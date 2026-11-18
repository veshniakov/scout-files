package dev.scout.files.provider.archive

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.scout.files.core.model.ArchiveUri
import dev.scout.files.core.model.VfsException
import dev.scout.files.core.model.VfsNode
import dev.scout.files.core.model.VfsUri
import dev.scout.files.core.vfs.ArchivePasswordStore
import dev.scout.files.core.vfs.FileSystemProvider
import dev.scout.files.core.vfs.RandomAccessContent
import dev.scout.files.core.vfs.VfsRouter
import dev.scout.files.core.vfs.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.lingala.zip4j.exception.ZipException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class ArchiveFileSystemProvider @Inject constructor(
    private val archivePasswordStore: ArchivePasswordStore,
    private val vfsRouterProvider: Provider<VfsRouter>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationContext private val context: Context? = null
) : FileSystemProvider {

    constructor(
        archivePasswordStore: ArchivePasswordStore,
        vfsRouterProvider: Provider<VfsRouter>,
        ioDispatcher: CoroutineDispatcher
    ) : this(archivePasswordStore, vfsRouterProvider, ioDispatcher, null)

    override val scheme: String = ArchiveUri.SCHEME

    private val treeCache = ConcurrentHashMap<String, VirtualArchiveTree>()

    override suspend fun listDirectory(uri: VfsUri): List<VfsNode> = withContext(ioDispatcher) {
        val (sourceUri, internalPath) = parseUriOrThrow(uri)
        val tree = getOrLoadTree(sourceUri)
        val children = tree.listChildren(internalPath)
        children.map { entry ->
            val childUri = ArchiveUri.build(sourceUri, entry.path)
            VfsNode(
                uri = childUri,
                name = entry.name,
                isDirectory = entry.isDirectory,
                sizeBytes = if (entry.isDirectory) 0L else entry.size,
                lastModifiedMillis = entry.lastModified
            )
        }
    }

    override suspend fun getMetadata(uri: VfsUri): VfsNode = withContext(ioDispatcher) {
        val (sourceUri, internalPath) = parseUriOrThrow(uri)
        val tree = getOrLoadTree(sourceUri)
        val entry = tree.getEntry(internalPath) ?: throw VfsException.NotFound(uri)
        val name = if (entry.path == "/" || entry.name.isEmpty()) sourceUri.name else entry.name
        VfsNode(
            uri = uri,
            name = name,
            isDirectory = entry.isDirectory,
            sizeBytes = if (entry.isDirectory) 0L else entry.size,
            lastModifiedMillis = entry.lastModified
        )
    }

    override suspend fun exists(uri: VfsUri): Boolean = withContext(ioDispatcher) {
        val (sourceUri, internalPath) = ArchiveUri.parse(uri) ?: return@withContext false
        val tree = runCatching { getOrLoadTree(sourceUri) }.getOrNull() ?: return@withContext false
        tree.getEntry(internalPath) != null
    }

    override suspend fun openInputStream(uri: VfsUri): InputStream = withContext(ioDispatcher) {
        val (sourceUri, internalPath) = parseUriOrThrow(uri)
        val file = resolveLocalArchiveFile(sourceUri)
        val cleanPath = internalPath.trim().removePrefix("/").removeSuffix("/")

        val lowerName = sourceUri.name.lowercase()
        when {
            lowerName.endsWith(".zip") -> openZipEntry(file, sourceUri, cleanPath, uri)
            lowerName.endsWith(".rar") -> openRarEntry(file, sourceUri, cleanPath, uri)
            lowerName.endsWith(".7z") -> open7zEntry(file, sourceUri, cleanPath, uri)
            lowerName.endsWith(".tar.gz") || lowerName.endsWith(".tgz") ->
                openTarEntry(file, cleanPath, uri) { GzipCompressorInputStream(it.buffered()) }
            lowerName.endsWith(".tar.bz2") || lowerName.endsWith(".tbz2") ->
                openTarEntry(file, cleanPath, uri) { BZip2CompressorInputStream(it.buffered()) }
            lowerName.endsWith(".tar.xz") || lowerName.endsWith(".txz") ->
                openTarEntry(file, cleanPath, uri) { XZCompressorInputStream(it.buffered()) }
            lowerName.endsWith(".tar") ->
                openTarEntry(file, cleanPath, uri) { it }
            else -> openZipEntry(file, sourceUri, cleanPath, uri)
        }
    }

    override suspend fun openRandomAccess(uri: VfsUri, mode: String): RandomAccessContent = withContext(ioDispatcher) {
        val stream = openInputStream(uri)
        val buffer = ByteArrayOutputStream()
        stream.use { input ->
            input.copyTo(buffer)
        }
        val bytes = buffer.toByteArray()
        object : RandomAccessContent {
            private var currentPosition: Long = 0L

            override val length: Long get() = bytes.size.toLong()
            override val position: Long get() = currentPosition

            override suspend fun seek(position: Long) {
                currentPosition = position.coerceIn(0L, length)
            }

            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (currentPosition >= bytes.size) return -1
                val available = (bytes.size - currentPosition).toInt()
                val toRead = minOf(length, available)
                System.arraycopy(bytes, currentPosition.toInt(), buffer, offset, toRead)
                currentPosition += toRead
                return toRead
            }

            override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
                throw VfsException.ReadOnlyFileSystem(uri)
            }

            override suspend fun setLength(newLength: Long) {
                throw VfsException.ReadOnlyFileSystem(uri)
            }

            override fun close() {}
        }
    }

    override suspend fun openOutputStream(uri: VfsUri, append: Boolean): OutputStream {
        throw VfsException.ReadOnlyFileSystem(uri, "Direct archive writing is not supported")
    }

    override suspend fun createDirectory(uri: VfsUri): Boolean {
        throw VfsException.ReadOnlyFileSystem(uri, "Direct archive modification is not supported")
    }

    override suspend fun delete(uri: VfsUri, recursive: Boolean): Boolean {
        throw VfsException.ReadOnlyFileSystem(uri, "Direct archive deletion is not supported")
    }

    override suspend fun rename(sourceUri: VfsUri, targetUri: VfsUri): Boolean {
        throw VfsException.ReadOnlyFileSystem(sourceUri, "Direct archive rename is not supported")
    }

    override suspend fun copyTo(
        sourceUri: VfsUri,
        targetUri: VfsUri,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)?
    ): Boolean = false

    private fun parseUriOrThrow(uri: VfsUri): Pair<VfsUri, String> {
        return ArchiveUri.parse(uri) ?: throw VfsException.NotFound(uri, "Invalid archive URI: $uri")
    }

    private suspend fun getOrLoadTree(sourceUri: VfsUri): VirtualArchiveTree {
        val file = resolveLocalArchiveFile(sourceUri)
        val cacheKey = "${sourceUri.raw}:${file.lastModified()}:${file.length()}"
        val cached = treeCache[cacheKey]
        if (cached != null) {
            if (cached.entries.any { it.isEncrypted } && !archivePasswordStore.hasPassword(sourceUri)) {
                treeCache.remove(cacheKey)
                throw VfsException.AuthenticationRequired(sourceUri, "Archive requires password")
            }
            return cached
        }

        val tree = loadVirtualTree(file, sourceUri)
        treeCache[cacheKey] = tree
        return tree
    }

    private suspend fun resolveLocalArchiveFile(sourceUri: VfsUri): File {
        if (sourceUri.scheme == "file") {
            val file = File(sourceUri.path)
            if (!file.exists()) throw VfsException.NotFound(sourceUri)
            return file
        }
        val cacheDir = context?.cacheDir ?: File(System.getProperty("java.io.tmpdir"), "scout_archive_cache")
        cacheDir.mkdirs()
        val cacheFile = File(cacheDir, "cached_${sourceUri.raw.hashCode()}_${sourceUri.name}")
        if (!cacheFile.exists() || cacheFile.length() == 0L) {
            val router = vfsRouterProvider.get()
            router.openInputStream(sourceUri).use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return cacheFile
    }

    private fun loadVirtualTree(file: File, sourceUri: VfsUri): VirtualArchiveTree {
        val lowerName = sourceUri.name.lowercase()
        val entries = when {
            lowerName.endsWith(".zip") -> loadZipEntries(file, sourceUri)
            lowerName.endsWith(".rar") -> loadRarEntries(file, sourceUri)
            lowerName.endsWith(".7z") -> load7zEntries(file, sourceUri)
            lowerName.endsWith(".tar.gz") || lowerName.endsWith(".tgz") ->
                loadTarEntries(file) { GzipCompressorInputStream(it.buffered()) }
            lowerName.endsWith(".tar.bz2") || lowerName.endsWith(".tbz2") ->
                loadTarEntries(file) { BZip2CompressorInputStream(it.buffered()) }
            lowerName.endsWith(".tar.xz") || lowerName.endsWith(".txz") ->
                loadTarEntries(file) { XZCompressorInputStream(it.buffered()) }
            lowerName.endsWith(".tar") ->
                loadTarEntries(file) { it }
            else -> loadZipEntries(file, sourceUri)
        }
        return VirtualArchiveTree(entries)
    }

    private fun loadZipEntries(file: File, sourceUri: VfsUri): List<ArchiveEntryInfo> {
        val zipFile = net.lingala.zip4j.ZipFile(file)
        if (zipFile.isEncrypted) {
            val password = archivePasswordStore.getPassword(sourceUri)
            if (password == null) {
                throw VfsException.AuthenticationRequired(sourceUri, "ZIP archive is password protected")
            }
            zipFile.setPassword(password)
        }
        return try {
            zipFile.fileHeaders.mapNotNull { header ->
                val clean = header.fileName.replace('\\', '/').removePrefix("./").removePrefix("/")
                if (clean.isEmpty()) null
                else {
                    ArchiveEntryInfo(
                        path = "/$clean",
                        name = clean.trimEnd('/').substringAfterLast('/', clean.trimEnd('/')),
                        isDirectory = header.isDirectory,
                        size = header.uncompressedSize,
                        lastModified = header.lastModifiedTimeEpoch,
                        isEncrypted = header.isEncrypted
                    )
                }
            }
        } catch (e: ZipException) {
            if (e.type == ZipException.Type.WRONG_PASSWORD || e.message?.contains("password", ignoreCase = true) == true) {
                throw VfsException.AuthenticationRequired(sourceUri, "Incorrect password for ZIP archive", e)
            }
            throw VfsException.Io(sourceUri, "Error reading ZIP headers", e)
        }
    }

    private fun loadRarEntries(file: File, sourceUri: VfsUri): List<ArchiveEntryInfo> {
        val password = archivePasswordStore.getPasswordString(sourceUri)
        val archive = try {
            if (password != null) {
                com.github.junrar.Archive(file, password)
            } else {
                com.github.junrar.Archive(file)
            }
        } catch (e: Exception) {
            if (e.message?.contains("password", ignoreCase = true) == true) {
                throw VfsException.AuthenticationRequired(sourceUri, "Password error reading RAR archive", e)
            }
            throw VfsException.Io(sourceUri, "Error opening RAR archive", e)
        }

        if ((archive.isEncrypted || archive.isPasswordProtected) && password == null) {
            archive.close()
            throw VfsException.AuthenticationRequired(sourceUri, "RAR archive is password protected")
        }

        return try {
            archive.fileHeaders.mapNotNull { header ->
                val clean = header.fileName.replace('\\', '/').removePrefix("./").removePrefix("/")
                if (clean.isEmpty()) null
                else {
                    ArchiveEntryInfo(
                        path = "/$clean",
                        name = clean.trimEnd('/').substringAfterLast('/', clean.trimEnd('/')),
                        isDirectory = header.isDirectory,
                        size = header.unpSize,
                        lastModified = header.mTime?.time ?: 0L,
                        isEncrypted = header.isEncrypted
                    )
                }
            }
        } finally {
            archive.close()
        }
    }

    private fun load7zEntries(file: File, sourceUri: VfsUri): List<ArchiveEntryInfo> {
        val password = archivePasswordStore.getPassword(sourceUri)
        val sz = try {
            SevenZFile(file, password)
        } catch (e: PasswordRequiredException) {
            throw VfsException.AuthenticationRequired(sourceUri, "7z archive requires password", e)
        } catch (e: Exception) {
            if (e.message?.contains("password", ignoreCase = true) == true) {
                throw VfsException.AuthenticationRequired(sourceUri, "Password error for 7z archive", e)
            }
            throw VfsException.Io(sourceUri, "Error opening 7z archive", e)
        }

        sz.use { sevenZFile ->
            return sevenZFile.entries.mapNotNull { entry ->
                val clean = entry.name.replace('\\', '/').removePrefix("./").removePrefix("/")
                if (clean.isEmpty()) null
                else {
                    val isEnc = entry.contentMethods?.any { it.method == SevenZMethod.AES256SHA256 } == true
                    ArchiveEntryInfo(
                        path = "/$clean",
                        name = clean.trimEnd('/').substringAfterLast('/', clean.trimEnd('/')),
                        isDirectory = entry.isDirectory,
                        size = entry.size,
                        lastModified = entry.lastModifiedDate?.time ?: 0L,
                        isEncrypted = isEnc
                    )
                }
            }
        }
    }

    private fun loadTarEntries(file: File, decompressor: (InputStream) -> InputStream): List<ArchiveEntryInfo> {
        val entries = mutableListOf<ArchiveEntryInfo>()
        TarArchiveInputStream(decompressor(file.inputStream().buffered())).use { tarIn ->
            var entry = tarIn.nextTarEntry
            while (entry != null) {
                val clean = entry.name.replace('\\', '/').removePrefix("./").removePrefix("/")
                if (clean.isNotEmpty()) {
                    entries.add(
                        ArchiveEntryInfo(
                            path = "/$clean",
                            name = clean.trimEnd('/').substringAfterLast('/', clean.trimEnd('/')),
                            isDirectory = entry.isDirectory,
                            size = entry.size,
                            lastModified = entry.modTime?.time ?: 0L,
                            isEncrypted = false
                        )
                    )
                }
                entry = tarIn.nextTarEntry
            }
        }
        return entries
    }

    private fun openZipEntry(file: File, sourceUri: VfsUri, entryPath: String, requestedUri: VfsUri): InputStream {
        val zipFile = net.lingala.zip4j.ZipFile(file)
        val cleanRequested = entryPath.removePrefix("./").removePrefix("/").trimEnd('/')
        val header = zipFile.getFileHeader(cleanRequested)
            ?: zipFile.fileHeaders.firstOrNull {
                it.fileName.replace('\\', '/').removePrefix("./").removePrefix("/").trimEnd('/') == cleanRequested
            }
            ?: throw VfsException.NotFound(requestedUri)

        val isEncrypted = header.isEncrypted || zipFile.isEncrypted
        if (isEncrypted) {
            val password = archivePasswordStore.getPassword(sourceUri)
                ?: throw VfsException.AuthenticationRequired(requestedUri, "ZIP entry requires password")
            zipFile.setPassword(password)
        }

        val rawStream = try {
            zipFile.getInputStream(header)
        } catch (e: ZipException) {
            if (e.type == ZipException.Type.WRONG_PASSWORD || e.message?.contains("password", ignoreCase = true) == true) {
                throw VfsException.AuthenticationRequired(requestedUri, "Incorrect password for ZIP entry", e)
            }
            throw VfsException.Io(requestedUri, "Failed reading ZIP entry", e)
        }

        return object : InputStream() {
            override fun read(): Int = try {
                rawStream.read()
            } catch (e: ZipException) {
                if (e.type == ZipException.Type.WRONG_PASSWORD || e.message?.contains("password", ignoreCase = true) == true) {
                    throw VfsException.AuthenticationRequired(requestedUri, "Incorrect password for ZIP entry", e)
                }
                throw e
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int = try {
                rawStream.read(b, off, len)
            } catch (e: ZipException) {
                if (e.type == ZipException.Type.WRONG_PASSWORD || e.message?.contains("password", ignoreCase = true) == true) {
                    throw VfsException.AuthenticationRequired(requestedUri, "Incorrect password for ZIP entry", e)
                }
                throw e
            }
            override fun read(b: ByteArray): Int = read(b, 0, b.size)
            override fun skip(n: Long): Long = rawStream.skip(n)
            override fun available(): Int = rawStream.available()
            override fun close() = rawStream.close()
        }
    }

    private fun openRarEntry(file: File, sourceUri: VfsUri, entryPath: String, requestedUri: VfsUri): InputStream {
        val password = archivePasswordStore.getPasswordString(sourceUri)
        val archive = try {
            if (password != null) {
                com.github.junrar.Archive(file, password)
            } else {
                com.github.junrar.Archive(file)
            }
        } catch (e: Exception) {
            if (e.message?.contains("password", ignoreCase = true) == true) {
                throw VfsException.AuthenticationRequired(requestedUri, "Password error", e)
            }
            throw VfsException.Io(requestedUri, "Error opening RAR", e)
        }

        if ((archive.isEncrypted || archive.isPasswordProtected) && password == null) {
            archive.close()
            throw VfsException.AuthenticationRequired(requestedUri, "Entry requires password")
        }

        val cleanRequested = entryPath.removePrefix("./").removePrefix("/").trimEnd('/')
        val header = archive.fileHeaders.firstOrNull {
            it.fileName.replace('\\', '/').removePrefix("./").removePrefix("/").trimEnd('/') == cleanRequested
        }
        if (header == null) {
            archive.close()
            throw VfsException.NotFound(requestedUri)
        }

        if (header.isEncrypted && password == null) {
            archive.close()
            throw VfsException.AuthenticationRequired(requestedUri, "Entry requires password")
        }

        val rawStream = try {
            archive.getInputStream(header)
        } catch (e: Exception) {
            archive.close()
            if (e.message?.contains("password", ignoreCase = true) == true) {
                throw VfsException.AuthenticationRequired(requestedUri, "Wrong RAR password", e)
            }
            throw VfsException.Io(requestedUri, "Error reading RAR entry", e)
        }

        return object : InputStream() {
            override fun read(): Int = rawStream.read()
            override fun read(b: ByteArray): Int = rawStream.read(b)
            override fun read(b: ByteArray, off: Int, len: Int): Int = rawStream.read(b, off, len)
            override fun skip(n: Long): Long = rawStream.skip(n)
            override fun available(): Int = rawStream.available()
            override fun close() {
                try {
                    rawStream.close()
                } finally {
                    archive.close()
                }
            }
        }
    }

    private fun open7zEntry(file: File, sourceUri: VfsUri, entryPath: String, requestedUri: VfsUri): InputStream {
        val password = archivePasswordStore.getPassword(sourceUri)
        val sz = try {
            SevenZFile(file, password)
        } catch (e: PasswordRequiredException) {
            throw VfsException.AuthenticationRequired(requestedUri, "7z requires password", e)
        } catch (e: Exception) {
            if (e.message?.contains("password", ignoreCase = true) == true) {
                throw VfsException.AuthenticationRequired(requestedUri, "Password error for 7z", e)
            }
            throw VfsException.Io(requestedUri, "Error opening 7z", e)
        }

        val cleanRequested = entryPath.removePrefix("./").removePrefix("/").trimEnd('/')
        var entry: SevenZArchiveEntry? = sz.nextEntry
        while (entry != null) {
            val cleanName = entry.name.replace('\\', '/').removePrefix("./").removePrefix("/").trimEnd('/')
            if (cleanName == cleanRequested) {
                val isEnc = entry.contentMethods?.any { it.method == SevenZMethod.AES256SHA256 } == true
                if (isEnc && password == null) {
                    sz.close()
                    throw VfsException.AuthenticationRequired(requestedUri, "7z entry requires password")
                }
                val rawStream = try {
                    sz.getInputStream(entry)
                } catch (e: PasswordRequiredException) {
                    sz.close()
                    throw VfsException.AuthenticationRequired(requestedUri, "7z entry requires password", e)
                } catch (e: Exception) {
                    sz.close()
                    if (e.message?.contains("password", ignoreCase = true) == true) {
                        throw VfsException.AuthenticationRequired(requestedUri, "Password error", e)
                    }
                    throw VfsException.Io(requestedUri, "Failed reading 7z entry", e)
                }
                return object : InputStream() {
                    override fun read(): Int = try {
                        rawStream.read()
                    } catch (e: Exception) {
                        if (e is PasswordRequiredException || e.message?.contains("password", ignoreCase = true) == true) {
                            throw VfsException.AuthenticationRequired(requestedUri, "Password error for 7z", e)
                        }
                        throw e
                    }
                    override fun read(b: ByteArray, off: Int, len: Int): Int = try {
                        rawStream.read(b, off, len)
                    } catch (e: Exception) {
                        if (e is PasswordRequiredException || e.message?.contains("password", ignoreCase = true) == true) {
                            throw VfsException.AuthenticationRequired(requestedUri, "Password error for 7z", e)
                        }
                        throw e
                    }
                    override fun read(b: ByteArray): Int = read(b, 0, b.size)
                    override fun skip(n: Long): Long = rawStream.skip(n)
                    override fun available(): Int = rawStream.available()
                    override fun close() {
                        try {
                            rawStream.close()
                        } finally {
                            sz.close()
                        }
                    }
                }
            }
            entry = sz.nextEntry
        }
        sz.close()
        throw VfsException.NotFound(requestedUri)
    }

    private fun openTarEntry(
        file: File,
        entryPath: String,
        requestedUri: VfsUri,
        decompressor: (InputStream) -> InputStream
    ): InputStream {
        val cleanRequested = entryPath.removePrefix("./").removePrefix("/").trimEnd('/')
        val fis = file.inputStream().buffered()
        val decompressed = decompressor(fis)
        val tarIn = TarArchiveInputStream(decompressed)
        var entry = tarIn.nextTarEntry
        while (entry != null) {
            val cleanName = entry.name.replace('\\', '/').removePrefix("./").removePrefix("/").trimEnd('/')
            if (cleanName == cleanRequested) {
                return object : InputStream() {
                    override fun read(): Int = tarIn.read()
                    override fun read(b: ByteArray, off: Int, len: Int): Int = tarIn.read(b, off, len)
                    override fun read(b: ByteArray): Int = tarIn.read(b)
                    override fun skip(n: Long): Long = tarIn.skip(n)
                    override fun available(): Int = tarIn.available()
                    override fun close() {
                        try {
                            tarIn.close()
                        } finally {
                            decompressed.close()
                            fis.close()
                        }
                    }
                }
            }
            entry = tarIn.nextTarEntry
        }
        tarIn.close()
        decompressed.close()
        fis.close()
        throw VfsException.NotFound(requestedUri)
    }
}

data class ArchiveEntryInfo(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val isEncrypted: Boolean
)

class VirtualArchiveTree(val entries: List<ArchiveEntryInfo>) {
    private val dirChildrenMap = mutableMapOf<String, MutableMap<String, ArchiveEntryInfo>>()
    private val entriesByPath = mutableMapOf<String, ArchiveEntryInfo>()

    init {
        dirChildrenMap["/"] = mutableMapOf()

        for (entry in entries) {
            val normalizedPath = normalizePath(entry.path)
            entriesByPath[normalizedPath] = entry

            val segments = normalizedPath.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
            var currentPath = ""
            for (i in 0 until segments.size - 1) {
                val parentPath = if (currentPath.isEmpty()) "/" else currentPath
                val seg = segments[i]
                currentPath = "$currentPath/$seg"

                val parentMap = dirChildrenMap.getOrPut(parentPath) { mutableMapOf() }
                if (!parentMap.containsKey(seg)) {
                    val dirInfo = ArchiveEntryInfo(
                        path = currentPath,
                        name = seg,
                        isDirectory = true,
                        size = 0L,
                        lastModified = 0L,
                        isEncrypted = false
                    )
                    parentMap[seg] = dirInfo
                    entriesByPath.putIfAbsent(currentPath, dirInfo)
                }
            }

            val parentPath = if (segments.size <= 1) "/" else "/" + segments.dropLast(1).joinToString("/")
            val entryName = segments.lastOrNull() ?: ""
            if (entryName.isNotEmpty()) {
                val parentMap = dirChildrenMap.getOrPut(parentPath) { mutableMapOf() }
                parentMap[entryName] = entry
            }
        }
    }

    fun listChildren(dirPath: String): List<ArchiveEntryInfo> {
        val norm = normalizePath(dirPath)
        return dirChildrenMap[norm]?.values?.toList() ?: emptyList()
    }

    fun getEntry(path: String): ArchiveEntryInfo? {
        val norm = normalizePath(path)
        if (norm == "/") {
            return ArchiveEntryInfo("/", "", isDirectory = true, size = 0L, lastModified = 0L, isEncrypted = false)
        }
        return entriesByPath[norm]
    }

    private fun normalizePath(p: String): String {
        val trimmed = p.trim().trimEnd('/')
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }
}
