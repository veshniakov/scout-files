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

class WebDavFileSystemProviderTest {

    private lateinit var mockWebServer: MockWebServer
    private val metadataRepository: MetadataRepository = mockk(relaxed = true)
    private val credentialVault: CredentialVault = mockk(relaxed = true)
    private lateinit var provider: WebDavFileSystemProvider

    private lateinit var connection: ServerConnection

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        connection = ServerConnection(
            id = "webdav-conn-1",
            name = "Nextcloud WebDAV",
            protocol = NetworkProtocol.WEBDAV,
            host = mockWebServer.hostName,
            port = mockWebServer.port,
            username = "alice",
            authType = AuthType.PASSWORD,
            rootPath = "/remote.php/dav/files/{username}/",
            useTls = false
        )

        coEvery { metadataRepository.getServerConnection("webdav-conn-1") } returns connection
        coEvery { credentialVault.getCredentials("webdav-conn-1") } returns ServerCredentials(password = "app-password")

        provider = WebDavFileSystemProvider(
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
    fun listDirectory_parsesPropfindAndFiltersSelfReference() = runTest {
        val propfindResponseXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/remote.php/dav/files/alice/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/></d:resourcetype>
                            <d:getlastmodified>Wed, 18 Nov 2026 23:33:19 GMT</d:getlastmodified>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
                <d:response>
                    <d:href>/remote.php/dav/files/alice/Documents/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/></d:resourcetype>
                            <d:displayname>Documents</d:displayname>
                            <d:getlastmodified>Wed, 18 Nov 2026 23:33:19 GMT</d:getlastmodified>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
                <d:response>
                    <d:href>/remote.php/dav/files/alice/photo.jpg</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype/>
                            <d:getcontentlength>54321</d:getcontentlength>
                            <d:displayname>photo.jpg</d:displayname>
                            <d:getlastmodified>Wed, 18 Nov 2026 23:33:19 GMT</d:getlastmodified>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml; charset=utf-8")
                .setBody(propfindResponseXml)
        )

        val uri = VfsUri("webdav://webdav-conn-1/")
        val nodes = provider.listDirectory(uri)

        assertEquals(2, nodes.size)
        assertEquals("Documents", nodes[0].name)
        assertTrue(nodes[0].isDirectory)

        assertEquals("photo.jpg", nodes[1].name)
        assertFalse(nodes[1].isDirectory)
        assertEquals(54321L, nodes[1].sizeBytes)

        val recorded = mockWebServer.takeRequest()
        assertEquals("PROPFIND", recorded.method)
        assertEquals("1", recorded.getHeader("Depth"))
        assertNotNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun getMetadata_depth0Propfind_returnsCorrectAttributes() = runTest {
        val propfindXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/remote.php/dav/files/alice/data.csv</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype/>
                            <d:getcontentlength>8192</d:getcontentlength>
                            <d:displayname>data.csv</d:displayname>
                            <d:getlastmodified>Wed, 18 Nov 2026 23:33:19 GMT</d:getlastmodified>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml; charset=utf-8")
                .setBody(propfindXml)
        )

        val uri = VfsUri("webdav://webdav-conn-1/data.csv")
        val node = provider.getMetadata(uri)

        assertEquals("data.csv", node.name)
        assertFalse(node.isDirectory)
        assertEquals(8192L, node.sizeBytes)

        val recorded = mockWebServer.takeRequest()
        assertEquals("PROPFIND", recorded.method)
        assertEquals("0", recorded.getHeader("Depth"))
    }

    @Test
    fun exists_returnsTrueOn207AndFalseOn404() = runTest {
        val propfindXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/remote.php/dav/files/alice/exists.txt</d:href>
                    <d:propstat><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(207).setBody(propfindXml))
        assertTrue(provider.exists(VfsUri("webdav://webdav-conn-1/exists.txt")))

        mockWebServer.enqueue(MockResponse().setResponseCode(404))
        assertFalse(provider.exists(VfsUri("webdav://webdav-conn-1/missing.txt")))
    }

    @Test
    fun openInputStream_readsContentSuccessfully() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("WebDAV file content")
        )

        val uri = VfsUri("webdav://webdav-conn-1/test.txt")
        val stream = provider.openInputStream(uri)
        val content = String(stream.readBytes(), Charsets.UTF_8)

        assertEquals("WebDAV file content", content)
        val recorded = mockWebServer.takeRequest()
        assertEquals("GET", recorded.method)
    }

    @Test
    fun openOutputStream_executesPutRequest() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(201))

        val uri = VfsUri("webdav://webdav-conn-1/upload.txt")
        val stream = provider.openOutputStream(uri, append = false)
        stream.write("New upload".toByteArray(Charsets.UTF_8))
        stream.close()

        val recorded = mockWebServer.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("New upload", recorded.body.readUtf8())
    }

    @Test
    fun openRandomAccess_sendsRangeHeader() = runTest {
        val propfindXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/remote.php/dav/files/alice/video.mp4</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype/>
                            <d:getcontentlength>100000</d:getcontentlength>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(207).setBody(propfindXml))
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 500-999/100000")
                .setBody("CHUNK_DATA")
        )

        val uri = VfsUri("webdav://webdav-conn-1/video.mp4")
        val randomAccess = provider.openRandomAccess(uri, "r")
        assertEquals(100000L, randomAccess.length)

        randomAccess.seek(500L)
        val buffer = ByteArray(10)
        val read = randomAccess.read(buffer, 0, 10)

        assertEquals(10, read)
        assertEquals("CHUNK_DATA", String(buffer, Charsets.UTF_8))

        mockWebServer.takeRequest() // PROPFIND
        val getReq = mockWebServer.takeRequest()
        assertEquals("GET", getReq.method)
        assertEquals("bytes=500-", getReq.getHeader("Range"))
    }

    @Test
    fun createDirectory_sendsMkcol() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(201))

        val uri = VfsUri("webdav://webdav-conn-1/NewFolder")
        val result = provider.createDirectory(uri)

        assertTrue(result)
        val recorded = mockWebServer.takeRequest()
        assertEquals("MKCOL", recorded.method)
    }

    @Test
    fun delete_sendsDelete() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(204))

        val uri = VfsUri("webdav://webdav-conn-1/trash.txt")
        val result = provider.delete(uri, recursive = false)

        assertTrue(result)
        val recorded = mockWebServer.takeRequest()
        assertEquals("DELETE", recorded.method)
    }

    @Test
    fun rename_sendsMoveWithDestination() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(201))

        val srcUri = VfsUri("webdav://webdav-conn-1/source.txt")
        val dstUri = VfsUri("webdav://webdav-conn-1/target.txt")
        val result = provider.rename(srcUri, dstUri)

        assertTrue(result)
        val recorded = mockWebServer.takeRequest()
        assertEquals("MOVE", recorded.method)
        assertEquals("T", recorded.getHeader("Overwrite"))
        assertTrue(recorded.getHeader("Destination")!!.endsWith("/remote.php/dav/files/alice/target.txt"))
    }
}
