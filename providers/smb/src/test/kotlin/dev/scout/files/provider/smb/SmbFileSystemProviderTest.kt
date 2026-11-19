package dev.scout.files.provider.smb

import com.hierynomus.msdtyp.FileTime
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileAllInformation
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import dev.scout.files.core.database.repository.MetadataRepository
import dev.scout.files.core.model.AuthType
import dev.scout.files.core.model.NetworkProtocol
import dev.scout.files.core.model.ServerConnection
import dev.scout.files.core.model.ServerCredentials
import dev.scout.files.core.model.VfsException
import dev.scout.files.core.model.VfsUri
import dev.scout.files.core.security.CredentialVault
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SmbFileSystemProviderTest {

    private val metadataRepository: MetadataRepository = mockk(relaxed = true)
    private val credentialVault: CredentialVault = mockk(relaxed = true)
    private val smbClient: SMBClient = mockk(relaxed = true)
    private val connection: Connection = mockk(relaxed = true)
    private val session: Session = mockk(relaxed = true)
    private val diskShare: DiskShare = mockk(relaxed = true)
    private val file: File = mockk(relaxed = true)

    private lateinit var sessionPool: SmbSessionPool
    private lateinit var provider: SmbFileSystemProvider

    private val serverConn = ServerConnection(
        id = "smb-conn-1",
        name = "Office NAS",
        protocol = NetworkProtocol.SMB,
        host = "192.168.1.100",
        port = 445,
        username = "alice",
        authType = AuthType.PASSWORD,
        shareOrBucket = "public",
        domainOrRegion = "WORKGROUP",
        rootPath = "data"
    )

    private val credentials = ServerCredentials(password = "secretpass")

    @Before
    fun setUp() {
        every { connection.isConnected } returns true
        every { diskShare.isConnected } returns true
        every { smbClient.connect(any<String>(), any<Int>()) } returns connection
        every { connection.authenticate(any()) } returns session
        every { session.connectShare("public") } returns diskShare

        sessionPool = SmbSessionPool(clientFactory = { smbClient })
        provider = SmbFileSystemProvider(
            metadataRepository = metadataRepository,
            credentialVault = credentialVault,
            sessionPool = sessionPool,
            ioDispatcher = Dispatchers.Unconfined
        )

        coEvery { metadataRepository.getServerConnection("smb-conn-1") } returns serverConn
        coEvery { credentialVault.getCredentials("smb-conn-1") } returns credentials
    }

    @Test
    fun testUriShareAndPathParsingAndListDirectory() = runTest {
        val dotItem = mockk<FileIdBothDirectoryInformation> {
            every { fileName } returns "."
        }
        val dotDotItem = mockk<FileIdBothDirectoryInformation> {
            every { fileName } returns ".."
        }
        val fileItem = mockk<FileIdBothDirectoryInformation> {
            every { fileName } returns "budget.pdf"
            every { fileAttributes } returns FileAttributes.FILE_ATTRIBUTE_NORMAL.value
            every { endOfFile } returns 15000L
            every { lastWriteTime } returns FileTime.ofEpochMillis(1700000000000L)
        }
        val dirItem = mockk<FileIdBothDirectoryInformation> {
            every { fileName } returns "subfolder"
            every { fileAttributes } returns FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value
            every { endOfFile } returns 0L
            every { lastWriteTime } returns FileTime.ofEpochMillis(1700000000000L)
        }

        every { diskShare.list("data\\docs") } returns listOf(dotItem, dotDotItem, fileItem, dirItem)

        val uri = VfsUri("smb://smb-conn-1/public/data/docs")
        val nodes = provider.listDirectory(uri)

        assertEquals(2, nodes.size)

        val fileNode = nodes.find { it.name == "budget.pdf" }
        assertNotNull(fileNode)
        assertFalse(fileNode!!.isDirectory)
        assertEquals(15000L, fileNode.sizeBytes)
        assertEquals("application/pdf", fileNode.mimeType)
        assertEquals("smb://smb-conn-1/public/data/docs/budget.pdf", fileNode.uri.raw)

        val dirNode = nodes.find { it.name == "subfolder" }
        assertNotNull(dirNode)
        assertTrue(dirNode!!.isDirectory)
        assertEquals("smb://smb-conn-1/public/data/docs/subfolder", dirNode.uri.raw)
    }

    @Test
    fun testGetMetadata() = runTest {
        val standardInfo = mockk<FileStandardInformation> {
            every { isDirectory } returns false
            every { endOfFile } returns 8192L
        }
        val basicInfo = mockk<FileBasicInformation> {
            every { fileAttributes } returns FileAttributes.FILE_ATTRIBUTE_NORMAL.value
            every { lastWriteTime } returns FileTime.ofEpochMillis(1650000000000L)
        }
        val allInfo = mockk<FileAllInformation> {
            every { standardInformation } returns standardInfo
            every { basicInformation } returns basicInfo
        }

        every { diskShare.getFileInformation("data\\report.pdf") } returns allInfo

        val uri = VfsUri("smb://smb-conn-1/public/data/report.pdf")
        val node = provider.getMetadata(uri)

        assertEquals("report.pdf", node.name)
        assertFalse(node.isDirectory)
        assertEquals(8192L, node.sizeBytes)
        assertEquals("application/pdf", node.mimeType)
        assertEquals(1650000000000L, node.lastModifiedMillis)
    }

    @Test
    fun testGetMetadataForShareRoot() = runTest {
        val rootUri = VfsUri("smb://smb-conn-1/public")
        val node = provider.getMetadata(rootUri)

        assertEquals("public", node.name)
        assertTrue(node.isDirectory)
        assertEquals(0L, node.sizeBytes)
    }

    @Test
    fun testOpenRandomAccessPositionedRead() = runTest {
        val standardInfo = mockk<FileStandardInformation> {
            every { endOfFile } returns 500L
        }
        val allInfo = mockk<FileAllInformation> {
            every { standardInformation } returns standardInfo
        }
        every { file.fileInformation } returns allInfo

        every {
            diskShare.openFile("data\\stream.bin", any(), any(), any(), any(), any())
        } returns file

        every { file.read(any(), any(), any(), any()) } answers {
            val buffer = firstArg<ByteArray>()
            val fileOffset = secondArg<Long>()
            val offset = thirdArg<Int>()
            val length = arg<Int>(3)
            for (i in 0 until length) {
                buffer[offset + i] = ((fileOffset + i) % 100).toByte()
            }
            length
        }

        val uri = VfsUri("smb://smb-conn-1/public/data/stream.bin")
        val rac = provider.openRandomAccess(uri, "r")

        assertEquals(500L, rac.length)
        assertEquals(0L, rac.position)

        rac.seek(20L)
        assertEquals(20L, rac.position)

        val buf = ByteArray(10)
        val readCount = rac.read(buf, 0, 10)

        assertEquals(10, readCount)
        assertEquals(30L, rac.position)
        assertEquals(20.toByte(), buf[0])
        assertEquals(29.toByte(), buf[9])

        rac.close()
        verify { file.close() }
    }

    @Test
    fun testGuestAuthentication() = runTest {
        val guestConn = serverConn.copy(
            id = "smb-guest-1",
            authType = AuthType.GUEST
        )
        coEvery { metadataRepository.getServerConnection("smb-guest-1") } returns guestConn
        coEvery { credentialVault.getCredentials("smb-guest-1") } returns null

        val authSlot = slot<AuthenticationContext>()
        every { connection.authenticate(capture(authSlot)) } returns session

        val uri = VfsUri("smb://smb-guest-1/public")
        provider.exists(uri)

        assertTrue(authSlot.captured.isGuest)
    }

    @Test
    fun testPasswordAndDomainAuthentication() = runTest {
        val authSlot = slot<AuthenticationContext>()
        every { connection.authenticate(capture(authSlot)) } returns session

        val uri = VfsUri("smb://smb-conn-1/public")
        provider.exists(uri)

        val auth = authSlot.captured
        assertEquals("alice", auth.username)
        assertEquals("WORKGROUP", auth.domain)
        assertEquals("secretpass", String(auth.password))
    }

    @Test
    fun testSessionPoolAutoReconnectOnException() = runTest {
        val freshConnection = mockk<Connection>(relaxed = true) {
            every { isConnected } returns true
        }
        val freshSession = mockk<Session>(relaxed = true)
        val freshShare = mockk<DiskShare>(relaxed = true) {
            every { isConnected } returns true
        }

        every { freshConnection.authenticate(any()) } returns freshSession
        every { freshSession.connectShare("public") } returns freshShare

        var clientConnectCount = 0
        val freshClient = mockk<SMBClient>(relaxed = true) {
            every { connect(any<String>(), any<Int>()) } answers {
                clientConnectCount++
                if (clientConnectCount == 1) connection else freshConnection
            }
        }

        val reconnectPool = SmbSessionPool(clientFactory = { freshClient })
        val testProvider = SmbFileSystemProvider(
            metadataRepository = metadataRepository,
            credentialVault = credentialVault,
            sessionPool = reconnectPool,
            ioDispatcher = Dispatchers.Unconfined
        )

        // First call throws SMBRuntimeException
        every { diskShare.fileExists("data\\file.txt") } throws SMBRuntimeException("Connection lost")
        // Second call on freshShare succeeds
        every { freshShare.fileExists("data\\file.txt") } returns true

        val uri = VfsUri("smb://smb-conn-1/public/data/file.txt")
        val exists = testProvider.exists(uri)

        assertTrue(exists)
        assertEquals(2, clientConnectCount)
        verify { freshShare.fileExists("data\\file.txt") }
    }

    @Test
    fun testOperationsCreateDeleteRename() = runTest {
        val dirUri = VfsUri("smb://smb-conn-1/public/data/newfolder")
        provider.createDirectory(dirUri)
        verify { diskShare.mkdir("data\\newfolder") }

        val fileUri = VfsUri("smb://smb-conn-1/public/data/old.txt")
        every { diskShare.folderExists("data\\old.txt") } returns false
        provider.delete(fileUri, recursive = false)
        verify { diskShare.rm("data\\old.txt") }

        val targetUri = VfsUri("smb://smb-conn-1/public/data/renamed.txt")
        every {
            diskShare.openFile("data\\old.txt", any(), any(), any(), any(), any())
        } returns file

        provider.rename(fileUri, targetUri)
        verify { file.rename("data\\renamed.txt", true) }
    }

    @Test
    fun testErrorMappings() = runTest {
        val uri = VfsUri("smb://smb-conn-1/public/data/error.txt")

        val notFoundException = mockk<SMBApiException> {
            every { status } returns NtStatus.STATUS_OBJECT_NAME_NOT_FOUND
            every { message } returns "File not found"
        }
        every { diskShare.fileExists("data\\error.txt") } throws notFoundException
        try {
            provider.exists(uri)
            fail("Expected VfsException.NotFound")
        } catch (e: VfsException.NotFound) {
            assertEquals(uri, e.uri)
        }

        val accessDeniedException = mockk<SMBApiException> {
            every { status } returns NtStatus.STATUS_ACCESS_DENIED
            every { message } returns "Access denied"
        }
        every { diskShare.fileExists("data\\error.txt") } throws accessDeniedException
        try {
            provider.exists(uri)
            fail("Expected VfsException.PermissionDenied")
        } catch (e: VfsException.PermissionDenied) {
            assertEquals(uri, e.uri)
        }

        val logonFailureException = mockk<SMBApiException> {
            every { status } returns NtStatus.STATUS_LOGON_FAILURE
            every { message } returns "Logon failed"
        }
        every { diskShare.fileExists("data\\error.txt") } throws logonFailureException
        try {
            provider.exists(uri)
            fail("Expected VfsException.AuthenticationRequired")
        } catch (e: VfsException.AuthenticationRequired) {
            assertEquals(uri, e.uri)
        }
    }

    @Test
    fun testRootListingChildUriPreservesShare() = runTest {
        val fileItem = mockk<FileIdBothDirectoryInformation> {
            every { fileName } returns "report.pdf"
            every { fileAttributes } returns FileAttributes.FILE_ATTRIBUTE_NORMAL.value
            every { endOfFile } returns 2048L
            every { lastWriteTime } returns FileTime.ofEpochMillis(1700000000000L)
        }
        every { diskShare.list("") } returns listOf(fileItem)

        // Root URI with empty segments: smb://smb-conn-1
        val rootUri = VfsUri("smb://smb-conn-1")
        val nodes = provider.listDirectory(rootUri)

        assertEquals(1, nodes.size)
        val child = nodes.first()
        assertEquals("report.pdf", child.name)
        // Ensure child URI contains the share name 'public'
        assertEquals("smb://smb-conn-1/public/report.pdf", child.uri.raw)
    }

    @Test
    fun testBlankAndWhitespaceShareFallback() = runTest {
        val blankShareConn = serverConn.copy(
            id = "smb-blank-share",
            shareOrBucket = "   "
        )
        val trimmedShareConn = serverConn.copy(
            id = "smb-trimmed-share",
            shareOrBucket = "  /custom-share/  "
        )
        val fallbackShare = mockk<DiskShare>(relaxed = true) {
            every { isConnected } returns true
        }
        val customShare = mockk<DiskShare>(relaxed = true) {
            every { isConnected } returns true
        }
        every { session.connectShare("shared") } returns fallbackShare
        every { session.connectShare("custom-share") } returns customShare

        coEvery { metadataRepository.getServerConnection("smb-blank-share") } returns blankShareConn
        coEvery { credentialVault.getCredentials("smb-blank-share") } returns credentials
        coEvery { metadataRepository.getServerConnection("smb-trimmed-share") } returns trimmedShareConn
        coEvery { credentialVault.getCredentials("smb-trimmed-share") } returns credentials

        // Blank share should fall back to "shared"
        provider.exists(VfsUri("smb://smb-blank-share"))
        verify { session.connectShare("shared") }

        // Whitespace and slashes trimmed
        provider.exists(VfsUri("smb://smb-trimmed-share"))
        verify { session.connectShare("custom-share") }
    }

    @Test
    fun testNonFatalFileExceptionDoesNotInvalidateSession() = runTest {
        var connectCount = 0
        val trackingClient = mockk<SMBClient>(relaxed = true) {
            every { connect(any<String>(), any<Int>()) } answers {
                connectCount++
                connection
            }
        }
        val testPool = SmbSessionPool(clientFactory = { trackingClient })
        val testProvider = SmbFileSystemProvider(
            metadataRepository = metadataRepository,
            credentialVault = credentialVault,
            sessionPool = testPool,
            ioDispatcher = Dispatchers.Unconfined
        )

        val notFoundException = mockk<SMBApiException> {
            every { status } returns NtStatus.STATUS_OBJECT_NAME_NOT_FOUND
            every { message } returns "Object not found"
        }
        every { diskShare.fileExists("data\\missing.txt") } throws notFoundException

        val uri = VfsUri("smb://smb-conn-1/public/data/missing.txt")
        try {
            testProvider.exists(uri)
            fail("Expected VfsException.NotFound")
        } catch (e: VfsException.NotFound) {
            assertEquals(uri, e.uri)
        }

        // Must connect exactly once: non-fatal file error should NOT trigger reconnect
        assertEquals(1, connectCount)

        // Calling exists again uses the same cached session, connectCount remains 1
        try {
            testProvider.exists(uri)
        } catch (_: VfsException.NotFound) {}
        assertEquals(1, connectCount)
    }
}
