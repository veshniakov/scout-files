package dev.scout.files.core.model

import java.util.UUID

data class ServerConnection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: NetworkProtocol,
    val host: String,
    val port: Int = protocol.defaultPort,
    val username: String = "",
    val authType: AuthType = AuthType.PASSWORD,
    val shareOrBucket: String? = null,
    val domainOrRegion: String? = null,
    val rootPath: String = "/",
    val useTls: Boolean = true,
    val requireBiometric: Boolean = false,
    val lastConnectedMillis: Long? = null
) {
    fun rootVfsUri(): VfsUri {
        val cleanRoot = rootPath.trim('/').ifEmpty { "" }
        val pathSuffix = if (cleanRoot.isEmpty()) "" else "/$cleanRoot"
        return when (protocol) {
            NetworkProtocol.SFTP -> VfsUri("sftp://$id$pathSuffix/")
            NetworkProtocol.SMB -> {
                val share = shareOrBucket?.trim('/')?.ifEmpty { "shared" } ?: "shared"
                VfsUri("smb://$id/$share$pathSuffix/")
            }
            NetworkProtocol.FTP -> VfsUri("ftp://$id$pathSuffix/")
            NetworkProtocol.FTPS -> VfsUri("ftps://$id$pathSuffix/")
            NetworkProtocol.WEBDAV -> VfsUri("webdav://$id$pathSuffix/")
            NetworkProtocol.GDRIVE -> VfsUri("gdrive://$id$pathSuffix/")
            NetworkProtocol.S3 -> {
                val bucket = shareOrBucket?.trim('/')?.ifEmpty { "bucket" } ?: "bucket"
                VfsUri("s3://$id/$bucket$pathSuffix/")
            }
        }
    }
}
