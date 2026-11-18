package dev.scout.files.provider.local

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.scout.files.core.model.VfsException
import dev.scout.files.core.model.VfsNode
import dev.scout.files.core.model.VfsUri
import dev.scout.files.core.vfs.FileSystemProvider
import dev.scout.files.core.vfs.RandomAccessContent
import dev.scout.files.core.vfs.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage Access Framework (SAF) provider supporting `content://` URIs.
 * Interacts with external documents, OTG drives, and custom cloud providers via Android DocumentsContract.
 */
@Singleton
class SafFileSystemProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : FileSystemProvider {

    override val scheme: String = "content"

    private fun resolveDocument(uri: VfsUri): DocumentFile {
        val androidUri = Uri.parse(uri.raw)
        return if (DocumentFile.isDocumentUri(context, androidUri)) {
            DocumentFile.fromSingleUri(context, androidUri)
        } else {
            DocumentFile.fromTreeUri(context, androidUri)
        } ?: throw VfsException.NotFound(uri, "Cannot resolve DocumentFile for $uri")
    }

    override suspend fun listDirectory(uri: VfsUri): List<VfsNode> = withContext(ioDispatcher) {
        ensureActive()
        val doc = resolveDocument(uri)
        if (!doc.exists()) throw VfsException.NotFound(uri)
        if (!doc.isDirectory) throw VfsException.Io(uri, "Path is not a directory: $uri")

        doc.listFiles().map { child ->
            val childVfsUri = VfsUri(child.uri.toString())
            nodeFromDocument(childVfsUri, child)
        }
    }

    override suspend fun getMetadata(uri: VfsUri): VfsNode = withContext(ioDispatcher) {
        ensureActive()
        val doc = resolveDocument(uri)
        if (!doc.exists()) throw VfsException.NotFound(uri)
        nodeFromDocument(uri, doc)
    }

    override suspend fun exists(uri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        runCatching { resolveDocument(uri).exists() }.getOrDefault(false)
    }

    override suspend fun openInputStream(uri: VfsUri): InputStream = withContext(ioDispatcher) {
        ensureActive()
        val androidUri = Uri.parse(uri.raw)
        context.contentResolver.openInputStream(androidUri)
            ?: throw VfsException.NotFound(uri, "Could not open InputStream for $uri")
    }

    override suspend fun openOutputStream(uri: VfsUri, append: Boolean): OutputStream = withContext(ioDispatcher) {
        ensureActive()
        val androidUri = Uri.parse(uri.raw)
        val mode = if (append) "wa" else "wt"
        context.contentResolver.openOutputStream(androidUri, mode)
            ?: throw VfsException.Io(uri, "Could not open OutputStream for $uri")
    }

    override suspend fun openRandomAccess(uri: VfsUri, mode: String): RandomAccessContent = withContext(ioDispatcher) {
        ensureActive()
        val androidUri = Uri.parse(uri.raw)
        val pfdMode = if (mode.contains("w")) "rw" else "r"
        val pfd = context.contentResolver.openFileDescriptor(androidUri, pfdMode)
            ?: throw VfsException.Io(uri, "Failed to open FileDescriptor for $uri")
        ParcelFileDescriptorRandomAccessContent(pfd, ioDispatcher)
    }

    override suspend fun createDirectory(uri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val parentUri = uri.parent ?: return@withContext false
        val parentDoc = resolveDocument(parentUri)
        val newDir = parentDoc.createDirectory(uri.name)
        newDir != null
    }

    override suspend fun delete(uri: VfsUri, recursive: Boolean): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val doc = resolveDocument(uri)
        doc.delete()
    }

    override suspend fun rename(sourceUri: VfsUri, targetUri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val doc = resolveDocument(sourceUri)
        doc.renameTo(targetUri.name)
    }

    override suspend fun copyTo(
        sourceUri: VfsUri,
        targetUri: VfsUri,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)?
    ): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val metadata = getMetadata(sourceUri)
        val totalBytes = metadata.sizeBytes
        var bytesCopied = 0L

        openInputStream(sourceUri).use { input ->
            openOutputStream(targetUri, append = false).use { output ->
                val buffer = ByteArray(64 * 1024)
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

    private fun nodeFromDocument(uri: VfsUri, doc: DocumentFile): VfsNode {
        val isDir = doc.isDirectory
        val name = doc.name ?: uri.name
        val permissions = buildString {
            append(if (doc.canRead()) "r" else "-")
            append(if (doc.canWrite()) "w" else "-")
            append("-")
        }

        return VfsNode(
            uri = uri,
            name = name,
            isDirectory = isDir,
            sizeBytes = if (isDir) 0L else doc.length(),
            lastModifiedMillis = doc.lastModified(),
            mimeType = if (isDir) null else doc.type,
            permissions = permissions,
            isHidden = name.startsWith(".")
        )
    }
}

/**
 * Seekable RandomAccessContent backed by [ParcelFileDescriptor] and Linux [Os.lseek].
 */
class ParcelFileDescriptorRandomAccessContent(
    private val pfd: ParcelFileDescriptor,
    private val dispatcher: CoroutineDispatcher
) : RandomAccessContent {

    override val length: Long
        get() = pfd.statSize

    private var currentPosition: Long = 0L

    override val position: Long
        get() = currentPosition

    override suspend fun seek(position: Long) = withContext(dispatcher) {
        currentPosition = Os.lseek(pfd.fileDescriptor, position, OsConstants.SEEK_SET)
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = withContext(dispatcher) {
        val bytesRead = Os.read(pfd.fileDescriptor, buffer, offset, length)
        if (bytesRead > 0) currentPosition += bytesRead
        bytesRead
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = withContext(dispatcher) {
        val written = Os.write(pfd.fileDescriptor, buffer, offset, length)
        currentPosition += written
    }

    override suspend fun setLength(newLength: Long) = withContext(dispatcher) {
        Os.ftruncate(pfd.fileDescriptor, newLength)
    }

    override fun close() {
        pfd.close()
    }
}
