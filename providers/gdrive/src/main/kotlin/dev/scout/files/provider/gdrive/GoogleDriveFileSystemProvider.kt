package dev.scout.files.provider.gdrive

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleDriveFileSystemProvider(
    private val metadataRepository: MetadataRepository,
    private val credentialVault: CredentialVault,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val baseUrl: String = "https://www.googleapis.com",
    private val tokenUrl: String = "https://oauth2.googleapis.com/token"
) : FileSystemProvider {

    @Inject
    constructor(
        metadataRepository: MetadataRepository,
        credentialVault: CredentialVault,
        okHttpClient: OkHttpClient,
        @IoDispatcher ioDispatcher: CoroutineDispatcher
    ) : this(
        metadataRepository = metadataRepository,
        credentialVault = credentialVault,
        okHttpClient = okHttpClient,
        ioDispatcher = ioDispatcher,
        baseUrl = "https://www.googleapis.com",
        tokenUrl = "https://oauth2.googleapis.com/token"
    )

    override val scheme: String = "gdrive"

    internal val pathCache = ConcurrentHashMap<String, String>()
    internal val itemCache = ConcurrentHashMap<String, DriveFileItem>()
    private val refreshMutexes = ConcurrentHashMap<String, Mutex>()

    data class GDriveContext(
        val connection: ServerConnection,
        val credentials: ServerCredentials,
        val apiBaseUrl: String,
        val uploadBaseUrl: String,
        val tokenEndpointUrl: String
    )

    fun getCachedFileId(connectionId: String, path: String): String? {
        val normalized = normalizePath(path)
        if (normalized == "/") return "root"
        return pathCache["$connectionId:$normalized"]
    }

    fun setCachedFileId(connectionId: String, path: String, fileId: String) {
        val normalized = normalizePath(path)
        pathCache["$connectionId:$normalized"] = fileId
    }

    fun invalidateCache(connectionId: String, path: String? = null) {
        if (path == null) {
            pathCache.keys.removeIf { it.startsWith("$connectionId:") }
            itemCache.keys.removeIf { it.startsWith("$connectionId:") }
        } else {
            val normalized = normalizePath(path)
            val prefix = if (normalized == "/") "$connectionId:" else "$connectionId:$normalized/"
            pathCache.remove("$connectionId:$normalized")
            pathCache.keys.removeIf { it.startsWith(prefix) }
            itemCache.remove("$connectionId:$normalized")
            itemCache.keys.removeIf { it.startsWith(prefix) }
        }
    }

    private suspend fun resolveContext(uri: VfsUri): GDriveContext {
        val connectionId = uri.authority?.takeIf { it.isNotEmpty() }
            ?: uri.host?.takeIf { it.isNotEmpty() }
            ?: uri.segments.firstOrNull()?.takeIf { it.isNotEmpty() }
            ?: throw VfsException.NotFound(uri, "Connection ID missing in URI: $uri")

        val connection = metadataRepository.getServerConnection(connectionId)
            ?: throw VfsException.NotFound(uri, "Server connection not found for id: $connectionId")
        val credentials = credentialVault.getCredentials(connectionId)
            ?: throw VfsException.AuthenticationRequired(uri, "Credentials not found for connection: $connectionId")

        val isCustomHost = connection.host.isNotBlank() && !connection.host.contains("googleapis.com")
        val scheme = if (connection.useTls) "https" else "http"
        val (apiBase, uploadBase, tokenEndpoint) = if (isCustomHost && baseUrl == "https://www.googleapis.com") {
            val portPart = if (connection.port > 0) ":${connection.port}" else ""
            val base = "$scheme://${connection.host}$portPart"
            Triple("$base/drive/v3", "$base/upload/drive/v3", "$base/token")
        } else {
            val cleanBase = baseUrl.trimEnd('/')
            Triple("$cleanBase/drive/v3", "$cleanBase/upload/drive/v3", tokenUrl)
        }

        return GDriveContext(connection, credentials, apiBase, uploadBase, tokenEndpoint)
    }

    fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed == "/") return "/"
        val withSlash = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        val collapsed = withSlash.replace(Regex("/+"), "/")
        return if (collapsed.length > 1 && collapsed.endsWith("/")) {
            collapsed.substring(0, collapsed.length - 1)
        } else {
            collapsed
        }
    }

    fun getParentPath(path: String): String {
        val normalized = normalizePath(path)
        if (normalized == "/") return "/"
        val lastSlash = normalized.lastIndexOf('/')
        return if (lastSlash <= 0) "/" else normalized.substring(0, lastSlash)
    }

    fun getFileName(path: String): String {
        val normalized = normalizePath(path)
        if (normalized == "/") return ""
        return normalized.substringAfterLast('/')
    }

    fun getExportMimeType(driveMimeType: String): String? = when (driveMimeType) {
        "application/vnd.google-apps.document" -> "application/pdf"
        "application/vnd.google-apps.presentation" -> "application/pdf"
        "application/vnd.google-apps.drawing" -> "application/pdf"
        "application/vnd.google-apps.spreadsheet" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> if (driveMimeType.startsWith("application/vnd.google-apps.") && driveMimeType != "application/vnd.google-apps.folder") {
            "application/pdf"
        } else null
    }

    private fun mapNetworkException(uri: VfsUri, e: IOException): VfsException = when (e) {
        is SocketTimeoutException -> VfsException.NetworkTimeout(uri, "Google Drive request timed out: ${e.message}", e)
        is ConnectException -> VfsException.NetworkTimeout(uri, "Google Drive connection failed: ${e.message}", e)
        else -> VfsException.Io(uri, "Google Drive I/O error: ${e.message}", e)
    }

    private fun checkResponseStatus(uri: VfsUri, code: Int, message: String, responseBody: String? = null) {
        when (code) {
            200, 201, 204, 206 -> Unit
            401 -> throw VfsException.AuthenticationRequired(uri, "Google Drive authentication required: $message")
            403 -> {
                val error = responseBody?.let { GoogleDriveJsonParser.parseErrorResponse(it) }
                if (error?.reason == "exportSizeLimitExceeded") {
                    throw VfsException.Io(uri, "Google Workspace document export limit exceeded: ${error.message}")
                }
                throw VfsException.PermissionDenied(uri, "Google Drive access denied: $message")
            }
            404 -> throw VfsException.NotFound(uri, "Google Drive file or folder not found: $message")
            409 -> throw VfsException.AlreadyExists(uri, "Google Drive resource already exists: $message")
            408, 504 -> throw VfsException.NetworkTimeout(uri, "Google Drive request timed out: $message")
            else -> throw VfsException.Io(uri, "Google Drive request failed ($code): $message")
        }
    }

    private suspend fun executeWithAuth(
        context: GDriveContext,
        uri: VfsUri,
        requestFactory: (accessToken: String) -> Request
    ): Response {
        val token = context.credentials.oauthAccessToken ?: ""
        val request = requestFactory(token)
        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw mapNetworkException(uri, e)
        }
        if (response.code == 401) {
            response.close()
            val newToken = refreshToken(context, uri)
            val retryRequest = requestFactory(newToken)
            return try {
                okHttpClient.newCall(retryRequest).execute()
            } catch (e: IOException) {
                throw mapNetworkException(uri, e)
            }
        }
        return response
    }

    private suspend fun refreshToken(context: GDriveContext, uri: VfsUri): String {
        val connId = context.connection.id
        val mutex = refreshMutexes.computeIfAbsent(connId) { Mutex() }
        return mutex.withLock {
            val currentCreds = credentialVault.getCredentials(connId)
            if (currentCreds?.oauthAccessToken != null && currentCreds.oauthAccessToken != context.credentials.oauthAccessToken) {
                return@withLock currentCreds.oauthAccessToken!!
            }
            val refreshToken = currentCreds?.oauthRefreshToken ?: context.credentials.oauthRefreshToken
                ?: throw VfsException.AuthenticationRequired(uri, "OAuth 2.0 refresh token missing for connection $connId")

            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
            currentCreds?.oauthClientId?.takeIf { it.isNotBlank() }?.let { form.add("client_id", it) }
            currentCreds?.password?.takeIf { it.isNotBlank() }?.let { form.add("client_secret", it) }

            val tokenRequest = Request.Builder()
                .url(context.tokenEndpointUrl)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(form.build())
                .build()

            val tokenResponse = try {
                okHttpClient.newCall(tokenRequest).execute()
            } catch (e: IOException) {
                throw mapNetworkException(uri, e)
            }

            tokenResponse.use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    throw VfsException.AuthenticationRequired(uri, "Failed to refresh OAuth token (${resp.code}): ${resp.message}")
                }
                val newAccessToken = GoogleDriveJsonParser.parseAccessToken(body)
                    ?: throw VfsException.AuthenticationRequired(uri, "Access token absent in refresh response")
                val updated = (currentCreds ?: context.credentials).copy(oauthAccessToken = newAccessToken)
                credentialVault.storeCredentials(connId, updated)
                newAccessToken
            }
        }
    }

    private suspend fun resolvePathToFileId(
        context: GDriveContext,
        uri: VfsUri,
        path: String
    ): String {
        val normalized = normalizePath(path)
        if (normalized == "/") return "root"

        getCachedFileId(context.connection.id, normalized)?.let { return it }

        val segments = normalized.split('/').filter { it.isNotEmpty() }
        var currentParentId = "root"
        var currentPath = ""
        var startIndex = 0

        for (i in segments.size - 1 downTo 1) {
            val prefix = "/" + segments.take(i).joinToString("/")
            val cachedId = getCachedFileId(context.connection.id, prefix)
            if (cachedId != null) {
                currentParentId = cachedId
                currentPath = prefix
                startIndex = i
                break
            }
        }

        for (i in startIndex until segments.size) {
            val segment = segments[i]
            currentPath += "/$segment"

            val escaped = GoogleDriveJsonParser.escapeDriveQuery(segment)
            val query = "'$currentParentId' in parents and name = '$escaped' and trashed = false"
            val url = "${context.apiBaseUrl}/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("fields", "files(id,name,mimeType,size,modifiedTime,parents,trashed)")
                .addQueryParameter("pageSize", "10")
                .build()

            val response = executeWithAuth(context, uri) { token ->
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
            }

            val file = response.use { resp ->
                val body = resp.body?.string() ?: ""
                checkResponseStatus(uri, resp.code, resp.message, body)
                val files = GoogleDriveJsonParser.parseFileList(body)
                files.firstOrNull() ?: throw VfsException.NotFound(uri, "File or folder '$segment' not found under '$currentPath'")
            }

            setCachedFileId(context.connection.id, currentPath, file.id)
            itemCache["${context.connection.id}:$currentPath"] = file
            currentParentId = file.id
        }

        return currentParentId
    }

    private suspend fun fetchFileMetadata(context: GDriveContext, uri: VfsUri, fileId: String): DriveFileItem {
        val url = "${context.apiBaseUrl}/files/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "id,name,mimeType,size,modifiedTime,parents,trashed")
            .build()
        val response = executeWithAuth(context, uri) { token ->
            Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        }
        return response.use { resp ->
            val body = resp.body?.string() ?: ""
            checkResponseStatus(uri, resp.code, resp.message, body)
            GoogleDriveJsonParser.parseFileItem(body)
        }
    }

    override suspend fun listDirectory(uri: VfsUri): List<VfsNode> = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        val normalized = normalizePath(uri.path)
        val folderId = resolvePathToFileId(context, uri, normalized)

        val escapedFolderId = GoogleDriveJsonParser.escapeDriveQuery(folderId)
        val query = "'$escapedFolderId' in parents and trashed = false"
        val url = "${context.apiBaseUrl}/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("fields", "files(id,name,mimeType,size,modifiedTime,parents,trashed),nextPageToken")
            .addQueryParameter("pageSize", "1000")
            .build()

        val response = executeWithAuth(context, uri) { token ->
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        }

        response.use { resp ->
            val body = resp.body?.string() ?: ""
            checkResponseStatus(uri, resp.code, resp.message, body)
            val items = GoogleDriveJsonParser.parseFileList(body)
            items.map { item ->
                val childPath = if (normalized == "/") "/${item.name}" else "$normalized/${item.name}"
                setCachedFileId(context.connection.id, childPath, item.id)
                itemCache["${context.connection.id}:$childPath"] = item
                val isDir = item.isFolder
                VfsNode(
                    uri = uri.resolve(item.name),
                    name = item.name,
                    isDirectory = isDir,
                    sizeBytes = if (isDir) 0L else item.sizeBytes,
                    lastModifiedMillis = item.modifiedTimeMillis,
                    mimeType = item.mimeType,
                    extension = if (isDir) "" else item.name.substringAfterLast('.', "")
                )
            }
        }
    }

    override suspend fun getMetadata(uri: VfsUri): VfsNode = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        val normalized = normalizePath(uri.path)
        if (normalized == "/") {
            return@withContext VfsNode(
                uri = uri,
                name = "",
                isDirectory = true,
                sizeBytes = 0L,
                lastModifiedMillis = 0L,
                mimeType = "application/vnd.google-apps.folder"
            )
        }

        val fileId = resolvePathToFileId(context, uri, normalized)

        val item = itemCache["${context.connection.id}:$normalized"]
            ?: fetchFileMetadata(context, uri, fileId)

        VfsNode(
            uri = uri,
            name = item.name,
            isDirectory = item.isFolder,
            sizeBytes = if (item.isFolder) 0L else item.sizeBytes,
            lastModifiedMillis = item.modifiedTimeMillis,
            mimeType = item.mimeType,
            extension = if (item.isFolder) "" else item.name.substringAfterLast('.', "")
        )
    }

    override suspend fun exists(uri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val normalized = normalizePath(uri.path)
        if (normalized == "/") return@withContext true
        val context = try {
            resolveContext(uri)
        } catch (_: Exception) {
            return@withContext false
        }
        if (getCachedFileId(context.connection.id, normalized) != null) {
            return@withContext true
        }
        return@withContext try {
            resolvePathToFileId(context, uri, normalized)
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
        val normalized = normalizePath(uri.path)
        val fileId = resolvePathToFileId(context, uri, normalized)

        val fileItem = itemCache["${context.connection.id}:$normalized"]
            ?: fetchFileMetadata(context, uri, fileId)

        if (fileItem.isFolder) {
            throw VfsException.Io(uri, "Cannot open InputStream for directory: $uri")
        }

        val exportMime = getExportMimeType(fileItem.mimeType)
        val downloadUrl = if (exportMime != null) {
            "${context.apiBaseUrl}/files/$fileId/export".toHttpUrl().newBuilder()
                .addQueryParameter("mimeType", exportMime)
                .build()
        } else {
            "${context.apiBaseUrl}/files/$fileId?alt=media".toHttpUrl()
        }

        val downloadResponse = executeWithAuth(context, uri) { token ->
            Request.Builder().url(downloadUrl).header("Authorization", "Bearer $token").get().build()
        }

        if (!downloadResponse.isSuccessful) {
            val body = downloadResponse.body?.string()
            val code = downloadResponse.code
            val msg = downloadResponse.message
            downloadResponse.close()
            checkResponseStatus(uri, code, msg, body)
        }

        val responseBody = downloadResponse.body
            ?: throw VfsException.Io(uri, "Response body was null for download of $uri")

        object : InputStream() {
            private val delegate = responseBody.byteStream()
            override fun read(): Int = delegate.read()
            override fun read(b: ByteArray): Int = delegate.read(b)
            override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
            override fun available(): Int = delegate.available()
            override fun close() {
                try {
                    delegate.close()
                } finally {
                    downloadResponse.close()
                }
            }
        }
    }

    override suspend fun openOutputStream(uri: VfsUri, append: Boolean): OutputStream = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        val normalized = normalizePath(uri.path)
        val parentPath = getParentPath(normalized)
        val fileName = getFileName(normalized)
        val parentId = resolvePathToFileId(context, uri, parentPath)

        val existingFileId = getCachedFileId(context.connection.id, normalized)
            ?: runCatching { resolvePathToFileId(context, uri, normalized) }.getOrNull()

        object : ByteArrayOutputStream() {
            private var closed = false

            override fun close() {
                super.close()
                if (closed) return
                closed = true
                val bytes = toByteArray()
                kotlinx.coroutines.runBlocking(ioDispatcher) {
                    if (existingFileId != null) {
                        val uploadUrl = "${context.uploadBaseUrl}/files/$existingFileId?uploadType=media"
                        val requestBody = bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                        val response = executeWithAuth(context, uri) { token ->
                            Request.Builder()
                                .url(uploadUrl)
                                .header("Authorization", "Bearer $token")
                                .patch(requestBody)
                                .build()
                        }
                        response.use { resp ->
                            val body = resp.body?.string() ?: ""
                            checkResponseStatus(uri, resp.code, resp.message, body)
                        }
                        itemCache.remove("${context.connection.id}:$normalized")
                    } else {
                        val boundary = "scout_boundary_${UUID.randomUUID()}"
                        val metadataJson = GoogleDriveJsonParser.createFileMetadataJson(fileName, parentId)

                        val multipartBytes = ByteArrayOutputStream().apply {
                            write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                            write("Content-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray(Charsets.UTF_8))
                            write(metadataJson.toByteArray(Charsets.UTF_8))
                            write("\r\n--$boundary\r\n".toByteArray(Charsets.UTF_8))
                            write("Content-Type: application/octet-stream\r\n\r\n".toByteArray(Charsets.UTF_8))
                            write(bytes)
                            write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
                        }.toByteArray()

                        val uploadUrl = "${context.uploadBaseUrl}/files?uploadType=multipart"
                        val requestBody = multipartBytes.toRequestBody("multipart/related; boundary=$boundary".toMediaTypeOrNull())
                        val response = executeWithAuth(context, uri) { token ->
                            Request.Builder()
                                .url(uploadUrl)
                                .header("Authorization", "Bearer $token")
                                .post(requestBody)
                                .build()
                        }
                        val newFile = response.use { resp ->
                            val body = resp.body?.string() ?: ""
                            checkResponseStatus(uri, resp.code, resp.message, body)
                            GoogleDriveJsonParser.parseFileItem(body)
                        }
                        setCachedFileId(context.connection.id, normalized, newFile.id)
                        itemCache["${context.connection.id}:$normalized"] = newFile
                    }
                }
            }
        }
    }

    override suspend fun openRandomAccess(uri: VfsUri, mode: String): RandomAccessContent = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        val normalized = normalizePath(uri.path)
        val fileId = resolvePathToFileId(context, uri, normalized)

        val fileItem = itemCache["${context.connection.id}:$normalized"]
            ?: fetchFileMetadata(context, uri, fileId)

        if (fileItem.isFolder) {
            throw VfsException.Io(uri, "Cannot open random access for directory: $uri")
        }

        if (fileItem.isGoogleDoc) {
            throw UnsupportedOperationException("Random access is not supported on Google Workspace documents; use openInputStream")
        }

        GoogleDriveRandomAccessContent(
            length = fileItem.sizeBytes,
            dispatcher = ioDispatcher,
            openRangeStream = { start ->
                val url = "${context.apiBaseUrl}/files/$fileId?alt=media"
                val response = executeWithAuth(context, uri) { token ->
                    Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $token")
                        .header("Range", "bytes=$start-")
                        .get()
                        .build()
                }
                if (!response.isSuccessful) {
                    val body = response.body?.string()
                    val code = response.code
                    val msg = response.message
                    response.close()
                    checkResponseStatus(uri, code, msg, body)
                }
                val responseBody = response.body
                    ?: throw VfsException.Io(uri, "Response body was null for range request on $uri")
                object : InputStream() {
                    private val delegate = responseBody.byteStream()
                    override fun read(): Int = delegate.read()
                    override fun read(b: ByteArray): Int = delegate.read(b)
                    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
                    override fun available(): Int = delegate.available()
                    override fun close() {
                        try {
                            delegate.close()
                        } finally {
                            response.close()
                        }
                    }
                }
            }
        )
    }

    override suspend fun createDirectory(uri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        val normalized = normalizePath(uri.path)
        val parentPath = getParentPath(normalized)
        val dirName = getFileName(normalized)
        val parentId = resolvePathToFileId(context, uri, parentPath)

        val jsonBody = GoogleDriveJsonParser.createFolderJson(dirName, parentId)
        val requestBody = jsonBody.toRequestBody("application/json; charset=UTF-8".toMediaTypeOrNull())
        val url = "${context.apiBaseUrl}/files"
        val response = executeWithAuth(context, uri) { token ->
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .post(requestBody)
                .build()
        }

        val folder = response.use { resp ->
            val body = resp.body?.string() ?: ""
            checkResponseStatus(uri, resp.code, resp.message, body)
            GoogleDriveJsonParser.parseFileItem(body)
        }

        setCachedFileId(context.connection.id, normalized, folder.id)
        itemCache["${context.connection.id}:$normalized"] = folder
        true
    }

    override suspend fun rename(sourceUri: VfsUri, targetUri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(sourceUri)
        val sourcePath = normalizePath(sourceUri.path)
        val targetPath = normalizePath(targetUri.path)
        val fileId = resolvePathToFileId(context, sourceUri, sourcePath)

        val oldParentPath = getParentPath(sourcePath)
        val newParentPath = getParentPath(targetPath)
        val newName = getFileName(targetPath)

        val urlBuilder = "${context.apiBaseUrl}/files/$fileId".toHttpUrl().newBuilder()
        if (oldParentPath != newParentPath) {
            val oldParentId = resolvePathToFileId(context, sourceUri, oldParentPath)
            val newParentId = resolvePathToFileId(context, targetUri, newParentPath)
            urlBuilder.addQueryParameter("addParents", newParentId)
            urlBuilder.addQueryParameter("removeParents", oldParentId)
        }

        val jsonBody = GoogleDriveJsonParser.createRenameJson(newName)
        val requestBody = jsonBody.toRequestBody("application/json; charset=UTF-8".toMediaTypeOrNull())
        val response = executeWithAuth(context, sourceUri) { token ->
            Request.Builder()
                .url(urlBuilder.build())
                .header("Authorization", "Bearer $token")
                .patch(requestBody)
                .build()
        }

        response.use { resp ->
            val body = resp.body?.string() ?: ""
            checkResponseStatus(sourceUri, resp.code, resp.message, body)
        }

        invalidateCache(context.connection.id, sourcePath)
        setCachedFileId(context.connection.id, targetPath, fileId)
        true
    }

    override suspend fun delete(uri: VfsUri, recursive: Boolean): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        val normalized = normalizePath(uri.path)
        val fileId = resolvePathToFileId(context, uri, normalized)

        val url = "${context.apiBaseUrl}/files/$fileId"
        val response = executeWithAuth(context, uri) { token ->
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .delete()
                .build()
        }

        response.use { resp ->
            val body = resp.body?.string() ?: ""
            checkResponseStatus(uri, resp.code, resp.message, body)
        }

        invalidateCache(context.connection.id, normalized)
        true
    }

    override suspend fun copyTo(
        sourceUri: VfsUri,
        targetUri: VfsUri,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)?
    ): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val sourceMeta = getMetadata(sourceUri)
        if (sourceMeta.isDirectory) {
            createDirectory(targetUri)
            val children = listDirectory(sourceUri)
            for (child in children) {
                ensureActive()
                val childTarget = targetUri.resolve(child.name)
                copyTo(child.uri, childTarget, onProgress)
            }
            true
        } else {
            val totalBytes = sourceMeta.sizeBytes
            openInputStream(sourceUri).use { input ->
                openOutputStream(targetUri, append = false).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesCopied = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        ensureActive()
                        output.write(buffer, 0, read)
                        bytesCopied += read
                        onProgress?.invoke(bytesCopied, totalBytes)
                    }
                }
            }
            true
        }
    }
}
