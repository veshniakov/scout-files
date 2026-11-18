package dev.scout.files.provider.local

import android.webkit.MimeTypeMap
import dev.scout.files.core.model.VfsException
import dev.scout.files.core.model.VfsNode
import dev.scout.files.core.model.VfsUri
import dev.scout.files.core.vfs.FileSystemProvider
import dev.scout.files.core.vfs.RandomAccessContent
import dev.scout.files.core.vfs.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.URLConnection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-performance POSIX / Linux local file system provider.
 * Implements full metadata parsing (POSIX permissions, dotfiles, MIME types via MimeTypeMap),
 * random-access seekable streaming, and recursive tree management.
 */
@Singleton
class LocalFileSystemProvider @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : FileSystemProvider {

    constructor() : this(Dispatchers.IO)

    override val scheme: String = "file"

    private fun resolveFile(uri: VfsUri): File = File(uri.path)

    override suspend fun listDirectory(uri: VfsUri): List<VfsNode> = withContext(ioDispatcher) {
        ensureActive()
        val dir = resolveFile(uri)
        if (!dir.exists()) throw VfsException.NotFound(uri)
        if (!dir.isDirectory) throw VfsException.Io(uri, "Path is not a directory: ${uri.path}")

        val files = dir.listFiles() ?: emptyArray()
        files.map { file ->
            val childUri = uri.resolve(file.name)
            nodeFromFile(childUri, file)
        }
    }

    override suspend fun getMetadata(uri: VfsUri): VfsNode = withContext(ioDispatcher) {
        ensureActive()
        val file = resolveFile(uri)
        if (!file.exists()) throw VfsException.NotFound(uri)
        nodeFromFile(uri, file)
    }

    override suspend fun exists(uri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        resolveFile(uri).exists()
    }

    override suspend fun openInputStream(uri: VfsUri): InputStream = withContext(ioDispatcher) {
        ensureActive()
        val file = resolveFile(uri)
        if (!file.exists()) throw VfsException.NotFound(uri)
        FileInputStream(file)
    }

    override suspend fun openOutputStream(uri: VfsUri, append: Boolean): OutputStream = withContext(ioDispatcher) {
        ensureActive()
        val file = resolveFile(uri)
        file.parentFile?.mkdirs()
        FileOutputStream(file, append)
    }

    override suspend fun openRandomAccess(uri: VfsUri, mode: String): RandomAccessContent = withContext(ioDispatcher) {
        ensureActive()
        val file = resolveFile(uri)
        if (!file.exists() && mode.contains("r") && !mode.contains("w")) {
            throw VfsException.NotFound(uri)
        }
        file.parentFile?.mkdirs()
        FileRandomAccessContent(RandomAccessFile(file, mode), ioDispatcher)
    }

    override suspend fun createDirectory(uri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val dir = resolveFile(uri)
        if (dir.exists()) {
            dir.isDirectory
        } else {
            dir.mkdirs()
        }
    }

    override suspend fun delete(uri: VfsUri, recursive: Boolean): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val file = resolveFile(uri)
        if (!file.exists()) return@withContext true
        if (recursive) file.deleteRecursively() else file.delete()
    }

    override suspend fun rename(sourceUri: VfsUri, targetUri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val source = resolveFile(sourceUri)
        val target = resolveFile(targetUri)
        if (!source.exists()) throw VfsException.NotFound(sourceUri)
        target.parentFile?.mkdirs()

        if (source.renameTo(target)) {
            true
        } else {
            // Cross-filesystem / mount-point move fallback
            val copied = copyTo(sourceUri, targetUri)
            if (copied) {
                delete(sourceUri, recursive = true)
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
        val source = resolveFile(sourceUri)
        val target = resolveFile(targetUri)
        if (!source.exists()) throw VfsException.NotFound(sourceUri)

        target.parentFile?.mkdirs()
        if (source.isDirectory) {
            source.copyRecursively(target, overwrite = true)
        } else {
            val totalBytes = source.length()
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
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

    private fun nodeFromFile(uri: VfsUri, file: File): VfsNode {
        val isDir = file.isDirectory
        val extension = if (!isDir && file.name.contains('.')) file.name.substringAfterLast('.', "") else ""
        val mimeType = if (isDir) {
            null
        } else {
            val fromMimeMap = if (extension.isNotEmpty()) {
                runCatching {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
                }.getOrNull()
            } else null
            fromMimeMap ?: URLConnection.guessContentTypeFromName(file.name) ?: guessFallbackMimeType(extension)
        }

        val permissions = runCatching {
            val posix = java.nio.file.Files.getPosixFilePermissions(file.toPath())
            java.nio.file.attribute.PosixFilePermissions.toString(posix)
        }.getOrElse {
            buildString {
                append(if (file.canRead()) "r" else "-")
                append(if (file.canWrite()) "w" else "-")
                append(if (file.canExecute()) "x" else "-")
            }
        }

        val isHidden = file.name.startsWith(".") || file.isHidden

        return VfsNode(
            uri = uri,
            name = file.name,
            isDirectory = isDir,
            sizeBytes = if (isDir) 0L else file.length(),
            lastModifiedMillis = file.lastModified(),
            mimeType = mimeType,
            permissions = permissions,
            isHidden = isHidden,
            extension = extension
        )
    }

    private fun guessFallbackMimeType(extension: String): String? {
        return when (extension.lowercase()) {
            "kt", "kts" -> "text/x-kotlin"
            "md", "markdown" -> "text/markdown"
            "json" -> "application/json"
            "yaml", "yml" -> "text/yaml"
            "toml" -> "text/x-toml"
            "sh", "bash", "zsh" -> "application/x-sh"
            "py" -> "text/x-python"
            "c", "h" -> "text/x-c"
            "cpp", "hpp", "cc" -> "text/x-c++"
            "rs" -> "text/x-rust"
            "go" -> "text/x-go"
            "java" -> "text/x-java"
            "js" -> "application/javascript"
            "ts" -> "application/typescript"
            "sql" -> "application/sql"
            "xml" -> "application/xml"
            "gradle", "properties", "env", "conf", "ini", "cfg", "log" -> "text/plain"
            "csv" -> "text/csv"
            else -> null
        }
    }
}

/**
 * RandomAccessFile-backed implementation of [RandomAccessContent].
 */
class FileRandomAccessContent(
    private val raf: RandomAccessFile,
    private val dispatcher: CoroutineDispatcher
) : RandomAccessContent {

    override val length: Long
        get() = raf.length()

    override val position: Long
        get() = raf.filePointer

    override suspend fun seek(position: Long) = withContext(dispatcher) {
        raf.seek(position)
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = withContext(dispatcher) {
        raf.read(buffer, offset, length)
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = withContext(dispatcher) {
        raf.write(buffer, offset, length)
    }

    override suspend fun setLength(newLength: Long) = withContext(dispatcher) {
        raf.setLength(newLength)
    }

    override fun close() {
        raf.close()
    }
}
