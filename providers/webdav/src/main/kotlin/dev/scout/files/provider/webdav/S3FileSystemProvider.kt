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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class S3FileSystemProvider @Inject constructor(
    private val metadataRepository: MetadataRepository,
    private val credentialVault: CredentialVault,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : FileSystemProvider {

    override val scheme: String = "s3"

    data class S3Context(
        val connection: ServerConnection,
        val credentials: ServerCredentials?,
        val endpoint: String,
        val region: String,
        val bucket: String,
        val key: String,
        val accessKey: String,
        val secretKey: String
    )

    private suspend fun resolveContext(uri: VfsUri): S3Context {
        val connectionId = uri.authority ?: uri.host ?: uri.segments.firstOrNull()
            ?: throw VfsException.NotFound(uri, "Connection ID missing in URI: $uri")
        val connection = metadataRepository.getServerConnection(connectionId)
            ?: throw VfsException.NotFound(uri, "Server connection not found for id: $connectionId")
        val credentials = credentialVault.getCredentials(connectionId)

        val region = connection.domainOrRegion?.trim()?.ifEmpty { "us-east-1" } ?: "us-east-1"
        val endpoint = if (connection.host.isBlank() || connection.host.equals("s3.amazonaws.com", ignoreCase = true)) {
            "https://s3.$region.amazonaws.com"
        } else {
            val scheme = if (connection.useTls) "https" else "http"
            val defaultPort = if (connection.useTls) 443 else 80
            val portPart = if (connection.port > 0 && connection.port != defaultPort) ":${connection.port}" else ""
            val hostPart = connection.host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
            "$scheme://$hostPart$portPart"
        }

        val bucket = uri.segments.firstOrNull() ?: connection.shareOrBucket?.trim()?.trim('/')?.takeIf { it.isNotEmpty() } ?: "bucket"
        val key = uri.segments.drop(1).joinToString("/")

        val accessKey = connection.username
        val secretKey = credentials?.s3SecretKey ?: credentials?.password ?: ""

        return S3Context(connection, credentials, endpoint, region, bucket, key, accessKey, secretKey)
    }

    private inline fun <T> wrapS3Errors(uri: VfsUri, block: () -> T): T {
        return try {
            block()
        } catch (e: VfsException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw VfsException.NetworkTimeout(uri, e.message ?: "S3 timeout: $uri", e)
        } catch (e: java.net.ConnectException) {
            throw VfsException.NetworkTimeout(uri, e.message ?: "S3 connection failed: $uri", e)
        } catch (e: IOException) {
            throw VfsException.Io(uri, e.message ?: "S3 I/O error: $uri", e)
        }
    }

    private fun checkResponseStatus(uri: VfsUri, code: Int, message: String) {
        when (code) {
            200, 201, 204, 206 -> Unit
            401, 403 -> throw VfsException.AuthenticationRequired(uri, "S3 authentication/permission error ($code): $message")
            404 -> throw VfsException.NotFound(uri, "S3 object not found: $message")
            409 -> throw VfsException.AlreadyExists(uri, "S3 conflict: $message")
            408, 504 -> throw VfsException.NetworkTimeout(uri, "S3 network timeout: $message")
            else -> throw VfsException.Io(uri, "S3 request failed ($code): $message")
        }
    }

    override suspend fun listDirectory(uri: VfsUri): List<VfsNode> = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        wrapS3Errors(uri) {
            val prefix = if (context.key.isNotEmpty()) {
                if (context.key.endsWith('/')) context.key else "${context.key}/"
            } else ""

            val urlBuilder = "${context.endpoint}/${context.bucket}".toHttpUrl().newBuilder()
                .addQueryParameter("list-type", "2")
                .addQueryParameter("delimiter", "/")

            if (prefix.isNotEmpty()) {
                urlBuilder.addQueryParameter("prefix", prefix)
            }

            val rawRequest = Request.Builder()
                .url(urlBuilder.build())
                .get()
                .build()

            val signedRequest = AwsSigV4Signer.sign(
                request = rawRequest,
                accessKey = context.accessKey,
                secretKey = context.secretKey,
                region = context.region,
                service = "s3"
            )

            val response = okHttpClient.newCall(signedRequest).execute()
            response.use { resp ->
                checkResponseStatus(uri, resp.code, resp.message)
                val bodyStream = resp.body?.byteStream()
                    ?: throw VfsException.Io(uri, "Empty body in S3 ListObjectsV2 response")

                val result = S3XmlParser.parseListBucketResult(bodyStream)
                val nodes = mutableListOf<VfsNode>()

                // CommonPrefixes (Folders)
                result.commonPrefixes.forEach { commonPrefix ->
                    val cleanPrefix = commonPrefix.trimEnd('/')
                    val dirName = cleanPrefix.substringAfterLast('/')
                    val childUri = uri.resolve(dirName)
                    nodes.add(
                        VfsNode(
                            uri = childUri,
                            name = dirName,
                            isDirectory = true,
                            sizeBytes = 0L,
                            lastModifiedMillis = 0L
                        )
                    )
                }

                // Contents (Files)
                result.contents.forEach { s3Obj ->
                    if (s3Obj.key != prefix) {
                        val fileName = s3Obj.key.substringAfterLast('/')
                        if (fileName.isNotEmpty()) {
                            val childUri = uri.resolve(fileName)
                            nodes.add(
                                VfsNode(
                                    uri = childUri,
                                    name = fileName,
                                    isDirectory = false,
                                    sizeBytes = s3Obj.size,
                                    lastModifiedMillis = s3Obj.lastModifiedMillis,
                                    mimeType = getMimeType(fileName)
                                )
                            )
                        }
                    }
                }

                nodes
            }
        }
    }

    override suspend fun getMetadata(uri: VfsUri): VfsNode = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        if (context.key.isEmpty()) {
            return@withContext VfsNode(
                uri = uri,
                name = context.bucket,
                isDirectory = true,
                sizeBytes = 0L,
                lastModifiedMillis = 0L
            )
        }

        wrapS3Errors(uri) {
            val url = "${context.endpoint}/${context.bucket}/${context.key}".toHttpUrl()
            val rawRequest = Request.Builder()
                .url(url)
                .head()
                .build()

            val signedRequest = AwsSigV4Signer.sign(
                request = rawRequest,
                accessKey = context.accessKey,
                secretKey = context.secretKey,
                region = context.region,
                service = "s3"
            )

            val response = okHttpClient.newCall(signedRequest).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    val contentLength = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                    val lastModifiedStr = resp.header("Last-Modified")
                    val lastModifiedMillis = parseHttpDate(lastModifiedStr)
                    return@wrapS3Errors VfsNode(
                        uri = uri,
                        name = uri.name,
                        isDirectory = false,
                        sizeBytes = contentLength,
                        lastModifiedMillis = lastModifiedMillis,
                        mimeType = getMimeType(uri.name)
                    )
                }
            }

            // Fallback: Check if it's a prefix / directory
            val listUrl = "${context.endpoint}/${context.bucket}".toHttpUrl().newBuilder()
                .addQueryParameter("list-type", "2")
                .addQueryParameter("prefix", "${context.key}/")
                .addQueryParameter("max-keys", "1")
                .build()

            val listRequest = AwsSigV4Signer.sign(
                request = Request.Builder().url(listUrl).get().build(),
                accessKey = context.accessKey,
                secretKey = context.secretKey,
                region = context.region,
                service = "s3"
            )

            val listResponse = okHttpClient.newCall(listRequest).execute()
            listResponse.use { resp ->
                if (resp.isSuccessful) {
                    val bodyStream = resp.body?.byteStream()
                    if (bodyStream != null) {
                        val result = S3XmlParser.parseListBucketResult(bodyStream)
                        if (result.contents.isNotEmpty() || result.commonPrefixes.isNotEmpty()) {
                            return@wrapS3Errors VfsNode(
                                uri = uri,
                                name = uri.name,
                                isDirectory = true,
                                sizeBytes = 0L,
                                lastModifiedMillis = 0L
                            )
                        }
                    }
                }
            }

            throw VfsException.NotFound(uri, "S3 object not found: ${context.key}")
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
        wrapS3Errors(uri) {
            val url = "${context.endpoint}/${context.bucket}/${context.key}".toHttpUrl()
            val rawRequest = Request.Builder().url(url).get().build()
            val signedRequest = AwsSigV4Signer.sign(
                request = rawRequest,
                accessKey = context.accessKey,
                secretKey = context.secretKey,
                region = context.region,
                service = "s3"
            )

            val response = okHttpClient.newCall(signedRequest).execute()
            if (!response.isSuccessful) {
                val code = response.code
                val msg = response.message
                response.close()
                checkResponseStatus(uri, code, msg)
            }
            val body = response.body ?: throw VfsException.NotFound(uri, "Empty body in S3 GetObject")
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
                val url = "${context.endpoint}/${context.bucket}/${context.key}".toHttpUrl()
                val rawRequest = Request.Builder()
                    .url(url)
                    .put(body)
                    .build()

                val signedRequest = AwsSigV4Signer.sign(
                    request = rawRequest,
                    accessKey = context.accessKey,
                    secretKey = context.secretKey,
                    region = context.region,
                    service = "s3"
                )

                val response = okHttpClient.newCall(signedRequest).execute()
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
                val url = "${context.endpoint}/${context.bucket}/${context.key}".toHttpUrl()
                val rawRequest = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$start-")
                    .get()
                    .build()

                val signedRequest = AwsSigV4Signer.sign(
                    request = rawRequest,
                    accessKey = context.accessKey,
                    secretKey = context.secretKey,
                    region = context.region,
                    service = "s3"
                )

                val response = okHttpClient.newCall(signedRequest).execute()
                if (!response.isSuccessful && response.code != 206) {
                    val code = response.code
                    val msg = response.message
                    response.close()
                    checkResponseStatus(uri, code, msg)
                }
                val body = response.body ?: throw IOException("Empty body from S3 range request")
                body.byteStream()
            }
        )
    }

    override suspend fun createDirectory(uri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        wrapS3Errors(uri) {
            val dirKey = if (context.key.endsWith('/')) context.key else "${context.key}/"
            val url = "${context.endpoint}/${context.bucket}/$dirKey".toHttpUrl()
            val emptyBody = ByteArray(0).toRequestBody("application/x-directory".toMediaTypeOrNull())
            val rawRequest = Request.Builder()
                .url(url)
                .put(emptyBody)
                .build()

            val signedRequest = AwsSigV4Signer.sign(
                request = rawRequest,
                accessKey = context.accessKey,
                secretKey = context.secretKey,
                region = context.region,
                service = "s3"
            )

            val response = okHttpClient.newCall(signedRequest).execute()
            response.use { resp ->
                checkResponseStatus(uri, resp.code, resp.message)
                resp.isSuccessful
            }
        }
    }

    override suspend fun delete(uri: VfsUri, recursive: Boolean): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        wrapS3Errors(uri) {
            if (recursive) {
                val prefix = if (context.key.endsWith('/')) context.key else "${context.key}/"
                val listUrl = "${context.endpoint}/${context.bucket}".toHttpUrl().newBuilder()
                    .addQueryParameter("list-type", "2")
                    .addQueryParameter("prefix", prefix)
                    .build()

                val listRequest = AwsSigV4Signer.sign(
                    request = Request.Builder().url(listUrl).get().build(),
                    accessKey = context.accessKey,
                    secretKey = context.secretKey,
                    region = context.region,
                    service = "s3"
                )

                val listResp = okHttpClient.newCall(listRequest).execute()
                listResp.use { resp ->
                    if (resp.isSuccessful) {
                        resp.body?.byteStream()?.let { stream ->
                            val result = S3XmlParser.parseListBucketResult(stream)
                            result.contents.forEach { obj ->
                                deleteSingleKey(context, obj.key)
                            }
                        }
                    }
                }
            }

            deleteSingleKey(context, context.key)
        }
    }

    private fun deleteSingleKey(context: S3Context, key: String): Boolean {
        val url = "${context.endpoint}/${context.bucket}/$key".toHttpUrl()
        val rawRequest = Request.Builder().url(url).delete().build()
        val signedRequest = AwsSigV4Signer.sign(
            request = rawRequest,
            accessKey = context.accessKey,
            secretKey = context.secretKey,
            region = context.region,
            service = "s3"
        )
        val response = okHttpClient.newCall(signedRequest).execute()
        return response.use { it.isSuccessful || it.code == 204 }
    }

    override suspend fun rename(sourceUri: VfsUri, targetUri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val sourceContext = resolveContext(sourceUri)
        val targetContext = resolveContext(targetUri)
        wrapS3Errors(sourceUri) {
            // S3 CopyObject: PUT /${targetBucket}/${targetKey} with x-amz-copy-source: /${sourceBucket}/${sourceKey}
            val copySource = "/${sourceContext.bucket}/${sourceContext.key}"
            val destUrl = "${targetContext.endpoint}/${targetContext.bucket}/${targetContext.key}".toHttpUrl()
            val rawRequest = Request.Builder()
                .url(destUrl)
                .put(ByteArray(0).toRequestBody(null))
                .header("x-amz-copy-source", copySource)
                .build()

            val signedRequest = AwsSigV4Signer.sign(
                request = rawRequest,
                accessKey = targetContext.accessKey,
                secretKey = targetContext.secretKey,
                region = targetContext.region,
                service = "s3"
            )

            val copyResponse = okHttpClient.newCall(signedRequest).execute()
            val copySuccess = copyResponse.use { it.isSuccessful }

            if (copySuccess) {
                deleteSingleKey(sourceContext, sourceContext.key)
                true
            } else {
                false
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

    private fun parseHttpDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(dateStr)).toEpochMilli()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
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
