package dev.scout.files.core.vfs

import dev.scout.files.core.model.VfsNode
import dev.scout.files.core.model.VfsUri
import java.io.InputStream
import java.io.OutputStream

/**
 * Common contract for Virtual File System providers (local, SMB, SFTP, WebDAV, Google Drive, etc.).
 */
interface FileSystemProvider {
    /**
     * Primary URI scheme handled by this provider (e.g. "file", "smb", "sftp").
     */
    val scheme: String

    /**
     * Lists child nodes of a directory.
     */
    suspend fun listDirectory(uri: VfsUri): List<VfsNode>

    /**
     * Retrieves metadata for a node at given URI.
     */
    suspend fun getMetadata(uri: VfsUri): VfsNode

    /**
     * Checks if a resource exists at given URI.
     */
    suspend fun exists(uri: VfsUri): Boolean

    /**
     * Opens an input stream for reading resource content.
     */
    suspend fun openInputStream(uri: VfsUri): InputStream

    /**
     * Opens an output stream for writing resource content.
     */
    suspend fun openOutputStream(uri: VfsUri, append: Boolean = false): OutputStream

    /**
     * Opens a seekable random access content interface.
     */
    suspend fun openRandomAccess(uri: VfsUri, mode: String = "r"): RandomAccessContent

    /**
     * Creates a directory at given URI.
     */
    suspend fun createDirectory(uri: VfsUri): Boolean

    /**
     * Deletes a file or directory at given URI.
     */
    suspend fun delete(uri: VfsUri, recursive: Boolean = false): Boolean

    /**
     * Renames or moves a resource within the same provider.
     */
    suspend fun rename(sourceUri: VfsUri, targetUri: VfsUri): Boolean

    /**
     * Copies a resource to another location within the same provider.
     */
    suspend fun copyTo(
        sourceUri: VfsUri,
        targetUri: VfsUri,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)? = null
    ): Boolean
}
