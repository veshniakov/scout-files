package dev.scout.files.feature.connections.model

import dev.scout.files.core.model.AuthType
import dev.scout.files.core.model.NetworkProtocol
import dev.scout.files.core.model.ServerConnection
import dev.scout.files.core.model.ServerCredentials
import dev.scout.files.core.model.WebDavPreset
import java.util.UUID

sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Testing : ConnectionTestState
    data class Success(val itemCount: Int) : ConnectionTestState
    data class Error(val message: String) : ConnectionTestState
}

data class ConnectionFormState(
    val id: String? = null,
    val name: String = "",
    val protocol: NetworkProtocol = NetworkProtocol.SFTP,
    val host: String = "",
    val portText: String = "22",
    val username: String = "",
    val authType: AuthType = AuthType.PASSWORD,
    val password: String = "",
    val isPasswordVisible: Boolean = false,
    val privateKeyPem: String = "",
    val privateKeyPassphrase: String = "",
    val isPassphraseVisible: Boolean = false,
    val shareOrBucket: String = "",
    val domainOrRegion: String = "",
    val rootPath: String = "/",
    val useTls: Boolean = true,
    val requireBiometric: Boolean = false,
    val webDavPreset: WebDavPreset = WebDavPreset.CUSTOM,
    val isAnonymous: Boolean = false,
    val oauthClientId: String = "",
    val oauthRefreshToken: String = "",
    val oauthAccessToken: String = "",
    val s3SecretKey: String = "",
    val isS3SecretVisible: Boolean = false,
    val testState: ConnectionTestState = ConnectionTestState.Idle,
    val isSaving: Boolean = false,
    val errorMessage: String? = null
) {
    val isEditMode: Boolean get() = id != null

    val port: Int get() = portText.toIntOrNull() ?: protocol.defaultPort

    fun toServerConnection(connectionId: String = id ?: UUID.randomUUID().toString()): ServerConnection {
        val resolvedRoot = if (protocol == NetworkProtocol.WEBDAV && rootPath.contains("{username}")) {
            rootPath.replace("{username}", username.trim())
        } else {
            rootPath.trim().ifEmpty { "/" }
        }

        val resolvedHost = if (protocol == NetworkProtocol.GDRIVE && host.isBlank()) {
            "drive.google.com"
        } else {
            host.trim()
        }

        val resolvedShareOrBucket = when (protocol) {
            NetworkProtocol.SMB -> shareOrBucket.trim().ifEmpty { "shared" }
            NetworkProtocol.S3 -> shareOrBucket.trim().ifEmpty { "bucket" }
            else -> shareOrBucket.trim().takeIf { it.isNotEmpty() }
        }

        val resolvedDomainOrRegion = when (protocol) {
            NetworkProtocol.SMB -> domainOrRegion.trim().ifEmpty { "WORKGROUP" }
            NetworkProtocol.S3 -> domainOrRegion.trim().ifEmpty { "us-east-1" }
            else -> domainOrRegion.trim().takeIf { it.isNotEmpty() }
        }

        val resolvedAuthType = when {
            protocol == NetworkProtocol.GDRIVE -> AuthType.OAUTH2
            protocol == NetworkProtocol.S3 -> AuthType.ACCESS_KEY
            (protocol == NetworkProtocol.FTP || protocol == NetworkProtocol.FTPS || protocol == NetworkProtocol.SMB) && isAnonymous -> AuthType.GUEST
            else -> authType
        }

        return ServerConnection(
            id = connectionId,
            name = name.trim().ifEmpty { resolvedHost },
            protocol = protocol,
            host = resolvedHost,
            port = port,
            username = username.trim(),
            authType = resolvedAuthType,
            shareOrBucket = resolvedShareOrBucket,
            domainOrRegion = resolvedDomainOrRegion,
            rootPath = resolvedRoot,
            useTls = useTls,
            requireBiometric = requireBiometric,
            lastConnectedMillis = null
        )
    }

    fun toServerCredentials(): ServerCredentials {
        return ServerCredentials(
            password = if (isAnonymous) null else password.takeIf { it.isNotEmpty() },
            privateKeyPem = if (authType == AuthType.SSH_KEY) privateKeyPem.takeIf { it.isNotEmpty() } else null,
            privateKeyPassphrase = if (authType == AuthType.SSH_KEY) privateKeyPassphrase.takeIf { it.isNotEmpty() } else null,
            oauthAccessToken = if (protocol == NetworkProtocol.GDRIVE) oauthAccessToken.takeIf { it.isNotEmpty() } else null,
            oauthRefreshToken = if (protocol == NetworkProtocol.GDRIVE) oauthRefreshToken.takeIf { it.isNotEmpty() } else null,
            oauthClientId = if (protocol == NetworkProtocol.GDRIVE) oauthClientId.takeIf { it.isNotEmpty() } else null,
            s3SecretKey = if (protocol == NetworkProtocol.S3) s3SecretKey.takeIf { it.isNotEmpty() } else null
        )
    }

    companion object {
        fun fromConnection(connection: ServerConnection, credentials: ServerCredentials?): ConnectionFormState {
            val preset = if (connection.protocol == NetworkProtocol.WEBDAV) {
                when {
                    connection.host == WebDavPreset.YANDEX_DISK.defaultHost -> WebDavPreset.YANDEX_DISK
                    connection.rootPath.contains("/remote.php/dav/files") -> WebDavPreset.NEXTCLOUD
                    connection.rootPath.contains("/remote.php/webdav") -> WebDavPreset.OWNCLOUD
                    else -> WebDavPreset.CUSTOM
                }
            } else {
                WebDavPreset.CUSTOM
            }

            return ConnectionFormState(
                id = connection.id,
                name = connection.name,
                protocol = connection.protocol,
                host = connection.host,
                portText = connection.port.toString(),
                username = connection.username,
                authType = connection.authType,
                password = credentials?.password ?: "",
                privateKeyPem = credentials?.privateKeyPem ?: "",
                privateKeyPassphrase = credentials?.privateKeyPassphrase ?: "",
                shareOrBucket = connection.shareOrBucket ?: "",
                domainOrRegion = connection.domainOrRegion ?: "",
                rootPath = connection.rootPath,
                useTls = connection.useTls,
                requireBiometric = connection.requireBiometric,
                webDavPreset = preset,
                isAnonymous = connection.authType == AuthType.GUEST,
                oauthClientId = credentials?.oauthClientId ?: "",
                oauthRefreshToken = credentials?.oauthRefreshToken ?: "",
                oauthAccessToken = credentials?.oauthAccessToken ?: "",
                s3SecretKey = credentials?.s3SecretKey ?: ""
            )
        }
    }
}
