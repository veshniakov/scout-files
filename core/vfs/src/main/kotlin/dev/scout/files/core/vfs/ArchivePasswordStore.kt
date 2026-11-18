package dev.scout.files.core.vfs

import dev.scout.files.core.model.ArchiveUri
import dev.scout.files.core.model.VfsUri
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread-safe in-memory session store for archive passwords.
 * Keyed by canonical source archive URI string.
 */
@Singleton
class ArchivePasswordStore @Inject constructor() {

    private val passwords = ConcurrentHashMap<String, CharArray>()

    private fun normalizeKey(uri: VfsUri): String {
        val sourceUri = ArchiveUri.extractSourceUri(uri) ?: uri
        return sourceUri.raw
    }

    /**
     * Stores a password for the given archive URI.
     */
    fun setPassword(uri: VfsUri, password: CharArray) {
        val key = normalizeKey(uri)
        passwords[key] = password.copyOf()
    }

    /**
     * Stores a password string for the given archive URI.
     */
    fun setPassword(uri: VfsUri, password: String) {
        setPassword(uri, password.toCharArray())
    }

    /**
     * Retrieves the stored password for the given archive URI as CharArray, or null if none.
     */
    fun getPassword(uri: VfsUri): CharArray? {
        val key = normalizeKey(uri)
        return passwords[key]?.copyOf()
    }

    /**
     * Retrieves the stored password for the given archive URI as String, or null if none.
     */
    fun getPasswordString(uri: VfsUri): String? {
        return getPassword(uri)?.let { String(it) }
    }

    /**
     * Checks whether a password has been registered for the given archive URI.
     */
    fun hasPassword(uri: VfsUri): Boolean {
        val key = normalizeKey(uri)
        return passwords.containsKey(key)
    }

    /**
     * Removes the stored password for the given archive URI.
     */
    fun removePassword(uri: VfsUri) {
        val key = normalizeKey(uri)
        passwords.remove(key)
    }

    /**
     * Clears all cached passwords in this session.
     */
    fun clear() {
        passwords.clear()
    }
}
