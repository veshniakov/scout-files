package dev.scout.files.provider.sftp

import dev.scout.files.core.database.repository.MetadataRepository
import dev.scout.files.core.model.AuthType
import dev.scout.files.core.model.NetworkProtocol
import dev.scout.files.core.model.ServerConnection
import dev.scout.files.core.model.ServerCredentials
import dev.scout.files.core.model.VfsException
import dev.scout.files.core.model.VfsUri
import dev.scout.files.core.security.CredentialVault
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.EnumSet

class SftpFileSystemProviderTest {

    private val metadataRepository: MetadataRepository = mockk(relaxed = true)
    private val credentialVault: CredentialVault = mockk(relaxed = true)
    private val sshClient: SSHClient = mockk(relaxed = true)
    private val sftpClient: SFTPClient = mockk(relaxed = true)
    private val remoteFile: RemoteFile = mockk(relaxed = true)

    private lateinit var sessionPool: SftpSessionPool
    private lateinit var provider: SftpFileSystemProvider

    private val connection = ServerConnection(
        id = "sftp-conn-1",
        name = "Production SFTP",
        protocol = NetworkProtocol.SFTP,
        host = "sftp.example.com",
        port = 22,
        username = "deploy",
        authType = AuthType.PASSWORD,
        rootPath = "/var/www"
    )

    private val credentials = ServerCredentials(password = "supersecret")

    @Before
    fun setUp() {
        every { sshClient.isConnected } returns true
        every { sshClient.isAuthenticated } returns true
        every { sshClient.newSFTPClient() } returns sftpClient

        sessionPool = SftpSessionPool(clientFactory = { sshClient })
        provider = SftpFileSystemProvider(
            metadataRepository = metadataRepository,
            credentialVault = credentialVault,
            sessionPool = sessionPool,
            ioDispatcher = Dispatchers.Unconfined
        )

        coEvery { metadataRepository.getServerConnection("sftp-conn-1") } returns connection
        coEvery { credentialVault.getCredentials("sftp-conn-1") } returns credentials
    }

    @Test
    fun testUriResolutionAndListDirectory() = runTest {
        val fileAttr = mockk<FileAttributes>(relaxed = true) {
            every { size } returns 1024L
            every { mtime } returns 1700000000L
            every { mode } returns FileMode(0x81a4) // regular file
        }
        val dirAttr = mockk<FileAttributes>(relaxed = true) {
            every { size } returns 4096L
            every { mtime } returns 1700000000L
            every { mode } returns FileMode(0x41ed) // directory
        }

        val dotItem = mockk<RemoteResourceInfo> {
            every { name } returns "."
            every { isDirectory } returns true
        }
        val dotDotItem = mockk<RemoteResourceInfo> {
            every { name } returns ".."
            every { isDirectory } returns true
        }
        val fileItem = mockk<RemoteResourceInfo> {
            every { name } returns "report.pdf"
            every { path } returns "/var/www/docs/report.pdf"
            every { isDirectory } returns false
            every { attributes } returns fileAttr
        }
        val dirItem = mockk<RemoteResourceInfo> {
            every { name } returns "assets"
            every { path } returns "/var/www/docs/assets"
            every { isDirectory } returns true
            every { attributes } returns dirAttr
        }

        every { sftpClient.ls("/var/www/docs") } returns listOf(dotItem, dotDotItem, fileItem, dirItem)

        val uri = VfsUri("sftp://sftp-conn-1/var/www/docs")
        val nodes = provider.listDirectory(uri)

        assertEquals(2, nodes.size)

        val fileNode = nodes.find { it.name == "report.pdf" }
        assertNotNull(fileNode)
        assertFalse(fileNode!!.isDirectory)
        assertEquals(1024L, fileNode.sizeBytes)
        assertEquals("application/pdf", fileNode.mimeType)
        assertEquals("sftp://sftp-conn-1/var/www/docs/report.pdf", fileNode.uri.raw)

        val dirNode = nodes.find { it.name == "assets" }
        assertNotNull(dirNode)
        assertTrue(dirNode!!.isDirectory)
        assertEquals("sftp://sftp-conn-1/var/www/docs/assets", dirNode.uri.raw)
    }

    @Test
    fun testGetMetadata() = runTest {
        val fileAttr = mockk<FileAttributes>(relaxed = true) {
            every { size } returns 4096L
            every { mtime } returns 1650000000L
            every { mode } returns FileMode(0x81a4)
        }
        every { sftpClient.statExistence("/var/www/notes.txt") } returns fileAttr

        val uri = VfsUri("sftp://sftp-conn-1/var/www/notes.txt")
        val node = provider.getMetadata(uri)

        assertEquals("notes.txt", node.name)
        assertFalse(node.isDirectory)
        assertEquals(4096L, node.sizeBytes)
        assertEquals("text/plain", node.mimeType)
        assertEquals(1650000000000L, node.lastModifiedMillis)
    }

    @Test
    fun testGetMetadataNotFoundThrows() = runTest {
        every { sftpClient.statExistence("/var/www/missing.txt") } returns null

        val uri = VfsUri("sftp://sftp-conn-1/var/www/missing.txt")
        try {
            provider.getMetadata(uri)
            fail("Expected VfsException.NotFound")
        } catch (e: VfsException.NotFound) {
            assertEquals(uri, e.uri)
        }
    }

    @Test
    fun testOpenRandomAccessPositionedRead() = runTest {
        every { sftpClient.open("/var/www/data.bin", any()) } returns remoteFile
        every { remoteFile.length() } returns 1000L
        every { remoteFile.read(any(), any(), any(), any()) } answers {
            val fileOffset = firstArg<Long>()
            val buffer = secondArg<ByteArray>()
            val offset = thirdArg<Int>()
            val length = arg<Int>(3)
            // fill buffer with sequence
            for (i in 0 until length) {
                buffer[offset + i] = ((fileOffset + i) % 128).toByte()
            }
            length
        }

        val uri = VfsUri("sftp://sftp-conn-1/var/www/data.bin")
        val rac = provider.openRandomAccess(uri, "r")

        assertEquals(1000L, rac.length)
        assertEquals(0L, rac.position)

        rac.seek(50L)
        assertEquals(50L, rac.position)

        val buf = ByteArray(10)
        val readCount = rac.read(buf, 0, 10)

        assertEquals(10, readCount)
        assertEquals(60L, rac.position)
        assertEquals(50.toByte(), buf[0])
        assertEquals(59.toByte(), buf[9])

        rac.close()
        verify { remoteFile.close() }
    }

    @Test
    fun testSshKeyAuthentication() = runTest {
        val keyConn = connection.copy(
            id = "sftp-key-1",
            authType = AuthType.SSH_KEY
        )
        val keyCreds = ServerCredentials(
            privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\ntest\n-----END OPENSSH PRIVATE KEY-----",
            privateKeyPassphrase = "pass"
        )
        coEvery { metadataRepository.getServerConnection("sftp-key-1") } returns keyConn
        coEvery { credentialVault.getCredentials("sftp-key-1") } returns keyCreds

        val mockKeyProvider = mockk<KeyProvider>()
        every { sshClient.loadKeys(keyCreds.privateKeyPem, null, any()) } returns mockKeyProvider

        every { sftpClient.statExistence("/var/www/test.txt") } returns mockk(relaxed = true)

        val uri = VfsUri("sftp://sftp-key-1/var/www/test.txt")
        provider.exists(uri)

        verify { sshClient.authPublickey("deploy", mockKeyProvider) }
    }

    @Test
    fun testSshKeyAuthenticationWithoutPassphrase() = runTest {
        val keyConn = connection.copy(
            id = "sftp-key-no-pass",
            authType = AuthType.SSH_KEY
        )
        val keyCreds = ServerCredentials(
            privateKeyPem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA0...\n-----END RSA PRIVATE KEY-----",
            privateKeyPassphrase = null
        )
        coEvery { metadataRepository.getServerConnection("sftp-key-no-pass") } returns keyConn
        coEvery { credentialVault.getCredentials("sftp-key-no-pass") } returns keyCreds

        val mockKeyProvider = mockk<KeyProvider>()
        every { sshClient.loadKeys(keyCreds.privateKeyPem, null, null) } returns mockKeyProvider

        every { sftpClient.statExistence("/var/www/test.txt") } returns mockk(relaxed = true)

        val uri = VfsUri("sftp://sftp-key-no-pass/var/www/test.txt")
        val exists = provider.exists(uri)

        assertTrue(exists)
        verify { sshClient.loadKeys(keyCreds.privateKeyPem, null, null) }
        verify { sshClient.authPublickey("deploy", mockKeyProvider) }
    }

    @Test
    fun testSessionPoolAutoReconnectOnTransportDrop() = runTest {
        val freshSshClient = mockk<SSHClient>(relaxed = true) {
            every { isConnected } returns true
            every { isAuthenticated } returns true
        }
        val freshSftpClient = mockk<SFTPClient>(relaxed = true)
        every { freshSshClient.newSFTPClient() } returns freshSftpClient

        var clientCallCount = 0
        val factory: () -> SSHClient = {
            clientCallCount++
            if (clientCallCount == 1) sshClient else freshSshClient
        }

        val reconnectPool = SftpSessionPool(clientFactory = factory)
        val testProvider = SftpFileSystemProvider(
            metadataRepository = metadataRepository,
            credentialVault = credentialVault,
            sessionPool = reconnectPool,
            ioDispatcher = Dispatchers.Unconfined
        )

        // First call on old sftpClient throws TransportException
        every { sftpClient.statExistence("/var/www/test.txt") } throws TransportException("Broken pipe")
        // Second call on fresh sftpClient succeeds
        every { freshSftpClient.statExistence("/var/www/test.txt") } returns mockk(relaxed = true)

        val uri = VfsUri("sftp://sftp-conn-1/var/www/test.txt")
        val exists = testProvider.exists(uri)

        assertTrue(exists)
        assertEquals(2, clientCallCount)
        verify { freshSftpClient.statExistence("/var/www/test.txt") }
    }

    @Test
    fun testOperationsCreateDeleteRename() = runTest {
        val dirUri = VfsUri("sftp://sftp-conn-1/var/www/newdir")
        provider.createDirectory(dirUri)
        verify { sftpClient.mkdirs("/var/www/newdir") }

        val fileAttr = mockk<FileAttributes>(relaxed = true) {
            every { mode } returns FileMode(0x81a4) // file
        }
        every { sftpClient.statExistence("/var/www/old.txt") } returns fileAttr
        val fileUri = VfsUri("sftp://sftp-conn-1/var/www/old.txt")
        provider.delete(fileUri, recursive = false)
        verify { sftpClient.rm("/var/www/old.txt") }

        val targetUri = VfsUri("sftp://sftp-conn-1/var/www/renamed.txt")
        provider.rename(fileUri, targetUri)
        verify { sftpClient.rename("/var/www/old.txt", "/var/www/renamed.txt") }
    }

    @Test
    fun testErrorMappings() = runTest {
        val uri = VfsUri("sftp://sftp-conn-1/var/www/err.txt")

        every { sftpClient.statExistence("/var/www/err.txt") } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Access denied")
        try {
            provider.exists(uri)
            fail("Expected VfsException.PermissionDenied")
        } catch (e: VfsException.PermissionDenied) {
            assertEquals(uri, e.uri)
        }

        every { sftpClient.statExistence("/var/www/err.txt") } throws UserAuthException("Bad credentials")
        try {
            provider.exists(uri)
            fail("Expected VfsException.AuthenticationRequired")
        } catch (e: VfsException.AuthenticationRequired) {
            assertEquals(uri, e.uri)
        }
    }
}
