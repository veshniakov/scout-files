package dev.scout.files.provider.webdav

import dev.scout.files.core.database.repository.MetadataRepository
import dev.scout.files.core.model.AuthType
import dev.scout.files.core.model.NetworkProtocol
import dev.scout.files.core.model.ServerConnection
import dev.scout.files.core.model.ServerCredentials
import dev.scout.files.core.model.VfsUri
import dev.scout.files.core.security.CredentialVault
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class S3FileSystemProviderTest {

    private lateinit var mockWebServer: MockWebServer
    private val metadataRepository: MetadataRepository = mockk(relaxed = true)
    private val credentialVault: CredentialVault = mockk(relaxed = true)
    private lateinit var provider: S3FileSystemProvider

    private lateinit var connection: ServerConnection

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        connection = ServerConnection(
            id = "s3-conn-1",
            name = "MinIO / S3 Storage",
            protocol = NetworkProtocol.S3,
            host = mockWebServer.hostName,
            port = mockWebServer.port,
            username = "AKIAIOSFODNN7EXAMPLE",
            authType = AuthType.ACCESS_KEY,
            shareOrBucket = "test-bucket",
            domainOrRegion = "us-east-1",
            useTls = false
        )

        coEvery { metadataRepository.getServerConnection("s3-conn-1") } returns connection
        coEvery { credentialVault.getCredentials("s3-conn-1") } returns ServerCredentials(
            s3SecretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        )

        provider = S3FileSystemProvider(
            metadataRepository = metadataRepository,
            credentialVault = credentialVault,
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun listDirectory_parsesCommonPrefixesAndContentsWithSigV4() = runTest {
        val listXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Name>test-bucket</Name>
                <Prefix></Prefix>
                <KeyCount>2</KeyCount>
                <MaxKeys>1000</MaxKeys>
                <Delimiter>/</Delimiter>
                <IsTruncated>false</IsTruncated>
                <Contents>
                    <Key>report.pdf</Key>
                    <LastModified>2026-11-18T23:33:19.000Z</LastModified>
                    <Size>40960</Size>
                </Contents>
                <CommonPrefixes>
                    <Prefix>archives/</Prefix>
                </CommonPrefixes>
            </ListBucketResult>
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/xml")
                .setBody(listXml)
        )

        val uri = VfsUri("s3://s3-conn-1/test-bucket/")
        val nodes = provider.listDirectory(uri)

        assertEquals(2, nodes.size)
        assertEquals("archives", nodes[0].name)
        assertTrue(nodes[0].isDirectory)

        assertEquals("report.pdf", nodes[1].name)
        assertFalse(nodes[1].isDirectory)
        assertEquals(40960L, nodes[1].sizeBytes)

        val recorded = mockWebServer.takeRequest()
        assertEquals("GET", recorded.method)
        assertTrue(recorded.path!!.contains("list-type=2"))
        val auth = recorded.getHeader("Authorization")
        assertNotNull(auth)
        assertTrue(auth!!.startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE"))
    }

    @Test
    fun getMetadata_headObject_returnsMetadata() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "102400")
                .setHeader("Last-Modified", "Wed, 18 Nov 2026 23:33:19 GMT")
        )

        val uri = VfsUri("s3://s3-conn-1/test-bucket/data.bin")
        val node = provider.getMetadata(uri)

        assertEquals("data.bin", node.name)
        assertFalse(node.isDirectory)
        assertEquals(102400L, node.sizeBytes)

        val recorded = mockWebServer.takeRequest()
        assertEquals("HEAD", recorded.method)
        assertEquals("/test-bucket/data.bin", recorded.path)
    }

    @Test
    fun exists_returnsTrueForExistingObject() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "100")
        )

        val uri = VfsUri("s3://s3-conn-1/test-bucket/item.json")
        assertTrue(provider.exists(uri))
    }

    @Test
    fun openInputStream_fetchesObjectStream() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("S3 Payload Stream")
        )

        val uri = VfsUri("s3://s3-conn-1/test-bucket/file.txt")
        val stream = provider.openInputStream(uri)
        val content = String(stream.readBytes(), Charsets.UTF_8)

        assertEquals("S3 Payload Stream", content)
        val recorded = mockWebServer.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/test-bucket/file.txt", recorded.path)
    }

    @Test
    fun openOutputStream_putsObject() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val uri = VfsUri("s3://s3-conn-1/test-bucket/upload.dat")
        val stream = provider.openOutputStream(uri, append = false)
        stream.write("Binary content".toByteArray(Charsets.UTF_8))
        stream.close()

        val recorded = mockWebServer.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/test-bucket/upload.dat", recorded.path)
        assertEquals("Binary content", recorded.body.readUtf8())
        assertNotNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun openRandomAccess_readsByteRange() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "50000")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setBody("SEEKED_BYTES")
        )

        val uri = VfsUri("s3://s3-conn-1/test-bucket/video.mp4")
        val randomAccess = provider.openRandomAccess(uri, "r")
        assertEquals(50000L, randomAccess.length)

        randomAccess.seek(1024L)
        val buffer = ByteArray(12)
        val read = randomAccess.read(buffer, 0, 12)

        assertEquals(12, read)
        assertEquals("SEEKED_BYTES", String(buffer, Charsets.UTF_8))

        mockWebServer.takeRequest() // HEAD
        val getReq = mockWebServer.takeRequest()
        assertEquals("GET", getReq.method)
        assertEquals("bytes=1024-", getReq.getHeader("Range"))
    }

    @Test
    fun createDirectory_putsEmptyObjectWithTrailingSlash() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val uri = VfsUri("s3://s3-conn-1/test-bucket/NewFolder")
        val result = provider.createDirectory(uri)

        assertTrue(result)
        val recorded = mockWebServer.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/test-bucket/NewFolder/", recorded.path)
    }

    @Test
    fun delete_deletesObject() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(204))

        val uri = VfsUri("s3://s3-conn-1/test-bucket/old.txt")
        val result = provider.delete(uri, recursive = false)

        assertTrue(result)
        val recorded = mockWebServer.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/test-bucket/old.txt", recorded.path)
    }

    @Test
    fun rename_performsCopyObjectAndDeleteObject() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200)) // CopyObject
        mockWebServer.enqueue(MockResponse().setResponseCode(204)) // DeleteObject

        val srcUri = VfsUri("s3://s3-conn-1/test-bucket/src.txt")
        val dstUri = VfsUri("s3://s3-conn-1/test-bucket/dst.txt")
        val result = provider.rename(srcUri, dstUri)

        assertTrue(result)
        val copyReq = mockWebServer.takeRequest()
        assertEquals("PUT", copyReq.method)
        assertEquals("/test-bucket/dst.txt", copyReq.path)
        assertEquals("/test-bucket/src.txt", copyReq.getHeader("x-amz-copy-source"))

        val deleteReq = mockWebServer.takeRequest()
        assertEquals("DELETE", deleteReq.method)
        assertEquals("/test-bucket/src.txt", deleteReq.path)
    }
}
