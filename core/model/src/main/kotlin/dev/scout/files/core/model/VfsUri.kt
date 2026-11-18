package dev.scout.files.core.model

import java.net.URI

/**
 * Uniform Resource Identifier for Virtual File System (VFS).
 * Supported scheme examples: file, content, usb, smb, sftp, ftp, webdav, gdrive, archive.
 */
data class VfsUri(val raw: String) : Comparable<VfsUri> {

    val scheme: String
    val host: String?
    val port: Int?
    val authority: String?
    val path: String
    val query: String?

    init {
        val parsedUri = runCatching { URI(raw) }.getOrNull()
        if (parsedUri != null && parsedUri.scheme != null) {
            scheme = parsedUri.scheme.lowercase()
            host = parsedUri.host
            port = if (parsedUri.port != -1) parsedUri.port else null
            authority = parsedUri.rawAuthority ?: parsedUri.authority
            path = parsedUri.path?.ifEmpty { "/" } ?: "/"
            query = parsedUri.query
        } else {
            // Fallback manual parser for non-standard URI strings like "file:///storage/emulated/0"
            val schemeDelim = raw.indexOf("://")
            if (schemeDelim != -1) {
                scheme = raw.substring(0, schemeDelim).lowercase()
                val remainder = raw.substring(schemeDelim + 3)
                val slashIndex = remainder.indexOf('/')
                if (slashIndex != -1) {
                    val auth = remainder.substring(0, slashIndex)
                    authority = auth.takeIf { it.isNotEmpty() }
                    host = auth.substringBefore(':').takeIf { it.isNotEmpty() }
                    port = auth.substringAfter(':', "").toIntOrNull()
                    path = "/" + remainder.substring(slashIndex + 1).substringBefore('?')
                    query = remainder.substringAfter('?', "").takeIf { it.isNotEmpty() }
                } else {
                    authority = remainder.takeIf { it.isNotEmpty() }
                    host = remainder.substringBefore(':').takeIf { it.isNotEmpty() }
                    port = remainder.substringAfter(':', "").toIntOrNull()
                    path = "/"
                    query = null
                }
            } else if (raw.contains(':')) {
                scheme = raw.substringBefore(':').lowercase()
                val remainder = raw.substringAfter(':')
                authority = null
                host = null
                port = null
                path = if (remainder.startsWith('/')) remainder else "/$remainder"
                query = null
            } else {
                scheme = "file"
                authority = null
                host = null
                port = null
                path = if (raw.startsWith('/')) raw else "/$raw"
                query = null
            }
        }
    }

    /**
     * Segments of the path excluding empty entries.
     */
    val segments: List<String> by lazy {
        path.split('/').filter { it.isNotEmpty() }
    }

    /**
     * File name or last path segment. Empty for root path.
     */
    val name: String
        get() = segments.lastOrNull() ?: ""

    /**
     * Returns the parent VfsUri or null if already at the root.
     */
    val parent: VfsUri?
        get() {
            if (segments.isEmpty() || path == "/") return null
            val newSegments = segments.dropLast(1)
            val newPath = if (newSegments.isEmpty()) "/" else "/" + newSegments.joinToString("/")
            val auth = authority ?: buildString {
                if (!host.isNullOrEmpty()) {
                    append(host)
                    if (port != null) append(":$port")
                }
            }
            val newRaw = if (auth.isNotEmpty()) {
                "$scheme://$auth$newPath"
            } else {
                "$scheme://$newPath"
            }
            return VfsUri(newRaw)
        }

    /**
     * Resolves a child path segment or relative path.
     */
    fun resolve(child: String): VfsUri {
        val trimmed = child.trim().removePrefix("/").removeSuffix("/")
        if (trimmed.isEmpty()) return this
        val separator = if (path.endsWith("/")) "" else "/"
        val newPath = "$path$separator$trimmed"
        val auth = authority ?: buildString {
            if (!host.isNullOrEmpty()) {
                append(host)
                if (port != null) append(":$port")
            }
        }
        val newRaw = if (auth.isNotEmpty()) {
            "$scheme://$auth$newPath"
        } else {
            "$scheme://$newPath"
        }
        return VfsUri(newRaw)
    }

    override fun compareTo(other: VfsUri): Int = raw.compareTo(other.raw)

    override fun toString(): String = raw

    companion object {
        fun fromFile(path: String): VfsUri {
            val normalized = if (path.startsWith("/")) path else "/$path"
            return VfsUri("file://$normalized")
        }

        val ROOT_FILE = VfsUri("file:///")
    }
}
