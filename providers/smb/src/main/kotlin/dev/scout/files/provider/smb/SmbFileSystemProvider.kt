package dev.scout.files.provider.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.common.SMBRuntimeException
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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmbFileSystemProvider @Inject constructor(
    private val metadataRepository: MetadataRepository,
    private val credentialVault: CredentialVault,
    private val sessionPool: SmbSessionPool,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : FileSystemProvider {

    override val scheme: String = "smb"

    data class SmbContext(
        val connection: ServerConnection,
        val credentials: ServerCredentials?,
        val shareName: String,
        val remotePath: String
    )

    private suspend fun resolveContext(uri: VfsUri): SmbContext {
        val connectionId = uri.authority ?: uri.host ?: uri.segments.firstOrNull()
            ?: throw VfsException.NotFound(uri, "Connection ID missing in URI: $uri")
        val connection = metadataRepository.getServerConnection(connectionId)
            ?: throw VfsException.NotFound(uri, "Server connection not found for id: $connectionId")
        val credentials = credentialVault.getCredentials(connectionId)

        val shareName = uri.segments.firstOrNull() ?: connection.shareOrBucket?.trim()?.trim('/')?.takeIf { it.isNotEmpty() } ?: "shared"
        val remotePath = uri.segments.drop(1).joinToString("\\")

        return SmbContext(connection, credentials, shareName, remotePath)
    }

    private inline fun <T> wrapSmbErrors(uri: VfsUri, block: () -> T): T {
        return try {
            block()
        } catch (e: VfsException) {
            throw e
        } catch (e: SMBApiException) {
            when (e.status) {
                NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
                NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
                NtStatus.STATUS_NO_SUCH_FILE ->
                    throw VfsException.NotFound(uri, e.message ?: "Resource not found: $uri", e)
                NtStatus.STATUS_ACCESS_DENIED ->
                    throw VfsException.PermissionDenied(uri, e.message ?: "Access denied: $uri", e)
                NtStatus.STATUS_LOGON_FAILURE ->
                    throw VfsException.AuthenticationRequired(uri, e.message ?: "Authentication required: $uri", e)
                NtStatus.STATUS_OBJECT_NAME_COLLISION ->
                    throw VfsException.AlreadyExists(uri, e.message ?: "Resource already exists: $uri", e)
                NtStatus.STATUS_TIMEOUT, NtStatus.STATUS_IO_TIMEOUT ->
                    throw VfsException.NetworkTimeout(uri, e.message ?: "Network timeout accessing $uri", e)
                else ->
                    throw VfsException.Io(uri, e.message ?: "SMB error at $uri (status=${e.status})", e)
            }
        } catch (e: SMBRuntimeException) {
            throw VfsException.Io(uri, e.message ?: "SMB runtime error accessing $uri", e)
        } catch (e: IOException) {
            throw VfsException.Io(uri, e.message ?: "I/O error accessing $uri", e)
        }
    }

    override suspend fun listDirectory(uri: VfsUri): List<VfsNode> = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        wrapSmbErrors(uri) {
            sessionPool.withShare(context.connection, context.credentials, context.shareName) { diskShare ->
                val items = diskShare.list(context.remotePath)
                val baseUri = if (uri.segments.isEmpty()) uri.resolve(context.shareName) else uri
                items.filter { it.fileName != "." && it.fileName != ".." }.map { item ->
                    val isDir = (item.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                    val isHidden = (item.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value) != 0L ||
                        item.fileName.startsWith(".")
                    val childUri = baseUri.resolve(item.fileName)
                    VfsNode(
                        uri = childUri,
                        name = item.fileName,
                        isDirectory = isDir,
                        sizeBytes = if (isDir) 0L else item.endOfFile,
                        lastModifiedMillis = item.lastWriteTime.toEpochMillis(),
                        mimeType = if (isDir) null else getMimeType(item.fileName),
                        isHidden = isHidden
                    )
                }
            }
        }
    }

    override suspend fun getMetadata(uri: VfsUri): VfsNode = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        wrapSmbErrors(uri) {
            sessionPool.withShare(context.connection, context.credentials, context.shareName) { diskShare ->
                if (context.remotePath.isEmpty()) {
                    // Share root
                    VfsNode(
                        uri = uri,
                        name = context.shareName,
                        isDirectory = true,
                        sizeBytes = 0L,
                        lastModifiedMillis = 0L,
                        mimeType = null,
                        isHidden = false
                    )
                } else {
                    val info = diskShare.getFileInformation(context.remotePath)
                    val isDir = info.standardInformation.isDirectory
                    val isHidden = (info.basicInformation.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value) != 0L ||
                        uri.name.startsWith(".")
                    VfsNode(
                        uri = uri,
                        name = uri.name.ifEmpty { context.shareName },
                        isDirectory = isDir,
                        sizeBytes = if (isDir) 0L else info.standardInformation.endOfFile,
                        lastModifiedMillis = info.basicInformation.lastWriteTime.toEpochMillis(),
                        mimeType = if (isDir) null else getMimeType(uri.name),
                        isHidden = isHidden
                    )
                }
            }
        }
    }

    override suspend fun exists(uri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        wrapSmbErrors(uri) {
            sessionPool.withShare(context.connection, context.credentials, context.shareName) { diskShare ->
                if (context.remotePath.isEmpty()) {
                    diskShare.isConnected
                } else {
                    diskShare.fileExists(context.remotePath) || diskShare.folderExists(context.remotePath)
                }
            }
        }
    }

    override suspend fun openInputStream(uri: VfsUri): InputStream = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        wrapSmbErrors(uri) {
            val file = sessionPool.openFile(
                context.connection,
                context.credentials,
                context.shareName,
                context.remotePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            )
            val stream = file.getInputStream()
            object : InputStream() {
                override fun read(): Int = stream.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
                override fun available(): Int = stream.available()
                override fun skip(n: Long): Long = stream.skip(n)
                override fun close() {
                    try {
                        stream.close()
                    } finally {
                        file.close()
                    }
                }
            }
        }
    }

    override suspend fun openOutputStream(uri: VfsUri, append: Boolean): OutputStream = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        wrapSmbErrors(uri) {
            val disposition = if (append) SMB2CreateDisposition.FILE_OPEN_IF else SMB2CreateDisposition.FILE_OVERWRITE_IF
            val accessMask = if (append) {
                EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE, AccessMask.FILE_APPEND_DATA)
            } else {
                EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE)
            }
            val file = sessionPool.openFile(
                context.connection,
                context.credentials,
                context.shareName,
                context.remotePath,
                accessMask,
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
                disposition,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            )
            val stream = file.getOutputStream(append)
            object : OutputStream() {
                override fun write(b: Int) = stream.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = stream.write(b, off, len)
                override fun flush() = stream.flush()
                override fun close() {
                    try {
                        stream.close()
                    } finally {
                        file.close()
                    }
                }
            }
        }
    }

    override suspend fun openRandomAccess(uri: VfsUri, mode: String): RandomAccessContent = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        wrapSmbErrors(uri) {
            val accessMask = if (mode.contains("w")) {
                EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE)
            } else {
                EnumSet.of(AccessMask.GENERIC_READ)
            }
            val disposition = if (mode.contains("w")) {
                SMB2CreateDisposition.FILE_OPEN_IF
            } else {
                SMB2CreateDisposition.FILE_OPEN
            }
            val file = sessionPool.openFile(
                context.connection,
                context.credentials,
                context.shareName,
                context.remotePath,
                accessMask,
                if (mode.contains("w")) EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL) else null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
                disposition,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            )
            SmbRandomAccessContent(file, ioDispatcher)
        }
    }

    override suspend fun createDirectory(uri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        wrapSmbErrors(uri) {
            sessionPool.withShare(context.connection, context.credentials, context.shareName) { diskShare ->
                diskShare.mkdir(context.remotePath)
                true
            }
        }
    }

    override suspend fun delete(uri: VfsUri, recursive: Boolean): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val context = resolveContext(uri)
        wrapSmbErrors(uri) {
            sessionPool.withShare(context.connection, context.credentials, context.shareName) { diskShare ->
                if (diskShare.folderExists(context.remotePath)) {
                    diskShare.rmdir(context.remotePath, recursive)
                } else {
                    diskShare.rm(context.remotePath)
                }
                true
            }
        }
    }

    override suspend fun rename(sourceUri: VfsUri, targetUri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val srcContext = resolveContext(sourceUri)
        val tgtContext = resolveContext(targetUri)
        wrapSmbErrors(sourceUri) {
            sessionPool.withShare(srcContext.connection, srcContext.credentials, srcContext.shareName) { diskShare ->
                val entry = if (diskShare.folderExists(srcContext.remotePath)) {
                    diskShare.openDirectory(
                        srcContext.remotePath,
                        EnumSet.of(AccessMask.GENERIC_READ, AccessMask.DELETE),
                        null,
                        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE)
                    )
                } else {
                    diskShare.openFile(
                        srcContext.remotePath,
                        EnumSet.of(AccessMask.GENERIC_READ, AccessMask.DELETE),
                        null,
                        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
                    )
                }
                entry.use {
                    it.rename(tgtContext.remotePath, true)
                }
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
