package dev.scout.files.core.security

import dev.scout.files.core.model.ServerCredentials

interface CredentialVault {
    suspend fun storeCredentials(connectionId: String, credentials: ServerCredentials)
    suspend fun getCredentials(connectionId: String): ServerCredentials?
    suspend fun deleteCredentials(connectionId: String)
    suspend fun clearAll()
}
