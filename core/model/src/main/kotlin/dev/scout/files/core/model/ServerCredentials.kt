package dev.scout.files.core.model

data class ServerCredentials(
    val password: String? = null,
    val privateKeyPem: String? = null,
    val privateKeyPassphrase: String? = null,
    val oauthAccessToken: String? = null,
    val oauthRefreshToken: String? = null,
    val oauthClientId: String? = null,
    val s3SecretKey: String? = null
)
