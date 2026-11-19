package dev.scout.files.provider.sftp

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
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SftpFileSystemProvider @Inject constructor(
    private val metadataRepository: MetadataRepository,
    private val credentialVault: CredentialVault,
    private val sessionPool: SftpSessionPool,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : FileSystemProvider {

    override val scheme: String = "sftp"

    private suspend fun resolveContext(uri: VfsUri): Triple<ServerConnection, ServerCredentials?, String> {
        val connectionId = uri.authority ?: uri.host ?: uri.segments.firstOrNull()
            ?: throw VfsException.NotFound(uri, "Connection ID missing in URI: $uri")
        val connection = metadataRepository.getServerConnection(connectionId)
            ?: throw VfsException.NotFound(uri, "Server connection not found for id: $connectionId")
        val credentials = credentialVault.getCredentials(connectionId)
        val remotePath = resolveRemotePath(uri, connection)
        return Triple(connection, credentials, remotePath)
    }

    private fun resolveRemotePath(uri: VfsUri, connection: ServerConnection): String {
        val root = connection.rootPath.trim('/')
        val rawPath = uri.path.trim('/')
        return when {
            root.isEmpty() -> if (rawPath.isEmpty()) "/" else "/$rawPath"
            rawPath.isEmpty() -> "/$root"
            rawPath == root || rawPath.startsWith("$root/") -> "/$rawPath"
            else -> "/$root/$rawPath"
        }
    }

    private inline fun <T> wrapErrors(uri: VfsUri, block: () -> T): T {
        return try {
            block()
        } catch (e: VfsException) {
            throw e
        } catch (e: SFTPException) {
            when (e.statusCode) {
                Response.StatusCode.NO_SUCH_FILE, Response.StatusCode.NO_SUCH_PATH ->
                    throw VfsException.NotFound(uri, e.message ?: "Resource not found: $uri", e)
                Response.StatusCode.PERMISSION_DENIED ->
                    throw VfsException.PermissionDenied(uri, e.message ?: "Permission denied: $uri", e)
                Response.StatusCode.FILE_ALREADY_EXISTS ->
                    throw VfsException.AlreadyExists(uri, e.message ?: "Resource already exists: $uri", e)
                else ->
                    throw VfsException.Io(uri, e.message ?: "SFTP error at $uri", e)
            }
        } catch (e: UserAuthException) {
            throw VfsException.AuthenticationRequired(uri, e.message ?: "Authentication required: $uri", e)
        } catch (e: TransportException) {
            throw VfsException.NetworkTimeout(uri, e.message ?: "Network transport error: $uri", e)
        } catch (e: IOException) {
            throw VfsException.Io(uri, e.message ?: "I/O error accessing $uri", e)
        }
    }

    override suspend fun listDirectory(uri: VfsUri): List<VfsNode> = withContext(ioDispatcher) {
        ensureActive()
        val (connection, credentials, remotePath) = resolveContext(uri)
        wrapErrors(uri) {
            sessionPool.execute(connection, credentials) { sftp ->
                val items = sftp.ls(remotePath)
                items.filter { it.name != "." && it.name != ".." }.map { item ->
                    val isDir = item.isDirectory
                    val childUri = uri.resolve(item.name)
                    VfsNode(
                        uri = childUri,
                        name = item.name,
                        isDirectory = isDir,
                        sizeBytes = if (isDir) 0L else item.attributes.size,
                        lastModifiedMillis = item.attributes.mtime * 1000L,
                        permissions = item.attributes.mode?.toString(),
                        mimeType = if (isDir) null else getMimeType(item.name),
                        isHidden = item.name.startsWith(".")
                    )
                }
            }
        }
    }

    override suspend fun getMetadata(uri: VfsUri): VfsNode = withContext(ioDispatcher) {
        ensureActive()
        val (connection, credentials, remotePath) = resolveContext(uri)
        wrapErrors(uri) {
            sessionPool.execute(connection, credentials) { sftp ->
                val attrs = sftp.statExistence(remotePath)
                    ?: throw VfsException.NotFound(uri, "Resource not found: $uri")
                val isDir = attrs.mode?.type == FileMode.Type.DIRECTORY
                val name = uri.name.ifEmpty { connection.name }
                VfsNode(
                    uri = uri,
                    name = name,
                    isDirectory = isDir,
                    sizeBytes = if (isDir) 0L else attrs.size,
                    lastModifiedMillis = attrs.mtime * 1000L,
                    permissions = attrs.mode?.toString(),
                    mimeType = if (isDir) null else getMimeType(name),
                    isHidden = name.startsWith(".")
                )
            }
        }
    }

    override suspend fun exists(uri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val (connection, credentials, remotePath) = resolveContext(uri)
        wrapErrors(uri) {
            sessionPool.execute(connection, credentials) { sftp ->
                sftp.statExistence(remotePath) != null
            }
        }
    }

    override suspend fun openInputStream(uri: VfsUri): InputStream = withContext(ioDispatcher) {
        ensureActive()
        val (connection, credentials, remotePath) = resolveContext(uri)
        wrapErrors(uri) {
            val remoteFile = sessionPool.openRemoteFile(
                connection,
                credentials,
                remotePath,
                EnumSet.of(OpenMode.READ)
            )
            val stream = remoteFile.RemoteFileInputStream()
            object : InputStream() {
                override fun read(): Int = stream.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
                override fun available(): Int = stream.available()
                override fun skip(n: Long): Long = stream.skip(n)
                override fun close() {
                    try {
                        stream.close()
                    } finally {
                        remoteFile.close()
                    }
                }
            }
        }
    }

    override suspend fun openOutputStream(uri: VfsUri, append: Boolean): OutputStream = withContext(ioDispatcher) {
        ensureActive()
        val (connection, credentials, remotePath) = resolveContext(uri)
        wrapErrors(uri) {
            val modes = if (append) {
                EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.APPEND)
            } else {
                EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
            }
            val remoteFile = sessionPool.openRemoteFile(
                connection,
                credentials,
                remotePath,
                modes
            )
            val stream = if (append) {
                remoteFile.RemoteFileOutputStream(remoteFile.length())
            } else {
                remoteFile.RemoteFileOutputStream()
            }
            object : OutputStream() {
                override fun write(b: Int) = stream.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = stream.write(b, off, len)
                override fun flush() = stream.flush()
                override fun close() {
                    try {
                        stream.close()
                    } finally {
                        remoteFile.close()
                    }
                }
            }
        }
    }

    override suspend fun openRandomAccess(uri: VfsUri, mode: String): RandomAccessContent = withContext(ioDispatcher) {
        ensureActive()
        val (connection, credentials, remotePath) = resolveContext(uri)
        wrapErrors(uri) {
            val modes = if (mode.contains("w")) {
                EnumSet.of(OpenMode.READ, OpenMode.WRITE, OpenMode.CREAT)
            } else {
                EnumSet.of(OpenMode.READ)
            }
            val remoteFile = sessionPool.openRemoteFile(
                connection,
                credentials,
                remotePath,
                modes
            )
            SftpRandomAccessContent(remoteFile, ioDispatcher)
        }
    }

    override suspend fun createDirectory(uri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val (connection, credentials, remotePath) = resolveContext(uri)
        wrapErrors(uri) {
            sessionPool.execute(connection, credentials) { sftp ->
                sftp.mkdirs(remotePath)
                true
            }
        }
    }

    override suspend fun delete(uri: VfsUri, recursive: Boolean): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val (connection, credentials, remotePath) = resolveContext(uri)
        wrapErrors(uri) {
            sessionPool.execute(connection, credentials) { sftp ->
                val attrs = sftp.statExistence(remotePath)
                    ?: throw VfsException.NotFound(uri, "Resource not found: $uri")
                val isDir = attrs.mode?.type == FileMode.Type.DIRECTORY
                if (isDir) {
                    if (recursive) {
                        deleteRecursive(sftp, remotePath)
                    } else {
                        sftp.rmdir(remotePath)
                    }
                } else {
                    sftp.rm(remotePath)
                }
                true
            }
        }
    }

    private fun deleteRecursive(sftp: SFTPClient, path: String) {
        val attrs = sftp.statExistence(path) ?: return
        if (attrs.mode?.type == FileMode.Type.DIRECTORY) {
            val children = sftp.ls(path).filter { it.name != "." && it.name != ".." }
            for (child in children) {
                deleteRecursive(sftp, child.path)
            }
            sftp.rmdir(path)
        } else {
            sftp.rm(path)
        }
    }

    override suspend fun rename(sourceUri: VfsUri, targetUri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val (srcConnection, srcCredentials, srcPath) = resolveContext(sourceUri)
        val (_, _, tgtPath) = resolveContext(targetUri)
        wrapErrors(sourceUri) {
            sessionPool.execute(srcConnection, srcCredentials) { sftp ->
                sftp.rename(srcPath, tgtPath)
                true
            }
        }
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

    private fun getMimeType(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return fallbackMimeType(ext)
    }

    private fun fallbackMimeType(extension: String): String? {
        return when (extension) {
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
