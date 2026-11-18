package dev.scout.files.core.vfs

import dev.scout.files.core.model.VfsException
import dev.scout.files.core.model.VfsNode
import dev.scout.files.core.model.VfsUri
import dev.scout.files.core.vfs.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central router for Virtual File System operations.
 * Routes URIs to corresponding [FileSystemProvider] instances based on URI scheme.
 * Supports cross-protocol streaming copy between disparate file systems.
 */
@Singleton
class VfsRouter @Inject constructor(
    providers: Set<@JvmSuppressWildcards FileSystemProvider>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    constructor(providers: Set<FileSystemProvider>) : this(providers, Dispatchers.IO)

    private val providerMap = ConcurrentHashMap<String, FileSystemProvider>().apply {
        providers.forEach { put(it.scheme.lowercase(), it) }
    }

    fun registerProvider(provider: FileSystemProvider) {
        providerMap[provider.scheme.lowercase()] = provider
    }

    fun unregisterProvider(scheme: String) {
        providerMap.remove(scheme.lowercase())
    }

    fun supportedSchemes(): Set<String> = providerMap.keys.toSet()

    fun getProvider(scheme: String): FileSystemProvider {
        val normalized = scheme.lowercase()
        return providerMap[normalized]
            ?: throw VfsException.UnsupportedScheme(
                uri = VfsUri("$normalized://"),
                message = "No FileSystemProvider registered for scheme '$normalized'"
            )
    }

    fun getProvider(uri: VfsUri): FileSystemProvider = getProvider(uri.scheme)

    suspend fun listDirectory(uri: VfsUri): List<VfsNode> = withContext(ioDispatcher) {
        ensureActive()
        getProvider(uri).listDirectory(uri)
    }

    suspend fun getMetadata(uri: VfsUri): VfsNode = withContext(ioDispatcher) {
        ensureActive()
        getProvider(uri).getMetadata(uri)
    }

    suspend fun exists(uri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        getProvider(uri).exists(uri)
    }

    suspend fun openInputStream(uri: VfsUri): InputStream = withContext(ioDispatcher) {
        ensureActive()
        getProvider(uri).openInputStream(uri)
    }

    suspend fun openOutputStream(uri: VfsUri, append: Boolean = false): OutputStream = withContext(ioDispatcher) {
        ensureActive()
        getProvider(uri).openOutputStream(uri, append)
    }

    suspend fun openRandomAccess(uri: VfsUri, mode: String = "r"): RandomAccessContent = withContext(ioDispatcher) {
        ensureActive()
        getProvider(uri).openRandomAccess(uri, mode)
    }

    suspend fun createDirectory(uri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        getProvider(uri).createDirectory(uri)
    }

    suspend fun delete(uri: VfsUri, recursive: Boolean = false): Boolean = withContext(ioDispatcher) {
        ensureActive()
        getProvider(uri).delete(uri, recursive)
    }

    suspend fun rename(sourceUri: VfsUri, targetUri: VfsUri): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val sourceProvider = getProvider(sourceUri)
        val targetProvider = getProvider(targetUri)

        if (sourceProvider.scheme.equals(targetProvider.scheme, ignoreCase = true)) {
            sourceProvider.rename(sourceUri, targetUri)
        } else {
            // Cross-provider move: copy then delete
            val copied = copy(sourceUri, targetUri)
            if (copied) {
                sourceProvider.delete(sourceUri, recursive = true)
            } else {
                false
            }
        }
    }

    /**
     * Copies a resource from sourceUri to targetUri.
     * Uses optimized intra-provider copy when both schemes match, or falls back to
     * cross-protocol streaming transfer with progress tracking.
     */
    suspend fun copy(
        sourceUri: VfsUri,
        targetUri: VfsUri,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)? = null
    ): Boolean = withContext(ioDispatcher) {
        ensureActive()
        val sourceProvider = getProvider(sourceUri)
        val targetProvider = getProvider(targetUri)

        if (sourceProvider.scheme.equals(targetProvider.scheme, ignoreCase = true)) {
            val intraResult = runCatching {
                sourceProvider.copyTo(sourceUri, targetUri, onProgress)
            }.getOrDefault(false)
            if (intraResult) return@withContext true
        }

        // Cross-protocol copy fallback
        crossProtocolCopy(sourceProvider, targetProvider, sourceUri, targetUri, onProgress)
    }

    private suspend fun crossProtocolCopy(
        sourceProvider: FileSystemProvider,
        targetProvider: FileSystemProvider,
        sourceUri: VfsUri,
        targetUri: VfsUri,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)?
    ): Boolean {
        val metadata = sourceProvider.getMetadata(sourceUri)
        return if (metadata.isDirectory) {
            targetProvider.createDirectory(targetUri)
            val children = sourceProvider.listDirectory(sourceUri)
            for (child in children) {
                val childTargetUri = targetUri.resolve(child.name)
                val success = copy(child.uri, childTargetUri, onProgress)
                if (!success) return false
            }
            true
        } else {
            val totalBytes = metadata.sizeBytes
            var bytesCopied = 0L

            sourceProvider.openInputStream(sourceUri).use { input ->
                targetProvider.openOutputStream(targetUri, append = false).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
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

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024 // 64 KB
    }
}
