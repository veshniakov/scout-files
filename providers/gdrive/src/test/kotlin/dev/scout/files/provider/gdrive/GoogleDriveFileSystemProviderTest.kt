package dev.scout.files.provider.gdrive

import dev.scout.files.core.database.repository.MetadataRepository
import dev.scout.files.core.model.AuthType
import dev.scout.files.core.model.NetworkProtocol
import dev.scout.files.core.model.ServerConnection
import dev.scout.files.core.model.ServerCredentials
import dev.scout.files.core.model.VfsException
import dev.scout.files.core.model.VfsUri
import dev.scout.files.core.security.InMemoryCredentialVault
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.test.assertFailsWith

class GoogleDriveFileSystemProviderTest {

    private lateinit var mockWebServer: MockWebServer
    private val metadataRepository: MetadataRepository = mockk(relaxed = true)
    private val inMemoryCredentialVault = InMemoryCredentialVault()
    private lateinit var provider: GoogleDriveFileSystemProvider
    private lateinit var connection: ServerConnection

    @Before
    fun setUp() = runTest {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        connection = ServerConnection(
            id = "gdrive-conn-1",
            name = "My Google Drive",
            protocol = NetworkProtocol.GDRIVE,
            host = mockWebServer.hostName,
            port = mockWebServer.port,
            username = "user@gmail.com",
            authType = AuthType.OAUTH2,
            rootPath = "/",
            useTls = false
        )

        coEvery { metadataRepository.getServerConnection("gdrive-conn-1") } returns connection

        inMemoryCredentialVault.storeCredentials(
            "gdrive-conn-1",
            ServerCredentials(
                oauthAccessToken = "test-token",
                oauthRefreshToken = "valid_refresh_token_222",
                oauthClientId = "scout_client_id_333"
            )
        )

        provider = GoogleDriveFileSystemProvider(
            metadataRepository = metadataRepository,
            credentialVault = inMemoryCredentialVault,
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.Unconfined,
            baseUrl = mockWebServer.url("/").toString().removeSuffix("/"),
            tokenUrl = mockWebServer.url("/token").toString()
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun listDirectory_parsesJsonResponse_returnsMappedVfsNodes() = runTest {
        val jsonResponse = """
            {
              "kind": "drive#fileList",
              "incompleteSearch": false,
              "files": [
                {
                  "id": "folder_1",
                  "name": "Documents",
                  "mimeType": "application/vnd.google-apps.folder",
                  "modifiedTime": "2026-11-19T06:00:00.000Z",
                  "parents": ["root"]
                },
                {
                  "id": "file_1",
                  "name": "photo.jpg",
                  "mimeType": "image/jpeg",
                  "size": "54321",
                  "modifiedTime": "2026-11-19T06:30:00.000Z",
                  "parents": ["root"]
                },
                {
                  "id": "sheet_1",
                  "name": "Budget Plan",
                  "mimeType": "application/vnd.google-apps.spreadsheet",
                  "modifiedTime": "2026-11-19T07:00:00.000Z",
                  "parents": ["root"]
                }
              ]
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(jsonResponse)
        )

        val nodes = provider.listDirectory(VfsUri("gdrive://gdrive-conn-1/"))

        assertEquals(3, nodes.size)

        // Folder
        assertEquals("Documents", nodes[0].name)
        assertTrue(nodes[0].isDirectory)
        assertEquals(0L, nodes[0].sizeBytes)
        assertEquals("application/vnd.google-apps.folder", nodes[0].mimeType)

        // Binary file
        assertEquals("photo.jpg", nodes[1].name)
        assertFalse(nodes[1].isDirectory)
        assertEquals(54321L, nodes[1].sizeBytes)
        assertEquals("image/jpeg", nodes[1].mimeType)
        assertEquals(Instant.parse("2026-11-19T06:30:00.000Z").toEpochMilli(), nodes[1].lastModifiedMillis)
        assertEquals("jpg", nodes[1].extension)

        // Workspace doc
        assertEquals("Budget Plan", nodes[2].name)
        assertFalse(nodes[2].isDirectory)
        assertEquals(0L, nodes[2].sizeBytes)
        assertEquals("application/vnd.google-apps.spreadsheet", nodes[2].mimeType)
        assertEquals(Instant.parse("2026-11-19T07:00:00.000Z").toEpochMilli(), nodes[2].lastModifiedMillis)

        val recorded = mockWebServer.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
        assertTrue(recorded.path!!.startsWith("/drive/v3/files"))
        assertTrue(recorded.path!!.contains("trashed"))
    }

    @Test
    fun getMetadata_and_exists_forRootFoldersAndFiles() = runTest {
        // Scenario 2a: Root directory
        val rootUri = VfsUri("gdrive://gdrive-conn-1/")
        assertTrue(provider.exists(rootUri))
        val rootNode = provider.getMetadata(rootUri)
        assertTrue(rootNode.isDirectory)
        assertEquals("", rootNode.name)

        // Scenario 2b: Existing subfolder
        val folderQueryResponse = """
            {
              "files": [
                {
                  "id": "folder_docs_id",
                  "name": "Docs",
                  "mimeType": "application/vnd.google-apps.folder",
                  "modifiedTime": "2026-11-19T01:00:00.000Z"
                }
              ]
            }
        """.trimIndent()
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(folderQueryResponse)
        )

        val subfolderUri = VfsUri("gdrive://gdrive-conn-1/Docs")
        assertTrue(provider.exists(subfolderUri))
        val subfolderNode = provider.getMetadata(subfolderUri)
        assertEquals("Docs", subfolderNode.name)
        assertTrue(subfolderNode.isDirectory)

        // Scenario 2c: Non-existent file
        val emptyQueryResponse = """{"files": []}"""
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(emptyQueryResponse))
        val missingUri = VfsUri("gdrive://gdrive-conn-1/missing.txt")
        assertFalse(provider.exists(missingUri))

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(emptyQueryResponse))
        assertFailsWith<VfsException.NotFound> {
            provider.getMetadata(missingUri)
        }
    }

    @Test
    fun hierarchicalPathResolution_traversesSegmentsAndCachesPathToIdMappings() = runTest {
        val targetUri = VfsUri("gdrive://gdrive-conn-1/Folder/Subfolder/file.txt")

        // Step 1: Cold cache -> 3 segment searches
        val folderResponse = """{"files": [{"id": "id_folder", "name": "Folder", "mimeType": "application/vnd.google-apps.folder"}]}"""
        val subfolderResponse = """{"files": [{"id": "id_subfolder", "name": "Subfolder", "mimeType": "application/vnd.google-apps.folder"}]}"""
        val fileResponse = """{"files": [{"id": "id_file", "name": "file.txt", "mimeType": "text/plain", "size": "100"}]}"""

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(folderResponse))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(subfolderResponse))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(fileResponse))

        val node = provider.getMetadata(targetUri)
        assertEquals("file.txt", node.name)
        assertEquals(100L, node.sizeBytes)

        val req1 = mockWebServer.takeRequest()
        val req2 = mockWebServer.takeRequest()
        val req3 = mockWebServer.takeRequest()

        assertTrue(req1.path!!.contains("root") && req1.path!!.contains("Folder"))
        assertTrue(req2.path!!.contains("id_folder") && req2.path!!.contains("Subfolder"))
        assertTrue(req3.path!!.contains("id_subfolder") && req3.path!!.contains("file.txt"))

        // Step 2: Warm cache -> ID is cached in pathCache
        provider.itemCache.remove("gdrive-conn-1:/Folder/Subfolder/file.txt")
        val directFileMeta = """{"id": "id_file", "name": "file.txt", "mimeType": "text/plain", "size": "100"}"""
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(directFileMeta))

        assertTrue(provider.exists(targetUri)) // 0 requests
        val warmNode = provider.getMetadata(targetUri) // 1 direct metadata request
        assertEquals("file.txt", warmNode.name)

        val directReq = mockWebServer.takeRequest()
        assertTrue(directReq.path!!.startsWith("/drive/v3/files/id_file"))
        assertFalse(directReq.path!!.contains("in+parents") || directReq.path!!.contains("in parents"))

        // Step 3: Sibling lookup under cached subfolder
        val siblingUri = VfsUri("gdrive://gdrive-conn-1/Folder/Subfolder/sibling.txt")
        val siblingResponse = """{"files": [{"id": "id_sibling", "name": "sibling.txt", "mimeType": "text/plain", "size": "200"}]}"""
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(siblingResponse))

        val siblingNode = provider.getMetadata(siblingUri)
        assertEquals("sibling.txt", siblingNode.name)

        val siblingReq = mockWebServer.takeRequest()
        assertTrue(siblingReq.path!!.contains("id_subfolder") && siblingReq.path!!.contains("sibling.txt"))
        assertFalse(siblingReq.path!!.contains("Folder"))
    }

    @Test
    fun oauthTokenRefresh_onHttp401_updatesVaultAndRetriesRequestSuccessfully() = runTest {
        inMemoryCredentialVault.storeCredentials(
            "gdrive-conn-1",
            ServerCredentials(
                oauthAccessToken = "expired_token_111",
                oauthRefreshToken = "valid_refresh_token_222",
                oauthClientId = "scout_client_id_333"
            )
        )

        // 1. Initial 401
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error": {"code": 401, "message": "Invalid Credentials"}}""")
        )

        // 2. Token refresh 200
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("""{"access_token": "fresh_access_token_999", "expires_in": 3599, "token_type": "Bearer"}""")
        )

        // 3. Retry 200
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("""{"files": [{"id": "file_refreshed_id", "name": "data.csv", "mimeType": "text/csv", "size": "500"}]}""")
        )

        val nodes = provider.listDirectory(VfsUri("gdrive://gdrive-conn-1/"))
        assertEquals(1, nodes.size)
        assertEquals("data.csv", nodes[0].name)

        val updatedCreds = inMemoryCredentialVault.getCredentials("gdrive-conn-1")
        assertNotNull(updatedCreds)
        assertEquals("fresh_access_token_999", updatedCreds?.oauthAccessToken)
        assertEquals("valid_refresh_token_222", updatedCreds?.oauthRefreshToken)

        val req1 = mockWebServer.takeRequest()
        assertEquals("GET", req1.method)
        assertEquals("Bearer expired_token_111", req1.getHeader("Authorization"))

        val req2 = mockWebServer.takeRequest()
        assertEquals("POST", req2.method)
        assertEquals("/token", req2.path)
        val refreshBody = req2.body.readUtf8()
        assertTrue(refreshBody.contains("grant_type=refresh_token"))
        assertTrue(refreshBody.contains("refresh_token=valid_refresh_token_222"))
        assertTrue(refreshBody.contains("client_id=scout_client_id_333"))

        val req3 = mockWebServer.takeRequest()
        assertEquals("GET", req3.method)
        assertEquals("Bearer fresh_access_token_999", req3.getHeader("Authorization"))
    }

    @Test
    fun openInputStream_downloadsBinaryFileWithAltMedia() = runTest {
        val targetUri = VfsUri("gdrive://gdrive-conn-1/archive.zip")

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"files": [{"id": "zip_file_123", "name": "archive.zip", "mimeType": "application/zip", "size": "100"}]}""")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/zip")
                .setBody("ZIP_BINARY_DATA_PAYLOAD_BYTES")
        )

        val stream = provider.openInputStream(targetUri)
        val content = String(stream.readBytes(), Charsets.UTF_8)
        assertEquals("ZIP_BINARY_DATA_PAYLOAD_BYTES", content)

        val resolveReq = mockWebServer.takeRequest()
        assertTrue(resolveReq.path!!.contains("archive.zip"))

        val downloadReq = mockWebServer.takeRequest()
        assertEquals("GET", downloadReq.method)
        assertEquals("/drive/v3/files/zip_file_123?alt=media", downloadReq.path)
        assertEquals("Bearer test-token", downloadReq.getHeader("Authorization"))
    }

    @Test
    fun openRandomAccess_seekAndRead_verifiesHttpRangeHeaders() = runTest {
        val targetUri = VfsUri("gdrive://gdrive-conn-1/video.mp4")

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"files": [{"id": "video_file_456", "name": "video.mp4", "mimeType": "video/mp4", "size": "100000"}]}""")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-999/100000")
                .setBody("FIRST_1000_BYTES_HEADER")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 50000-50999/100000")
                .setBody("SEEKED_PAYLOAD_MIDDLE")
        )

        val rac = provider.openRandomAccess(targetUri, "r")
        assertEquals(100000L, rac.length)
        assertEquals(0L, rac.position)

        val buffer1 = ByteArray(23)
        val read1 = rac.read(buffer1, 0, 23)
        assertEquals(23, read1)
        assertEquals("FIRST_1000_BYTES_HEADER", String(buffer1, Charsets.UTF_8))
        assertEquals(23L, rac.position)

        rac.seek(50000L)
        assertEquals(50000L, rac.position)

        val buffer2 = ByteArray(21)
        val read2 = rac.read(buffer2, 0, 21)
        assertEquals(21, read2)
        assertEquals("SEEKED_PAYLOAD_MIDDLE", String(buffer2, Charsets.UTF_8))

        rac.close()

        val reqResolve = mockWebServer.takeRequest()
        assertTrue(reqResolve.path!!.contains("video.mp4"))

        val req1 = mockWebServer.takeRequest()
        assertEquals("GET", req1.method)
        assertTrue(req1.path!!.startsWith("/drive/v3/files/video_file_456?alt=media"))
        assertEquals("bytes=0-", req1.getHeader("Range"))

        val req2 = mockWebServer.takeRequest()
        assertEquals("GET", req2.method)
        assertTrue(req2.path!!.startsWith("/drive/v3/files/video_file_456?alt=media"))
        assertEquals("bytes=50000-", req2.getHeader("Range"))
    }

    @Test
    fun openInputStream_googleDocsAndSheets_exportsToPdfAndXlsx() = runTest {
        // Scenario 7a: Google Document export to PDF
        val docUri = VfsUri("gdrive://gdrive-conn-1/MyReport")
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"files": [{"id": "doc_id_101", "name": "MyReport", "mimeType": "application/vnd.google-apps.document"}]}""")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/pdf")
                .setBody("%PDF-1.4 Mock Export Content")
        )

        val docStream = provider.openInputStream(docUri)
        val docContent = String(docStream.readBytes(), Charsets.UTF_8)
        assertTrue(docContent.startsWith("%PDF-1.4"))

        mockWebServer.takeRequest() // resolve request
        val docExportReq = mockWebServer.takeRequest()
        assertEquals("GET", docExportReq.method)
        assertEquals("/drive/v3/files/doc_id_101/export?mimeType=application%2Fpdf", docExportReq.path)

        // Scenario 7b: Google Spreadsheet export to XLSX
        val sheetUri = VfsUri("gdrive://gdrive-conn-1/Sheet1")
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"files": [{"id": "sheet_id_202", "name": "Sheet1", "mimeType": "application/vnd.google-apps.spreadsheet"}]}""")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .setBody("MOCK_XLSX_STREAM_BYTES")
        )

        val sheetStream = provider.openInputStream(sheetUri)
        val sheetContent = String(sheetStream.readBytes(), Charsets.UTF_8)
        assertEquals("MOCK_XLSX_STREAM_BYTES", sheetContent)

        mockWebServer.takeRequest() // resolve request
        val sheetExportReq = mockWebServer.takeRequest()
        assertTrue(sheetExportReq.path!!.contains("/drive/v3/files/sheet_id_202/export?mimeType="))
        assertTrue(sheetExportReq.path!!.contains("spreadsheetml.sheet"))
    }

    @Test
    fun createDirectory_upload_rename_delete_invalidatesPathCacheEntries() = runTest {
        // 8a: Create Directory
        val newFolderResponse = """{"id": "new_folder_id", "name": "Projects", "mimeType": "application/vnd.google-apps.folder"}"""
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(newFolderResponse))

        assertTrue(provider.createDirectory(VfsUri("gdrive://gdrive-conn-1/Projects")))

        val createReq = mockWebServer.takeRequest()
        assertEquals("POST", createReq.method)
        assertEquals("/drive/v3/files", createReq.path)
        val createBody = createReq.body.readUtf8()
        assertTrue(createBody.contains(""""name":"Projects""""))
        assertTrue(createBody.contains(""""parents":["root"]"""))

        // 8b: File Upload via openOutputStream
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"files": []}"""))
        val newFileResponse = """{"id": "new_file_id", "name": "upload.txt", "mimeType": "text/plain", "size": "11"}"""
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(newFileResponse))

        val outputStream = provider.openOutputStream(VfsUri("gdrive://gdrive-conn-1/upload.txt"))
        outputStream.write("Hello Drive".toByteArray(Charsets.UTF_8))
        outputStream.close()

        val checkExistingReq = mockWebServer.takeRequest()
        assertTrue(checkExistingReq.path!!.contains("upload.txt"))

        val uploadReq = mockWebServer.takeRequest()
        assertEquals("POST", uploadReq.method)
        assertTrue(uploadReq.path!!.contains("/drive/v3/files"))
        val uploadBody = uploadReq.body.readUtf8()
        assertTrue(uploadBody.contains("Hello Drive"))

        // 8c: Rename (PATCH)
        provider.setCachedFileId("gdrive-conn-1", "/old.txt", "file_rename_id")
        val renameResponse = """{"id": "file_rename_id", "name": "new.txt"}"""
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(renameResponse))

        assertTrue(provider.rename(VfsUri("gdrive://gdrive-conn-1/old.txt"), VfsUri("gdrive://gdrive-conn-1/new.txt")))

        val renameReq = mockWebServer.takeRequest()
        assertEquals("PATCH", renameReq.method)
        assertEquals("/drive/v3/files/file_rename_id", renameReq.path)
        val renameBody = renameReq.body.readUtf8()
        assertTrue(renameBody.contains(""""name":"new.txt""""))

        assertNull(provider.getCachedFileId("gdrive-conn-1", "/old.txt"))
        assertEquals("file_rename_id", provider.getCachedFileId("gdrive-conn-1", "/new.txt"))

        // 8d: Delete (DELETE)
        provider.setCachedFileId("gdrive-conn-1", "/trash.txt", "file_delete_id")
        mockWebServer.enqueue(MockResponse().setResponseCode(204))

        assertTrue(provider.delete(VfsUri("gdrive://gdrive-conn-1/trash.txt"), recursive = false))

        val deleteReq = mockWebServer.takeRequest()
        assertEquals("DELETE", deleteReq.method)
        assertEquals("/drive/v3/files/file_delete_id", deleteReq.path)
        assertNull(provider.getCachedFileId("gdrive-conn-1", "/trash.txt"))
    }

    @Test
    fun errorHandling_mapsHttpErrorCodesToScoutVfsExceptions() = runTest {
        // 9a: HTTP 404 Not Found
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"error": {"code": 404, "message": "File not found: abc", "errors": [{"reason": "notFound"}]}}""")
        )
        val missingUri = VfsUri("gdrive://gdrive-conn-1/missing.txt")
        assertFailsWith<VfsException.NotFound> {
            provider.getMetadata(missingUri)
        }

        // 9b: HTTP 403 Forbidden / Permission Denied
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error": {"code": 403, "message": "The user does not have sufficient permissions", "errors": [{"reason": "forbidden"}]}}""")
        )
        val forbiddenUri = VfsUri("gdrive://gdrive-conn-1/forbidden_folder")
        assertFailsWith<VfsException.PermissionDenied> {
            provider.listDirectory(forbiddenUri)
        }

        // 9c: HTTP 409 Conflict / Already Exists
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody("""{"error": {"code": 409, "message": "Conflict"}}""")
        )
        val existingUri = VfsUri("gdrive://gdrive-conn-1/existing_folder")
        assertFailsWith<VfsException.AlreadyExists> {
            provider.createDirectory(existingUri)
        }

        // 9d: HTTP 500 Internal Server Error
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error": {"code": 500, "message": "Backend Error"}}""")
        )
        val errorUri = VfsUri("gdrive://gdrive-conn-1/error_folder")
        assertFailsWith<VfsException.Io> {
            provider.listDirectory(errorUri)
        }

        // 9e: Missing connection ID in URI
        val badUri = VfsUri("gdrive:///")
        assertFailsWith<VfsException.NotFound> {
            provider.listDirectory(badUri)
        }

        // 9f: Unknown connection ID in Database
        coEvery { metadataRepository.getServerConnection("unknown-conn") } returns null
        val unknownUri = VfsUri("gdrive://unknown-conn/")
        assertFailsWith<VfsException.NotFound> {
            provider.listDirectory(unknownUri)
        }

        // 9g: Missing credentials in Vault
        coEvery { metadataRepository.getServerConnection("no-creds-conn") } returns ServerConnection(
            id = "no-creds-conn",
            name = "No Creds",
            protocol = NetworkProtocol.GDRIVE,
            host = "localhost"
        )
        inMemoryCredentialVault.deleteCredentials("no-creds-conn")
        val noCredsUri = VfsUri("gdrive://no-creds-conn/")
        assertFailsWith<VfsException.AuthenticationRequired> {
            provider.listDirectory(noCredsUri)
        }
    }

    @Test
    fun openRandomAccess_zeroByteFile_returnsEofWithoutIssuingRangeRequestOrFailingOn416() = runTest {
        val zeroByteFileUri = VfsUri("gdrive://gdrive-conn-1/empty.txt")
        val zeroByteFileJson = """{"files": [{"id": "id_empty_file", "name": "empty.txt", "mimeType": "text/plain", "size": "0"}]}"""
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(zeroByteFileJson))

        // If a Range request were issued, this 416 response would cause a crash
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(416)
                .setBody("""{"error": {"code": 416, "message": "Requested range not satisfiable"}}""")
        )

        val rac = provider.openRandomAccess(zeroByteFileUri, "r")
        assertEquals(0L, rac.length)
        val buffer = ByteArray(1024)
        val bytesRead = rac.read(buffer, 0, buffer.size)
        assertEquals(-1, bytesRead)
        rac.close()

        // Verify only the metadata query / direct lookup was issued, NO range request was made
        val req = mockWebServer.takeRequest()
        assertTrue(req.path!!.contains("empty.txt"))
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun randomAccessContent_zeroLengthRead_returnsZeroWithoutIssuingHttpRequest() = runTest {
        val fileUri = VfsUri("gdrive://gdrive-conn-1/sample.txt")
        val fileJson = """{"files": [{"id": "id_sample", "name": "sample.txt", "mimeType": "text/plain", "size": "1000"}]}"""
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(fileJson))

        val rac = provider.openRandomAccess(fileUri, "r")
        assertEquals(1000L, rac.length)
        val buffer = ByteArray(1024)
        val bytesRead = rac.read(buffer, 0, 0)
        assertEquals(0, bytesRead)
        rac.close()

        val req = mockWebServer.takeRequest() // Metadata resolution
        assertTrue(req.path!!.contains("sample.txt"))
        assertEquals(1, mockWebServer.requestCount) // No Range HTTP request issued
    }

    @Test
    fun openOutputStream_onUncachedExistingFile_sendsPatchInsteadOfPostMultipart() = runTest {
        val fileUri = VfsUri("gdrive://gdrive-conn-1/cold_existing.txt")

        // 1. Search for cold_existing.txt in root
        val searchResponse = """{"files": [{"id": "id_existing_123", "name": "cold_existing.txt", "mimeType": "text/plain", "size": "50"}]}"""
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(searchResponse))

        // 2. PATCH upload response
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": "id_existing_123", "name": "cold_existing.txt"}"""))

        val outputStream = provider.openOutputStream(fileUri)
        outputStream.write("updated content".toByteArray(Charsets.UTF_8))
        outputStream.close()

        val req1 = mockWebServer.takeRequest() // Resolution query
        assertTrue(req1.path!!.contains("cold_existing.txt"))

        val req2 = mockWebServer.takeRequest() // Upload request
        assertEquals("PATCH", req2.method)
        assertTrue(req2.path!!.startsWith("/upload/drive/v3/files/id_existing_123?uploadType=media"))

        // itemCache must be invalidated after PATCH
        assertNull(provider.itemCache["gdrive-conn-1:/cold_existing.txt"])
    }

    @Test
    fun invalidateCache_withRootPath_removesAllCachedKeysUnderConnection() = runTest {
        val connId = "gdrive-conn-1"
        provider.setCachedFileId(connId, "/", "root")
        provider.setCachedFileId(connId, "/folderA", "id_folderA")
        provider.setCachedFileId(connId, "/folderA/subfolderB", "id_subfolderB")
        provider.setCachedFileId(connId, "/folderA/file1.txt", "id_file1")
        provider.setCachedFileId(connId, "/file2.txt", "id_file2")

        val dummyItem = DriveFileItem(id = "id_file1", name = "file1.txt", mimeType = "text/plain", sizeBytes = 100L)
        provider.itemCache["$connId:/"] = DriveFileItem(id = "root", name = "", mimeType = "application/vnd.google-apps.folder")
        provider.itemCache["$connId:/folderA"] = DriveFileItem(id = "id_folderA", name = "folderA", mimeType = "application/vnd.google-apps.folder")
        provider.itemCache["$connId:/folderA/file1.txt"] = dummyItem

        // Invalidate root path "/"
        provider.invalidateCache(connId, "/")

        assertNull(provider.pathCache["$connId:/"])
        assertNull(provider.getCachedFileId(connId, "/folderA"))
        assertNull(provider.getCachedFileId(connId, "/folderA/subfolderB"))
        assertNull(provider.getCachedFileId(connId, "/folderA/file1.txt"))
        assertNull(provider.getCachedFileId(connId, "/file2.txt"))

        assertNull(provider.itemCache["$connId:/"])
        assertNull(provider.itemCache["$connId:/folderA"])
        assertNull(provider.itemCache["$connId:/folderA/file1.txt"])
    }

    @Test
    fun getMetadata_repeatedCalls_readFromItemCacheWithoutDuplicateNetworkCalls() = runTest {
        val fileUri = VfsUri("gdrive://gdrive-conn-1/cached_file.txt")
        val fileResponse = """{"files": [{"id": "id_cached_file", "name": "cached_file.txt", "mimeType": "text/plain", "size": "42"}]}"""
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(fileResponse))

        // First call populates cache
        val node1 = provider.getMetadata(fileUri)
        assertEquals("cached_file.txt", node1.name)
        assertEquals(42L, node1.sizeBytes)

        // Item must remain in itemCache
        assertNotNull(provider.itemCache["gdrive-conn-1:/cached_file.txt"])

        // Second and third calls must read from itemCache without any additional HTTP requests
        val node2 = provider.getMetadata(fileUri)
        val node3 = provider.getMetadata(fileUri)
        assertEquals("cached_file.txt", node2.name)
        assertEquals("cached_file.txt", node3.name)

        assertEquals(1, mockWebServer.requestCount)
        val req = mockWebServer.takeRequest()
        assertTrue(req.path!!.contains("cached_file.txt"))
    }
}
