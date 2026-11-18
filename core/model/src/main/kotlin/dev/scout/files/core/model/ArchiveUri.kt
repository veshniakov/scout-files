package dev.scout.files.core.model

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Utility for building and parsing archive virtual URIs.
 * Canonical format: archive://<url_encoded_source_vfs_uri>!/<internal_path>
 */
object ArchiveUri {
    const val SCHEME = "archive"
    private const val PREFIX = "archive://"
    private const val DELIMITER = "!"

    /**
     * Builds an archive VfsUri from a source archive file URI and an internal path.
     */
    fun build(sourceArchiveUri: VfsUri, internalPath: String = "/"): VfsUri {
        val encodedSource = URLEncoder.encode(sourceArchiveUri.raw, "UTF-8")
        val normalizedPath = when {
            internalPath.isEmpty() || internalPath == "/" -> "/"
            internalPath.startsWith("/") -> internalPath
            else -> "/$internalPath"
        }
        return VfsUri("$PREFIX$encodedSource$DELIMITER$normalizedPath")
    }

    /**
     * Parses an archive VfsUri into the underlying source archive VfsUri and the internal path.
     * Returns null if the URI is not a valid archive URI.
     */
    fun parse(uri: VfsUri): Pair<VfsUri, String>? {
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        val raw = uri.raw
        if (!raw.startsWith(PREFIX, ignoreCase = true)) return null
        val remainder = raw.substring(PREFIX.length)
        val bangIndex = remainder.indexOf(DELIMITER)
        if (bangIndex == -1) return null

        val encodedSource = remainder.substring(0, bangIndex)
        val decodedSource = runCatching { URLDecoder.decode(encodedSource, "UTF-8") }.getOrNull() ?: return null
        val internalPath = remainder.substring(bangIndex + 1).ifEmpty { "/" }
        return Pair(VfsUri(decodedSource), internalPath)
    }

    /**
     * Extracts the source archive VfsUri if the given URI is an archive URI.
     */
    fun extractSourceUri(uri: VfsUri): VfsUri? = parse(uri)?.first

    /**
     * Extracts the internal path within the archive.
     */
    fun extractInternalPath(uri: VfsUri): String? = parse(uri)?.second

    /**
     * Checks if given URI has the 'archive' scheme.
     */
    fun isArchiveUri(uri: VfsUri): Boolean = uri.scheme.equals(SCHEME, ignoreCase = true)

    /**
     * Determines whether a file name or URI points to a recognized archive format.
     */
    fun isSupportedArchiveFile(nameOrUri: String): Boolean {
        val lower = nameOrUri.lowercase()
        return lower.endsWith(".zip") ||
            lower.endsWith(".rar") ||
            lower.endsWith(".7z") ||
            lower.endsWith(".tar") ||
            lower.endsWith(".tar.gz") ||
            lower.endsWith(".tgz") ||
            lower.endsWith(".tar.bz2") ||
            lower.endsWith(".tbz2") ||
            lower.endsWith(".tar.xz") ||
            lower.endsWith(".txz") ||
            lower.endsWith(".gz") ||
            lower.endsWith(".bz2") ||
            lower.endsWith(".xz")
    }
}
