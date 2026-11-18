package dev.scout.files.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VfsUriTest {

    @Test
    fun testFileUriParsing() {
        val uri = VfsUri("file:///sdcard/Download/test.pdf")
        assertEquals("file", uri.scheme)
        assertEquals("/sdcard/Download/test.pdf", uri.path)
        assertEquals("test.pdf", uri.name)
        assertEquals(listOf("sdcard", "Download", "test.pdf"), uri.segments)
        assertEquals(VfsUri("file:///sdcard/Download"), uri.parent)
    }

    @Test
    fun testNetworkUriParsing() {
        val uri = VfsUri("smb://192.168.1.100:445/shared/documents")
        assertEquals("smb", uri.scheme)
        assertEquals("192.168.1.100", uri.host)
        assertEquals(445, uri.port)
        assertEquals("/shared/documents", uri.path)
        assertEquals("documents", uri.name)
        assertEquals(VfsUri("smb://192.168.1.100:445/shared"), uri.parent)
    }

    @Test
    fun testRootUri() {
        val uri = VfsUri("file:///")
        assertEquals("file", uri.scheme)
        assertEquals("/", uri.path)
        assertEquals("", uri.name)
        assertNull(uri.parent)
    }

    @Test
    fun testResolve() {
        val base = VfsUri("sftp://nas.local/media")
        val child = base.resolve("music/track.mp3")
        assertEquals(VfsUri("sftp://nas.local/media/music/track.mp3"), child)
        assertEquals("track.mp3", child.name)
    }
}
