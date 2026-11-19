package dev.scout.files.provider.webdav

import dev.scout.files.core.database.repository.MetadataRepository
import dev.scout.files.core.model.ServerConnection
import dev.scout.files.core.model.ServerCredentials
import dev.scout.files.core.model.VfsException
import dev.scout.files.core.model.VfsNode
import dev.scout.files.core.model.VfsUri
import dev.scout.files.core.security.CredentialVault
import dev.scout.files.core.vfs.FileSystemProvider
import dev.scout.files.core.vfs.RandomAccessContent
import dev.scout.files.core.vfs.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebDavFileSystemProvider @Inject constructor(
    private val metadataRepository: MetadataRepository,
    private val credentialVault: CredentialVault,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : FileSystemProvider {

    override val scheme: String = "webdav"

    data class WebDavContext(
        val connection: ServerConnection,
        val credentials: ServerCredentials?,
        val targetUrl: String,
        val relativePath: String,
        val authHeader: String?
    )

    private suspend fun resolveContext(uri: VfsUri): WebDavContext {
        val connectionId = uri.authority ?: uri.host ?: uri.segments.firstOrNull()
            ?: throw VfsException.NotFound(uri, "Connection ID missing in URI: $uri")
        val connection = metadataRepository.getServerConnection(connectionId)
            ?: throw VfsException.NotFound(uri, "Server connection not found for id: $connectionId")
        val credentials = credentialVault.getCredentials(connectionId)

        val httpScheme = if (connection.useTls) "https" else "http"
        val defaultPort = if (connection.useTls) 443 else 80
        val portPart = if (connection.port > 0 && connection.port != defaultPort) ":${connection.port}" else ""
        val hostPart = connection.host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')

        val rootPrefix = connection.rootPath.replace("{username}", connection.username).trim('/')
        val uriPath = uri.path.trim('/')
        val path = when {
            rootPrefix.isEmpty() -> if (uriPath.isEmpty()) "/" else "/$uriPath"
            uriPath.isEmpty() -> "/$rootPrefix"
            uriPath == rootPrefix || uriPath.startsWith("$rootPrefix/") -> "/$uriPath"
            else -> "/$rootPrefix/$uriPath"
        }

        val targetUrl = "$httpScheme://$hostPart$portPart$path"
        val authHeader = if (connection.username.isNotEmpty()) {
            Credentials.basic(connection.username, credentials?.password ?: "")
        } else null

        return WebDavContext(connection, credentials, targetUrl, path, authHeader)
    }

    private inline fun <T> wrapHttpErrors(uri: VfsUri, block: () -> T): T {
        return try {
            block()
        } catch (e: VfsException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw VfsException.NetworkTimeout(uri, e.message ?: "WebDAV timeout: $uri", e)
        } catch (e: java.net.ConnectException) {
            throw VfsException.NetworkTimeout(uri, e.message ?: "WebDAV connection failed: $uri", e)
        } catch (e: IOException) {
            throw VfsException.Io(uri, e.message ?: "WebDAV I/O error: $uri", e)
        }
    }

    private fun checkResponseStatus(uri: VfsUri, code: Int, message: String) {
        when (code) {
            200, 201, 204, 206, 207 -> Unit
            401 -> throw VfsException.AuthenticationRequired(uri, "WebDAV authentication required: $message")
            403 -> throw VfsException.PermissionDenied(uri, "WebDAV access denied: $message")
            404 -> throw VfsException.NotFound(uri, "WebDAV resource not found: $message")
            409 -> throw VfsException.AlreadyExists(uri, "WebDAV resource conflict / already exists: $message")
            408, 504 -> throw VfsException.NetworkTimeout(uri, "WebDAV timeout: $message")
            else -> throw VfsException.Io(uri, "WebDAV request failed ($code): $message")
        }
    }

    override suspend fun listDirectory(uri: VfsUri): List<VfsNode> = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        wrapHttpErrors(uri) {
            val propfindXml = """
                <?xml version="1.0" encoding="utf-8" ?>
                <D:propfind xmlns:D="DAV:">
                  <D:prop>
                    <D:resourcetype/>
                    <D:getcontentlength/>
                    <D:getlastmodified/>
                    <D:displayname/>
                  </D:prop>
                </D:propfind>
            """.trimIndent()

            val body = propfindXml.toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull())
            val requestBuilder = Request.Builder()
                .url(context.targetUrl)
                .method("PROPFIND", body)
                .header("Depth", "1")

            context.authHeader?.let { requestBuilder.header("Authorization", it) }
            val response = okHttpClient.newCall(requestBuilder.build()).execute()

            response.use { resp ->
                checkResponseStatus(uri, resp.code, resp.message)
                val bodyStream = resp.body?.byteStream()
                    ?: throw VfsException.Io(uri, "Empty response body from WebDAV PROPFIND")

                val resources = WebDavXmlParser.parseMultistatus(bodyStream)
                val normalizedRequestedPath = context.relativePath.trimEnd('/')

                resources.filter { res ->
                    val resPath = try {
                        URI(res.href).path ?: res.href
                    } catch (_: Exception) {
                        res.href
                    }
                    resPath.trimEnd('/') != normalizedRequestedPath
                }.map { res ->
                    val resPath = try {
                        URI(res.href).path ?: res.href
                    } catch (_: Exception) {
                        res.href
                    }
                    val name = res.displayName?.ifEmpty { null }
                        ?: resPath.trimEnd('/').substringAfterLast('/').ifEmpty { "unnamed" }
                    val childUri = uri.resolve(name)
                    VfsNode(
                        uri = childUri,
                        name = name,
                        isDirectory = res.isDirectory,
                        sizeBytes = if (res.isDirectory) 0L else res.contentLength,
                        lastModifiedMillis = res.lastModifiedMillis,
                        mimeType = if (res.isDirectory) null else getMimeType(name)
                    )
                }
            }
        }
    }

    override suspend fun getMetadata(uri: VfsUri): VfsNode = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        wrapHttpErrors(uri) {
            val propfindXml = """
                <?xml version="1.0" encoding="utf-8" ?>
                <D:propfind xmlns:D="DAV:">
                  <D:prop>
                    <D:resourcetype/>
                    <D:getcontentlength/>
                    <D:getlastmodified/>
                    <D:displayname/>
                  </D:prop>
                </D:propfind>
            """.trimIndent()

            val body = propfindXml.toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull())
            val requestBuilder = Request.Builder()
                .url(context.targetUrl)
                .method("PROPFIND", body)
                .header("Depth", "0")

            context.authHeader?.let { requestBuilder.header("Authorization", it) }
            val response = okHttpClient.newCall(requestBuilder.build()).execute()

            response.use { resp ->
                checkResponseStatus(uri, resp.code, resp.message)
                val bodyStream = resp.body?.byteStream()
                    ?: throw VfsException.NotFound(uri, "Resource not found at ${context.targetUrl}")

                val resources = WebDavXmlParser.parseMultistatus(bodyStream)
                val resource = resources.firstOrNull()
                    ?: throw VfsException.NotFound(uri, "Resource not found at ${context.targetUrl}")

                val name = uri.name.ifEmpty { resource.displayName ?: "/" }
                VfsNode(
                    uri = uri,
                    name = name,
                    isDirectory = resource.isDirectory,
                    sizeBytes = if (resource.isDirectory) 0L else resource.contentLength,
                    lastModifiedMillis = resource.lastModifiedMillis,
                    mimeType = if (resource.isDirectory) null else getMimeType(name)
                )
            }
        }
    }

    override suspend fun exists(uri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        try {
            getMetadata(uri)
            true
        } catch (_: VfsException.NotFound) {
            false
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun openInputStream(uri: VfsUri): InputStream = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        wrapHttpErrors(uri) {
            val requestBuilder = Request.Builder().url(context.targetUrl).get()
            context.authHeader?.let { requestBuilder.header("Authorization", it) }
            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                val code = response.code
                val msg = response.message
                response.close()
                checkResponseStatus(uri, code, msg)
            }
            val body = response.body ?: throw VfsException.NotFound(uri, "Empty body from ${context.targetUrl}")
            val byteStream = body.byteStream()
            object : InputStream() {
                private var isClosed = false
                override fun read(): Int = byteStream.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = byteStream.read(b, off, len)
                override fun available(): Int = byteStream.available()
                override fun close() {
                    if (isClosed) return
                    isClosed = true
                    try { byteStream.close() } catch (_: Exception) {}
                    try { response.close() } catch (_: Exception) {}
                }
            }
        }
    }

    override suspend fun openOutputStream(uri: VfsUri, append: Boolean): OutputStream = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        val buffer = ByteArrayOutputStream()
        object : OutputStream() {
            private var isClosed = false
            override fun write(b: Int) = buffer.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = buffer.write(b, off, len)
            override fun flush() = buffer.flush()
            override fun close() {
                if (isClosed) return
                isClosed = true
                buffer.close()
                val bytes = buffer.toByteArray()
                val body = bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                val requestBuilder = Request.Builder()
                    .url(context.targetUrl)
                    .put(body)

                context.authHeader?.let { requestBuilder.header("Authorization", it) }
                val response = okHttpClient.newCall(requestBuilder.build()).execute()
                response.use { resp ->
                    checkResponseStatus(uri, resp.code, resp.message)
                }
            }
        }
    }

    override suspend fun openRandomAccess(uri: VfsUri, mode: String): RandomAccessContent = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        val metadata = getMetadata(uri)
        HttpRangeRandomAccessContent(
            length = metadata.sizeBytes,
            dispatcher = ioDispatcher,
            openRangeStream = { start ->
                val requestBuilder = Request.Builder()
                    .url(context.targetUrl)
                    .header("Range", "bytes=$start-")
                    .get()

                context.authHeader?.let { requestBuilder.header("Authorization", it) }
                val response = okHttpClient.newCall(requestBuilder.build()).execute()
                if (!response.isSuccessful && response.code != 206) {
                    val code = response.code
                    val msg = response.message
                    response.close()
                    checkResponseStatus(uri, code, msg)
                }
                val body = response.body ?: throw IOException("Empty body from range request")
                body.byteStream()
            }
        )
    }

    override suspend fun createDirectory(uri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        wrapHttpErrors(uri) {
            val urlWithSlash = if (context.targetUrl.endsWith('/')) context.targetUrl else "${context.targetUrl}/"
            val requestBuilder = Request.Builder()
                .url(urlWithSlash)
                .method("MKCOL", null)

            context.authHeader?.let { requestBuilder.header("Authorization", it) }
            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            response.use { resp ->
                if (resp.code == 405) {
                    throw VfsException.AlreadyExists(uri, "Collection already exists at ${context.targetUrl}")
                }
                checkResponseStatus(uri, resp.code, resp.message)
                resp.isSuccessful || resp.code == 201
            }
        }
    }

    override suspend fun delete(uri: VfsUri, recursive: Boolean): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        wrapHttpErrors(uri) {
            val requestBuilder = Request.Builder()
                .url(context.targetUrl)
                .delete()

            context.authHeader?.let { requestBuilder.header("Authorization", it) }
            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            response.use { resp ->
                checkResponseStatus(uri, resp.code, resp.message)
                resp.isSuccessful || resp.code == 204
            }
        }
    }

    override suspend fun rename(sourceUri: VfsUri, targetUri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val sourceContext = resolveContext(sourceUri)
        val targetContext = resolveContext(targetUri)
        wrapHttpErrors(sourceUri) {
            val requestBuilder = Request.Builder()
                .url(sourceContext.targetUrl)
                .method("MOVE", null)
                .header("Destination", targetContext.targetUrl)
                .header("Overwrite", "T")

            sourceContext.authHeader?.let { requestBuilder.header("Authorization", it) }
            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            response.use { resp ->
                checkResponseStatus(sourceUri, resp.code, resp.message)
                resp.isSuccessful || resp.code == 201 || resp.code == 204
            }
        }
    }

    override suspend fun copyTo(
        sourceUri: VfsUri,
        targetUri: VfsUri,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)?
    ): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val totalBytes = runCatching { getMetadata(sourceUri).sizeBytes }.getOrDefault(0L)
        openInputStream(sourceUri).use { input ->
            openOutputStream(targetUri).use { output ->
                val buffer = ByteArray(32 * 1024)
                var bytesCopied = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    ensureActive()
                    output.write(buffer, 0, read)
                    bytesCopied += read
                    onProgress?.invoke(bytesCopied, totalBytes)
                }
                output.flush()
            }
        }
        true
    }

    private fun getMimeType(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt", "md", "log", "json" -> "text/plain"
            "html", "htm" -> "text/html"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "zip" -> "application/zip"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            else -> null
        }
    }
}
