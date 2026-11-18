package dev.scout.files.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArchiveUriTest {

    @Test
    fun testBuildAndParseArchiveUri() {
        val sourceUri = VfsUri("file:///storage/emulated/0/Download/secret.zip")
        val archiveUri = ArchiveUri.build(sourceUri, "folder/sub/document.txt")

        assertTrue(ArchiveUri.isArchiveUri(archiveUri))
        assertEquals("archive", archiveUri.scheme)

        val parsed = ArchiveUri.parse(archiveUri)
        assertNotNull(parsed)
        assertEquals(sourceUri, parsed?.first)
        assertEquals("/folder/sub/document.txt", parsed?.second)

        assertEquals("document.txt", archiveUri.name)
        val parentUri = archiveUri.parent
        assertNotNull(parentUri)
        assertEquals(ArchiveUri.build(sourceUri, "folder/sub"), parentUri)

        val rootParent = ArchiveUri.build(sourceUri, "").parent
        assertNull(rootParent)
    }

    @Test
    fun testSupportedArchiveExtensions() {
        assertTrue(ArchiveUri.isSupportedArchiveFile("archive.zip"))
        assertTrue(ArchiveUri.isSupportedArchiveFile("data.rar"))
        assertTrue(ArchiveUri.isSupportedArchiveFile("backup.7z"))
        assertTrue(ArchiveUri.isSupportedArchiveFile("linux.tar.gz"))
        assertTrue(ArchiveUri.isSupportedArchiveFile("system.tar.xz"))
    }
}
