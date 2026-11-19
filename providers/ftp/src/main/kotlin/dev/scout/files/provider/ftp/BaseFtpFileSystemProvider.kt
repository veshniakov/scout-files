package dev.scout.files.provider.ftp

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
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException

abstract class BaseFtpFileSystemProvider(
    private val metadataRepository: MetadataRepository,
    private val credentialVault: CredentialVault,
    private val clientFactory: FtpClientFactory,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : FileSystemProvider {

    protected suspend fun resolveContext(uri: VfsUri): Triple<ServerConnection, ServerCredentials?, String> {
        val connectionId = uri.authority ?: uri.host ?: uri.segments.firstOrNull()
            ?: throw VfsException.NotFound(uri, "Connection ID missing in URI: $uri")
        val connection = metadataRepository.getServerConnection(connectionId)
            ?: throw VfsException.NotFound(uri, "Server connection not found for id: $connectionId")
        val credentials = credentialVault.getCredentials(connectionId)
        val remotePath = resolveRemotePath(uri, connection)
        return Triple(connection, credentials, remotePath)
    }

    protected fun resolveRemotePath(uri: VfsUri, connection: ServerConnection): String {
        val root = connection.rootPath.trim('/')
        val rawPath = uri.path.trim('/')
        return when {
            root.isEmpty() -> if (rawPath.isEmpty()) "/" else "/$rawPath"
            rawPath.isEmpty() -> "/$root"
            rawPath == root || rawPath.startsWith("$root/") -> "/$rawPath"
            else -> "/$root/$rawPath"
        }
    }

    protected fun configureClient(client: FTPClient, connection: ServerConnection, credentials: ServerCredentials?) {
        val defaultPort = if (scheme == "ftps") 990 else 21
        val targetPort = if (connection.port > 0) connection.port else defaultPort
        client.connect(connection.host, targetPort)
        val reply = client.replyCode
        if (!FTPReply.isPositiveCompletion(reply)) {
            if (client.isConnected) {
                try { client.disconnect() } catch (_: Exception) {}
            }
            throw VfsException.Io(connection.rootVfsUri(), "FTP connection refused: reply code $reply")
        }

        val user = connection.username.ifEmpty { "anonymous" }
        val pass = credentials?.password ?: ""
        val loggedIn = client.login(user, pass)
        if (!loggedIn) {
            if (client.isConnected) {
                try { client.disconnect() } catch (_: Exception) {}
            }
            throw VfsException.AuthenticationRequired(connection.rootVfsUri(), "FTP authentication failed for user: $user")
        }

        if (client is FTPSClient) {
            client.execPBSZ(0)
            client.execPROT("P")
        }

        client.enterLocalPassiveMode()
        client.setFileType(FTP.BINARY_FILE_TYPE)
        client.controlEncoding = "UTF-8"
        client.sendCommand("OPTS UTF8", "ON")
    }

    protected fun <T> withClient(
        connection: ServerConnection,
        credentials: ServerCredentials?,
        block: (FTPClient) -> T
    ): T {
        val isImplicit = connection.port == 990
        val client = if (scheme == "ftps") {
            clientFactory.createFtpsClient(isImplicit)
        } else {
            clientFactory.createFtpClient()
        }
        try {
            configureClient(client, connection, credentials)
            return block(client)
        } finally {
            try { client.logout() } catch (_: Exception) {}
            try {
                if (client.isConnected) {
                    client.disconnect()
                }
            } catch (_: Exception) {}
        }
    }

    protected fun <T> wrapFtpErrors(uri: VfsUri, block: () -> T): T {
        return try {
            block()
        } catch (e: VfsException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw VfsException.NetworkTimeout(uri, e.message ?: "FTP network timeout", e)
        } catch (e: ConnectException) {
            throw VfsException.NetworkTimeout(uri, e.message ?: "FTP connection failed", e)
        } catch (e: IOException) {
            throw VfsException.Io(uri, e.message ?: "FTP I/O error", e)
        }
    }

    protected fun getMimeType(name: String): String? {
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

    override suspend fun listDirectory(uri: VfsUri): List<VfsNode> = withContext(ioDispatcher) {
        ensureActive()
        val (connection, credentials, remotePath) = resolveContext(uri)
        wrapFtpErrors(uri) {
            withClient(connection, credentials) { client ->
                val targetPath = if (remotePath.isEmpty()) "/" else remotePath
                val files = client.listFiles(targetPath) ?: emptyArray()
                files.filter { it.name != "." && it.name != ".." }.map { file ->
                    val isDir = file.isDirectory
                    val childUri = uri.resolve(file.name)
                    VfsNode(
                        uri = childUri,
                        name = file.name,
                        isDirectory = isDir,
                        sizeBytes = if (isDir) 0L else file.size,
                        lastModifiedMillis = file.timestamp?.timeInMillis ?: System.currentTimeMillis(),
                        mimeType = if (isDir) null else getMimeType(file.name)
                    )
                }
            }
        }
    }

    override suspend fun getMetadata(uri: VfsUri): VfsNode = withContext(ioDispatcher) {
        ensureActive()
        val (connection, credentials, remotePath) = resolveContext(uri)
        if (uri.path == "/" || uri.segments.isEmpty() || remotePath == "/" || remotePath.isEmpty()) {
            return@withContext VfsNode(
                uri = uri,
                name = uri.name.ifEmpty { connection.name },
                isDirectory = true,
                sizeBytes = 0L,
                lastModifiedMillis = 0L
            )
        }
        wrapFtpErrors(uri) {
            withClient(connection, credentials) { client ->
                // Try MLST first
                val mlistFile = runCatching { client.mlistFile(remotePath) }.getOrNull()
                if (mlistFile != null) {
                    val isDir = mlistFile.isDirectory
                    return@withClient VfsNode(
                        uri = uri,
                        name = uri.name,
                        isDirectory = isDir,
                        sizeBytes = if (isDir) 0L else mlistFile.size,
                        lastModifiedMillis = mlistFile.timestamp?.timeInMillis ?: System.currentTimeMillis(),
                        mimeType = if (isDir) null else getMimeType(uri.name)
                    )
                }

                // Fallback: list target path or parent directory
                val files = client.listFiles(remotePath) ?: emptyArray()
                if (files.size == 1 && (files[0].name == uri.name || !files[0].isDirectory)) {
                    val file = files[0]
                    val isDir = file.isDirectory
                    return@withClient VfsNode(
                        uri = uri,
                        name = uri.name,
                        isDirectory = isDir,
                        sizeBytes = if (isDir) 0L else file.size,
                        lastModifiedMillis = file.timestamp?.timeInMillis ?: System.currentTimeMillis(),
                        mimeType = if (isDir) null else getMimeType(uri.name)
                    )
                }

                // Check parent directory
                val parentSegments = remotePath.trim('/').split('/').dropLast(1)
                val parentPath = if (parentSegments.isEmpty()) "/" else "/" + parentSegments.joinToString("/")
                val parentFiles = client.listFiles(parentPath) ?: emptyArray()
                val match = parentFiles.firstOrNull { it.name == uri.name }
                if (match != null) {
                    val isDir = match.isDirectory
                    return@withClient VfsNode(
                        uri = uri,
                        name = uri.name,
                        isDirectory = isDir,
                        sizeBytes = if (isDir) 0L else match.size,
                        lastModifiedMillis = match.timestamp?.timeInMillis ?: System.currentTimeMillis(),
                        mimeType = if (isDir) null else getMimeType(uri.name)
                    )
                }

                throw VfsException.NotFound(uri, "Resource not found at $remotePath")
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
        val (connection, credentials, remotePath) = resolveContext(uri)
        wrapFtpErrors(uri) {
            val isImplicit = connection.port == 990
            val client = if (scheme == "ftps") {
                clientFactory.createFtpsClient(isImplicit)
            } else {
                clientFactory.createFtpClient()
            }
            try {
                configureClient(client, connection, credentials)
                val stream = client.retrieveFileStream(remotePath)
                    ?: throw VfsException.NotFound(uri, "Could not open input stream for $remotePath: ${client.replyString}")

                object : InputStream() {
                    private var isClosed = false

                    override fun read(): Int = stream.read()

                    override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)

                    override fun available(): Int = stream.available()

                    override fun close() {
                        if (isClosed) return
                        isClosed = true
                        try { stream.close() } catch (_: Exception) {}
                        try { client.completePendingCommand() } catch (_: Exception) {}
                        try { client.logout() } catch (_: Exception) {}
                        try {
                            if (client.isConnected) client.disconnect()
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                try { client.logout() } catch (_: Exception) {}
                try {
                    if (client.isConnected) client.disconnect()
                } catch (_: Exception) {}
                throw e
            }
        }
    }

    override suspend fun openOutputStream(uri: VfsUri, append: Boolean): OutputStream = withContext(ioDispatcher) {
        ensureActive()
        val (connection, credentials, remotePath) = resolveContext(uri)
        wrapFtpErrors(uri) {
            val isImplicit = connection.port == 990
            val client = if (scheme == "ftps") {
                clientFactory.createFtpsClient(isImplicit)
            } else {
                clientFactory.createFtpClient()
            }
            try {
                configureClient(client, connection, credentials)
                val stream = if (append) {
                    client.appendFileStream(remotePath)
                } else {
                    client.storeFileStream(remotePath)
                } ?: throw VfsException.Io(uri, "Could not open output stream for $remotePath: ${client.replyString}")

                object : OutputStream() {
                    private var isClosed = false

                    override fun write(b: Int) = stream.write(b)

                    override fun write(b: ByteArray, off: Int, len: Int) = stream.write(b, off, len)

                    override fun flush() = stream.flush()

                    override fun close() {
                        if (isClosed) return
                        isClosed = true
                        try { stream.flush() } catch (_: Exception) {}
                        try { stream.close() } catch (_: Exception) {}
                        try { client.completePendingCommand() } catch (_: Exception) {}
                        try { client.logout() } catch (_: Exception) {}
                        try {
                            if (client.isConnected) client.disconnect()
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                try { client.logout() } catch (_: Exception) {}
                try {
                    if (client.isConnected) client.disconnect()
                } catch (_: Exception) {}
                throw e
            }
        }
    }

    override suspend fun openRandomAccess(uri: VfsUri, mode: String): RandomAccessContent = withContext(ioDispatcher) {
        ensureActive()
        val (connection, credentials, remotePath) = resolveContext(uri)
        val metadata = getMetadata(uri)
        wrapFtpErrors(uri) {
            val isImplicit = connection.port == 990
            val client = if (scheme == "ftps") {
                clientFactory.createFtpsClient(isImplicit)
            } else {
                clientFactory.createFtpClient()
            }
            try {
                configureClient(client, connection, credentials)
                FtpRandomAccessContent(
                    client = client,
                    remotePath = remotePath,
                    length = metadata.sizeBytes,
                    dispatcher = ioDispatcher
                )
            } catch (e: Exception) {
                try { client.logout() } catch (_: Exception) {}
                try {
                    if (client.isConnected) client.disconnect()
                } catch (_: Exception) {}
                throw e
            }
        }
    }

    override suspend fun createDirectory(uri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val (connection, credentials, remotePath) = resolveContext(uri)
        wrapFtpErrors(uri) {
            withClient(connection, credentials) { client ->
                val success = client.makeDirectory(remotePath)
                if (!success) {
                    val code = client.replyCode
                    if (code == 550) {
                        throw VfsException.AlreadyExists(uri, "Directory already exists or access denied: ${client.replyString}")
                    }
                }
                success
            }
        }
    }

    override suspend fun delete(uri: VfsUri, recursive: Boolean): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val (connection, credentials, remotePath) = resolveContext(uri)
        wrapFtpErrors(uri) {
            withClient(connection, credentials) { client ->
                deleteRecursive(client, remotePath, recursive)
            }
        }
    }

    private fun deleteRecursive(client: FTPClient, path: String, recursive: Boolean): Boolean {
        // Try file deletion first
        if (client.deleteFile(path)) return true

        // If directory and recursive requested
        if (recursive) {
            val children = client.listFiles(path) ?: emptyArray()
            for (child in children) {
                if (child.name == "." || child.name == "..") continue
                val childPath = "$path/${child.name}".replace("//", "/")
                if (child.isDirectory) {
                    deleteRecursive(client, childPath, true)
                } else {
                    client.deleteFile(childPath)
                }
            }
        }
        return client.removeDirectory(path)
    }

    override suspend fun rename(sourceUri: VfsUri, targetUri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val (sourceConnection, credentials, sourcePath) = resolveContext(sourceUri)
        val targetPath = resolveRemotePath(targetUri, sourceConnection)
        wrapFtpErrors(sourceUri) {
            withClient(sourceConnection, credentials) { client ->
                client.rename(sourcePath, targetPath)
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
}
