package dev.scout.files.core.security

import dev.scout.files.core.model.ServerCredentials
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InMemoryCredentialVault @Inject constructor() : CredentialVault {
    private val storage = ConcurrentHashMap<String, ServerCredentials>()

    override suspend fun storeCredentials(connectionId: String, credentials: ServerCredentials) {
        storage[connectionId] = credentials
    }

    override suspend fun getCredentials(connectionId: String): ServerCredentials? {
        return storage[connectionId]
    }

    override suspend fun deleteCredentials(connectionId: String) {
        storage.remove(connectionId)
    }

    override suspend fun clearAll() {
        storage.clear()
    }
}
