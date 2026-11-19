package dev.scout.files.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerConnectionTest {

    @Test
    fun testNetworkProtocolPropertiesAndLookup() {
        assertEquals(22, NetworkProtocol.SFTP.defaultPort)
        assertEquals("sftp", NetworkProtocol.SFTP.scheme)
        assertEquals(445, NetworkProtocol.SMB.defaultPort)
        assertEquals(21, NetworkProtocol.FTP.defaultPort)
        assertEquals(990, NetworkProtocol.FTPS.defaultPort)
        assertEquals(443, NetworkProtocol.WEBDAV.defaultPort)
        assertEquals(443, NetworkProtocol.GDRIVE.defaultPort)
        assertEquals(443, NetworkProtocol.S3.defaultPort)

        assertEquals(NetworkProtocol.SFTP, NetworkProtocol.fromScheme("sftp"))
        assertEquals(NetworkProtocol.SFTP, NetworkProtocol.fromScheme("SFTP"))
        assertEquals(NetworkProtocol.SMB, NetworkProtocol.fromScheme("smb"))
        assertEquals(NetworkProtocol.FTP, NetworkProtocol.fromScheme("ftp"))
        assertEquals(NetworkProtocol.FTPS, NetworkProtocol.fromScheme("ftps"))
        assertEquals(NetworkProtocol.WEBDAV, NetworkProtocol.fromScheme("webdav"))
        assertEquals(NetworkProtocol.GDRIVE, NetworkProtocol.fromScheme("gdrive"))
        assertEquals(NetworkProtocol.S3, NetworkProtocol.fromScheme("s3"))
        assertNull(NetworkProtocol.fromScheme("unknown"))
    }

    @Test
    fun testWebDavPresetDefaults() {
        assertEquals("webdav.yandex.ru", WebDavPreset.YANDEX_DISK.defaultHost)
        assertEquals(443, WebDavPreset.YANDEX_DISK.defaultPort)
        assertTrue(WebDavPreset.YANDEX_DISK.useTls)
        assertFalse(WebDavPreset.YANDEX_DISK.isCustom)

        assertTrue(WebDavPreset.CUSTOM.isCustom)
        assertEquals("/remote.php/dav/files/{username}/", WebDavPreset.NEXTCLOUD.defaultRootPath)
        assertEquals("/remote.php/webdav/", WebDavPreset.OWNCLOUD.defaultRootPath)
    }

    @Test
    fun testSftpRootVfsUri() {
        val conn = ServerConnection(
            id = "sftp-1",
            name = "My Server",
            protocol = NetworkProtocol.SFTP,
            host = "192.168.1.50",
            rootPath = "/"
        )
        assertEquals(VfsUri("sftp://sftp-1/"), conn.rootVfsUri())

        val connSub = conn.copy(rootPath = "/var/www/html")
        assertEquals(VfsUri("sftp://sftp-1/var/www/html/"), connSub.rootVfsUri())
    }

    @Test
    fun testSmbRootVfsUri() {
        val connDefaultShare = ServerConnection(
            id = "smb-1",
            name = "NAS",
            protocol = NetworkProtocol.SMB,
            host = "nas.local",
            shareOrBucket = null,
            rootPath = "/"
        )
        assertEquals(VfsUri("smb://smb-1/shared/"), connDefaultShare.rootVfsUri())

        val connCustomShare = ServerConnection(
            id = "smb-2",
            name = "NAS Media",
            protocol = NetworkProtocol.SMB,
            host = "nas.local",
            shareOrBucket = "media",
            rootPath = "/movies"
        )
        assertEquals(VfsUri("smb://smb-2/media/movies/"), connCustomShare.rootVfsUri())
    }

    @Test
    fun testFtpAndFtpsRootVfsUri() {
        val ftpConn = ServerConnection(
            id = "ftp-1",
            name = "FTP Host",
            protocol = NetworkProtocol.FTP,
            host = "ftp.example.com",
            rootPath = "/pub"
        )
        assertEquals(VfsUri("ftp://ftp-1/pub/"), ftpConn.rootVfsUri())

        val ftpsConn = ServerConnection(
            id = "ftps-1",
            name = "Secure FTP",
            protocol = NetworkProtocol.FTPS,
            host = "ftps.example.com",
            rootPath = "/"
        )
        assertEquals(VfsUri("ftps://ftps-1/"), ftpsConn.rootVfsUri())
    }

    @Test
    fun testWebDavRootVfsUri() {
        val conn = ServerConnection(
            id = "dav-1",
            name = "Yandex Disk",
            protocol = NetworkProtocol.WEBDAV,
            host = "webdav.yandex.ru",
            rootPath = "/Documents"
        )
        assertEquals(VfsUri("webdav://dav-1/Documents/"), conn.rootVfsUri())
    }

    @Test
    fun testGoogleDriveRootVfsUri() {
        val conn = ServerConnection(
            id = "gdrive-1",
            name = "Google Drive Work",
            protocol = NetworkProtocol.GDRIVE,
            host = "drive.google.com",
            rootPath = "/"
        )
        assertEquals(VfsUri("gdrive://gdrive-1/"), conn.rootVfsUri())
    }

    @Test
    fun testS3RootVfsUri() {
        val s3Conn = ServerConnection(
            id = "s3-1",
            name = "MinIO Storage",
            protocol = NetworkProtocol.S3,
            host = "minio.local",
            shareOrBucket = "backups",
            rootPath = "/2026"
        )
        assertEquals(VfsUri("s3://s3-1/backups/2026/"), s3Conn.rootVfsUri())
    }

    @Test
    fun testServerCredentialsDefaults() {
        val creds = ServerCredentials()
        assertNull(creds.password)
        assertNull(creds.privateKeyPem)
        assertNull(creds.privateKeyPassphrase)
        assertNull(creds.oauthAccessToken)
        assertNull(creds.oauthRefreshToken)
        assertNull(creds.oauthClientId)
        assertNull(creds.s3SecretKey)

        val fullCreds = ServerCredentials(
            password = "secretPassword",
            privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\ntest\n-----END OPENSSH PRIVATE KEY-----",
            privateKeyPassphrase = "keyPassphrase",
            oauthAccessToken = "token123",
            oauthRefreshToken = "refresh123",
            oauthClientId = "clientApp",
            s3SecretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        )
        assertEquals("secretPassword", fullCreds.password)
        assertTrue(fullCreds.privateKeyPem!!.contains("BEGIN OPENSSH PRIVATE KEY"))
        assertEquals("keyPassphrase", fullCreds.privateKeyPassphrase)
        assertEquals("token123", fullCreds.oauthAccessToken)
        assertEquals("refresh123", fullCreds.oauthRefreshToken)
        assertEquals("clientApp", fullCreds.oauthClientId)
        assertEquals("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", fullCreds.s3SecretKey)
    }
}
