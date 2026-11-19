package dev.scout.files.provider.ftp

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
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPSClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Calendar

class FtpFileSystemProviderTest {

    private val metadataRepository: MetadataRepository = mockk(relaxed = true)
    private val credentialVault: CredentialVault = mockk(relaxed = true)
    private val clientFactory: FtpClientFactory = mockk(relaxed = true)
    private val ftpClient: FTPClient = mockk(relaxed = true)
    private val ftpsClient: FTPSClient = mockk(relaxed = true)

    private lateinit var ftpProvider: FtpFileSystemProvider
    private lateinit var ftpsProvider: FtpsFileSystemProvider

    private val ftpConnection = ServerConnection(
        id = "ftp-conn-1",
        name = "Public FTP",
        protocol = NetworkProtocol.FTP,
        host = "ftp.example.com",
        port = 21,
        username = "anonymous",
        authType = AuthType.PASSWORD,
        rootPath = "/pub"
    )

    private val ftpsConnection = ServerConnection(
        id = "ftps-conn-1",
        name = "Secure FTPS",
        protocol = NetworkProtocol.FTPS,
        host = "ftps.example.com",
        port = 990,
        username = "secureuser",
        authType = AuthType.PASSWORD,
        rootPath = "/"
    )

    @Before
    fun setUp() {
        ftpProvider = FtpFileSystemProvider(
            metadataRepository = metadataRepository,
            credentialVault = credentialVault,
            clientFactory = clientFactory,
            ioDispatcher = Dispatchers.Unconfined
        )

        ftpsProvider = FtpsFileSystemProvider(
            metadataRepository = metadataRepository,
            credentialVault = credentialVault,
            clientFactory = clientFactory,
            ioDispatcher = Dispatchers.Unconfined
        )

        every { clientFactory.createFtpClient() } returns ftpClient
        every { clientFactory.createFtpsClient(any()) } returns ftpsClient

        every { ftpClient.replyCode } returns 200
        every { ftpClient.login(any(), any()) } returns true
        every { ftpClient.isConnected } returns true

        every { ftpsClient.replyCode } returns 200
        every { ftpsClient.login(any(), any()) } returns true
        every { ftpsClient.isConnected } returns true

        coEvery { metadataRepository.getServerConnection("ftp-conn-1") } returns ftpConnection
        coEvery { metadataRepository.getServerConnection("ftps-conn-1") } returns ftpsConnection
        coEvery { credentialVault.getCredentials("ftp-conn-1") } returns ServerCredentials(password = "secret")
        coEvery { credentialVault.getCredentials("ftps-conn-1") } returns ServerCredentials(password = "ftpspass")
    }

    @Test
    fun listDirectory_returnsMappedVfsNodesAndFiltersDots() = runTest {
        val file1 = mockk<FTPFile> {
            every { name } returns "."
            every { isDirectory } returns true
        }
        val file2 = mockk<FTPFile> {
            every { name } returns ".."
            every { isDirectory } returns true
        }
        val cal = Calendar.getInstance().apply { timeInMillis = 1700000000000L }
        val file3 = mockk<FTPFile> {
            every { name } returns "docs"
            every { isDirectory } returns true
            every { size } returns 0L
            every { timestamp } returns cal
        }
        val file4 = mockk<FTPFile> {
            every { name } returns "readme.txt"
            every { isDirectory } returns false
            every { size } returns 1024L
            every { timestamp } returns cal
        }

        every { ftpClient.listFiles("/pub") } returns arrayOf(file1, file2, file3, file4)

        val uri = VfsUri("ftp://ftp-conn-1/")
        val nodes = ftpProvider.listDirectory(uri)

        assertEquals(2, nodes.size)
        assertEquals("docs", nodes[0].name)
        assertTrue(nodes[0].isDirectory)
        assertEquals("readme.txt", nodes[1].name)
        assertFalse(nodes[1].isDirectory)
        assertEquals(1024L, nodes[1].sizeBytes)

        verify { ftpClient.enterLocalPassiveMode() }
        verify { ftpClient.setFileType(FTP.BINARY_FILE_TYPE) }
        verify { ftpClient.controlEncoding = "UTF-8" }
        verify { ftpClient.sendCommand("OPTS UTF8", "ON") }
    }

    @Test
    fun getMetadata_rootDirectory_returnsDirectoryNode() = runTest {
        val uri = VfsUri("ftp://ftp-conn-1/")
        val node = ftpProvider.getMetadata(uri)

        assertTrue(node.isDirectory)
        assertEquals(0L, node.sizeBytes)
    }

    @Test
    fun getMetadata_regularFile_returnsCorrectAttributes() = runTest {
        val cal = Calendar.getInstance().apply { timeInMillis = 1700000000000L }
        val ftpFile = mockk<FTPFile> {
            every { name } returns "report.pdf"
            every { isDirectory } returns false
            every { size } returns 4096L
            every { timestamp } returns cal
        }
        every { ftpClient.mlistFile("/pub/report.pdf") } returns ftpFile

        val uri = VfsUri("ftp://ftp-conn-1/report.pdf")
        val node = ftpProvider.getMetadata(uri)

        assertEquals("report.pdf", node.name)
        assertFalse(node.isDirectory)
        assertEquals(4096L, node.sizeBytes)
        assertEquals(1700000000000L, node.lastModifiedMillis)
    }

    @Test
    fun exists_returnsTrueWhenFileExists() = runTest {
        val ftpFile = mockk<FTPFile> {
            every { name } returns "test.txt"
            every { isDirectory } returns false
            every { size } returns 100L
            every { timestamp } returns null
        }
        every { ftpClient.mlistFile("/pub/test.txt") } returns ftpFile

        val uri = VfsUri("ftp://ftp-conn-1/test.txt")
        assertTrue(ftpProvider.exists(uri))
    }

    @Test
    fun exists_returnsFalseWhenFileNotFound() = runTest {
        every { ftpClient.mlistFile("/pub/missing.txt") } returns null
        every { ftpClient.listFiles("/pub/missing.txt") } returns emptyArray()
        every { ftpClient.listFiles("/pub") } returns emptyArray()

        val uri = VfsUri("ftp://ftp-conn-1/missing.txt")
        assertFalse(ftpProvider.exists(uri))
    }

    @Test
    fun openInputStream_readsDataAndCompletesPendingCommandOnClose() = runTest {
        val testData = "Hello FTP World!".toByteArray(Charsets.UTF_8)
        val byteStream = ByteArrayInputStream(testData)
        every { ftpClient.retrieveFileStream("/pub/hello.txt") } returns byteStream

        val uri = VfsUri("ftp://ftp-conn-1/hello.txt")
        val stream = ftpProvider.openInputStream(uri)
        val readData = stream.readBytes()

        assertEquals("Hello FTP World!", String(readData, Charsets.UTF_8))
        stream.close()

        verify { ftpClient.completePendingCommand() }
        verify { ftpClient.disconnect() }
    }

    @Test
    fun openOutputStream_storesDataAndCompletesPendingCommandOnClose() = runTest {
        val outStream = ByteArrayOutputStream()
        every { ftpClient.storeFileStream("/pub/upload.txt") } returns outStream

        val uri = VfsUri("ftp://ftp-conn-1/upload.txt")
        val stream = ftpProvider.openOutputStream(uri, append = false)
        stream.write("Uploaded data".toByteArray(Charsets.UTF_8))
        stream.close()

        assertEquals("Uploaded data", String(outStream.toByteArray(), Charsets.UTF_8))
        verify { ftpClient.completePendingCommand() }
        verify { ftpClient.disconnect() }
    }

    @Test
    fun openRandomAccess_seekAndReadSetsRestartOffset() = runTest {
        val ftpFile = mockk<FTPFile> {
            every { name } returns "movie.mp4"
            every { isDirectory } returns false
            every { size } returns 1000L
            every { timestamp } returns null
        }
        every { ftpClient.mlistFile("/pub/movie.mp4") } returns ftpFile

        val dataPart1 = "0123456789".toByteArray(Charsets.UTF_8)
        val streamPart1 = ByteArrayInputStream(dataPart1)
        val dataPart2 = "56789".toByteArray(Charsets.UTF_8)
        val streamPart2 = ByteArrayInputStream(dataPart2)

        every { ftpClient.retrieveFileStream("/pub/movie.mp4") } returnsMany listOf(streamPart1, streamPart2)

        val uri = VfsUri("ftp://ftp-conn-1/movie.mp4")
        val randomAccess = ftpProvider.openRandomAccess(uri, "r")

        assertEquals(1000L, randomAccess.length)
        assertEquals(0L, randomAccess.position)

        val buffer = ByteArray(5)
        val read1 = randomAccess.read(buffer, 0, 5)
        assertEquals(5, read1)
        assertEquals("01234", String(buffer, Charsets.UTF_8))
        assertEquals(5L, randomAccess.position)

        // Seek forward
        randomAccess.seek(50L)
        assertEquals(50L, randomAccess.position)

        val read2 = randomAccess.read(buffer, 0, 5)
        assertEquals(5, read2)
        assertEquals("56789", String(buffer, Charsets.UTF_8))
        assertEquals(55L, randomAccess.position)

        randomAccess.close()

        verify { ftpClient.setRestartOffset(0L) }
        verify { ftpClient.setRestartOffset(50L) }
        verify { ftpClient.disconnect() }
    }

    @Test
    fun createDirectory_callsMakeDirectory() = runTest {
        every { ftpClient.makeDirectory("/pub/newfolder") } returns true

        val uri = VfsUri("ftp://ftp-conn-1/newfolder")
        val result = ftpProvider.createDirectory(uri)

        assertTrue(result)
        verify { ftpClient.makeDirectory("/pub/newfolder") }
    }

    @Test
    fun delete_fileAndDeleteDirectoryRecursive() = runTest {
        every { ftpClient.deleteFile("/pub/file.txt") } returns true
        val fileUri = VfsUri("ftp://ftp-conn-1/file.txt")
        assertTrue(ftpProvider.delete(fileUri, recursive = false))

        every { ftpClient.deleteFile("/pub/folder") } returns false
        val child = mockk<FTPFile> {
            every { name } returns "subfile.txt"
            every { isDirectory } returns false
        }
        every { ftpClient.listFiles("/pub/folder") } returns arrayOf(child)
        every { ftpClient.deleteFile("/pub/folder/subfile.txt") } returns true
        every { ftpClient.removeDirectory("/pub/folder") } returns true

        val dirUri = VfsUri("ftp://ftp-conn-1/folder")
        assertTrue(ftpProvider.delete(dirUri, recursive = true))

        verify { ftpClient.deleteFile("/pub/folder/subfile.txt") }
        verify { ftpClient.removeDirectory("/pub/folder") }
    }

    @Test
    fun rename_callsClientRename() = runTest {
        every { ftpClient.rename("/pub/old.txt", "/pub/new.txt") } returns true

        val srcUri = VfsUri("ftp://ftp-conn-1/old.txt")
        val dstUri = VfsUri("ftp://ftp-conn-1/new.txt")
        assertTrue(ftpProvider.rename(srcUri, dstUri))

        verify { ftpClient.rename("/pub/old.txt", "/pub/new.txt") }
    }

    @Test
    fun ftpsProvider_configuresPbszAndProt() = runTest {
        every { ftpsClient.listFiles("/") } returns emptyArray()

        val uri = VfsUri("ftps://ftps-conn-1/")
        val nodes = ftpsProvider.listDirectory(uri)
        assertNotNull(nodes)

        verify { ftpsClient.execPBSZ(0) }
        verify { ftpsClient.execPROT("P") }
    }

    @Test
    fun loginFailure_throwsAuthenticationRequired() = runTest {
        every { ftpClient.login(any(), any()) } returns false

        val uri = VfsUri("ftp://ftp-conn-1/")
        try {
            ftpProvider.listDirectory(uri)
            fail("Expected AuthenticationRequired exception")
        } catch (e: VfsException.AuthenticationRequired) {
            // expected
        }
    }
}
