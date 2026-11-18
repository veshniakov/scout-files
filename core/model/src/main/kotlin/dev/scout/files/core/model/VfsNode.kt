package dev.scout.files.core.model

/**
 * Metadata and node representation for a file or directory in VFS.
 */
data class VfsNode(
    val uri: VfsUri,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val lastModifiedMillis: Long = 0L,
    val mimeType: String? = null,
    val permissions: String? = null,
    val isHidden: Boolean = false,
    val extension: String = if (!isDirectory && name.contains('.')) name.substringAfterLast('.', "") else ""
)
