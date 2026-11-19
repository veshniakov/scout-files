package dev.scout.files.provider.gdrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveJsonParserTest {

    @Test
    fun parseFileList_extractsItemsCorrectly() {
        val json = """
            {
              "kind": "drive#fileList",
              "incompleteSearch": false,
              "files": [
                {
                  "id": "file_123",
                  "name": "Report 2026.pdf",
                  "mimeType": "application/pdf",
                  "size": "1048576",
                  "modifiedTime": "2026-11-19T08:00:00.000Z",
                  "parents": ["parent_root"],
                  "trashed": false
                },
                {
                  "id": "folder_456",
                  "name": "Workspace",
                  "mimeType": "application/vnd.google-apps.folder",
                  "parents": ["root"],
                  "trashed": false
                }
              ]
            }
        """.trimIndent()

        val files = GoogleDriveJsonParser.parseFileList(json)
        assertEquals(2, files.size)

        val file = files[0]
        assertEquals("file_123", file.id)
        assertEquals("Report 2026.pdf", file.name)
        assertEquals("application/pdf", file.mimeType)
        assertEquals(1048576L, file.sizeBytes)
        assertFalse(file.isFolder)
        assertFalse(file.isGoogleDoc)
        assertEquals(listOf("parent_root"), file.parents)
        assertFalse(file.trashed)

        val folder = files[1]
        assertEquals("folder_456", folder.id)
        assertEquals("Workspace", folder.name)
        assertTrue(folder.isFolder)
        assertFalse(folder.isGoogleDoc)
        assertEquals(0L, folder.sizeBytes)
    }

    @Test
    fun parseFileItem_handlesNumericSizeAndGoogleDocs() {
        val json = """
            {
              "id": "doc_999",
              "name": "My Document",
              "mimeType": "application/vnd.google-apps.document",
              "size": 0,
              "parents": ["root"]
            }
        """.trimIndent()

        val item = GoogleDriveJsonParser.parseFileItem(json)
        assertEquals("doc_999", item.id)
        assertEquals("My Document", item.name)
        assertFalse(item.isFolder)
        assertTrue(item.isGoogleDoc)
        assertEquals(0L, item.sizeBytes)
    }

    @Test
    fun parseAccessToken_extractsToken() {
        val json = """
            {
              "access_token": "ya29.a0AfH6SM...",
              "expires_in": 3599,
              "token_type": "Bearer"
            }
        """.trimIndent()

        val token = GoogleDriveJsonParser.parseAccessToken(json)
        assertEquals("ya29.a0AfH6SM...", token)
    }

    @Test
    fun parseErrorResponse_handlesBothNestedAndFlatFormats() {
        // Nested error
        val nestedJson = """
            {
              "error": {
                "code": 404,
                "message": "File not found: 1A2B3C",
                "errors": [
                  {
                    "domain": "global",
                    "reason": "notFound",
                    "message": "File not found: 1A2B3C"
                  }
                ]
              }
            }
        """.trimIndent()
        val nestedError = GoogleDriveJsonParser.parseErrorResponse(nestedJson)
        assertNotNull(nestedError)
        assertEquals(404, nestedError?.code)
        assertEquals("File not found: 1A2B3C", nestedError?.message)
        assertEquals("notFound", nestedError?.reason)

        // Flat error
        val flatJson = """
            {
              "error": "invalid_grant",
              "error_description": "Token has been expired or revoked."
            }
        """.trimIndent()
        val flatError = GoogleDriveJsonParser.parseErrorResponse(flatJson)
        assertNotNull(flatError)
        assertEquals(400, flatError?.code)
        assertEquals("Token has been expired or revoked.", flatError?.message)
        assertEquals("invalid_grant", flatError?.reason)

        // Invalid JSON
        assertNull(GoogleDriveJsonParser.parseErrorResponse("not json"))
    }

    @Test
    fun queryEscaping_escapesQuotesAndBackslashes() {
        val query = GoogleDriveJsonParser.escapeDriveQuery("Bob's folder\\2026")
        assertEquals("Bob\\'s folder\\\\2026", query)
    }

    @Test
    fun jsonEscaping_escapesSpecialCharacters() {
        val escaped = GoogleDriveJsonParser.escapeJson("Line 1\nLine 2\t\"Quotes\"\\Backslash")
        assertEquals("Line 1\\nLine 2\\t\\\"Quotes\\\"\\\\Backslash", escaped)
    }

    @Test
    fun jsonSerialization_createFolderAndMetadataJson() {
        val folderJson = GoogleDriveJsonParser.createFolderJson("New Folder", "root")
        assertEquals("""{"name":"New Folder","mimeType":"application/vnd.google-apps.folder","parents":["root"]}""", folderJson)

        val metaJson = GoogleDriveJsonParser.createFileMetadataJson("test.txt", "parent123")
        assertEquals("""{"name":"test.txt","parents":["parent123"]}""", metaJson)

        val renameJson = GoogleDriveJsonParser.createRenameJson("updated.txt")
        assertEquals("""{"name":"updated.txt"}""", renameJson)
    }
}
