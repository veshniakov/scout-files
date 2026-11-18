package dev.scout.files.core.model

/**
 * Typed hierarchy of VFS errors.
 */
sealed class VfsException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    class NotFound(
        val uri: VfsUri,
        message: String = "Resource not found: $uri",
        cause: Throwable? = null
    ) : VfsException(message, cause)

    class PermissionDenied(
        val uri: VfsUri,
        message: String = "Permission denied: $uri",
        cause: Throwable? = null
    ) : VfsException(message, cause)

    class AuthenticationRequired(
        val uri: VfsUri,
        message: String = "Authentication required for $uri",
        cause: Throwable? = null
    ) : VfsException(message, cause)

    class NetworkTimeout(
        val uri: VfsUri,
        message: String = "Network timeout accessing $uri",
        cause: Throwable? = null
    ) : VfsException(message, cause)

    class ReadOnlyFileSystem(
        val uri: VfsUri,
        message: String = "File system is read-only: $uri",
        cause: Throwable? = null
    ) : VfsException(message, cause)

    class AlreadyExists(
        val uri: VfsUri,
        message: String = "Resource already exists: $uri",
        cause: Throwable? = null
    ) : VfsException(message, cause)

    class UnsupportedScheme(
        val uri: VfsUri,
        message: String = "Unsupported URI scheme: ${uri.scheme}",
        cause: Throwable? = null
    ) : VfsException(message, cause)

    class Io(
        val uri: VfsUri,
        message: String = "I/O error accessing $uri",
        cause: Throwable? = null
    ) : VfsException(message, cause)
}
