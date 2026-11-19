package dev.scout.files.core.model

enum class NetworkProtocol(
    val scheme: String,
    val defaultPort: Int,
    val displayName: String
) {
    SFTP(scheme = "sftp", defaultPort = 22, displayName = "SFTP"),
    SMB(scheme = "smb", defaultPort = 445, displayName = "SMB / Samba"),
    FTP(scheme = "ftp", defaultPort = 21, displayName = "FTP"),
    FTPS(scheme = "ftps", defaultPort = 990, displayName = "FTPS"),
    WEBDAV(scheme = "webdav", defaultPort = 443, displayName = "WebDAV"),
    GDRIVE(scheme = "gdrive", defaultPort = 443, displayName = "Google Drive"),
    S3(scheme = "s3", defaultPort = 443, displayName = "Amazon S3 / MinIO");

    companion object {
        fun fromScheme(scheme: String): NetworkProtocol? =
            entries.find { it.scheme.equals(scheme, ignoreCase = true) }
    }
}
