package dev.scout.files.core.database

import dev.scout.files.core.database.dao.BookmarkDao
import dev.scout.files.core.database.dao.FileTagDao
import dev.scout.files.core.database.dao.ServerConnectionDao
import dev.scout.files.core.database.entity.BookmarkEntity
import dev.scout.files.core.database.entity.FileTagCrossRefEntity
import dev.scout.files.core.database.entity.ServerConnectionEntity
import dev.scout.files.core.database.entity.toEntity
import dev.scout.files.core.database.repository.MetadataRepository
import dev.scout.files.core.model.AuthType
import dev.scout.files.core.model.NetworkProtocol
import dev.scout.files.core.model.ServerConnection
import dev.scout.files.core.model.TagColor
import dev.scout.files.core.model.VfsUri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MetadataRepositoryTest {

    private val bookmarkDao: BookmarkDao = mockk(relaxed = true)
    private val fileTagDao: FileTagDao = mockk(relaxed = true)
    private val serverConnectionDao: ServerConnectionDao = mockk(relaxed = true)
    private lateinit var repository: MetadataRepository

    @Before
    fun setup() {
        repository = MetadataRepository(bookmarkDao, fileTagDao, serverConnectionDao)
    }

    @Test
    fun testGetBookmarks() = runTest {
        val entities = listOf(
            BookmarkEntity(uri = "file:///storage/emulated/0", title = "Home", iconKey = "home", sortOrder = 0)
        )
        every { bookmarkDao.getAllBookmarks() } returns flowOf(entities)

        val bookmarks = repository.getBookmarks().first()
        assertEquals(1, bookmarks.size)
        assertEquals("Home", bookmarks[0].title)
        assertEquals(VfsUri("file:///storage/emulated/0"), bookmarks[0].uri)
    }

    @Test
    fun testAddAndRemoveTag() = runTest {
        val uri = VfsUri("file:///data/photo.jpg")
        repository.addTag(uri, TagColor.RED)
        coVerify { fileTagDao.addTag(FileTagCrossRefEntity(uri = uri.raw, tagColor = "RED")) }

        repository.removeTag(uri, TagColor.RED)
        coVerify { fileTagDao.removeTag(uri = uri.raw, tagColor = "RED") }
    }

    @Test
    fun testGetTagsForUris() = runTest {
        val uri = VfsUri("file:///data/file1.txt")
        val entities = listOf(
            FileTagCrossRefEntity(uri = uri.raw, tagColor = "BLUE"),
            FileTagCrossRefEntity(uri = uri.raw, tagColor = "YELLOW")
        )
        every { fileTagDao.getTagsForUris(listOf(uri.raw)) } returns flowOf(entities)

        val map = repository.getTagsForUris(listOf(uri)).first()
        assertTrue(map.containsKey(uri))
        assertEquals(2, map[uri]?.size)
        assertEquals(TagColor.BLUE, map[uri]?.get(0)?.color)
        assertEquals(TagColor.YELLOW, map[uri]?.get(1)?.color)
    }

    @Test
    fun testGetServerConnections() = runTest {
        val entity = ServerConnectionEntity(
            id = "conn-1",
            name = "Test SFTP",
            protocol = "SFTP",
            host = "192.168.1.10",
            port = 22,
            username = "admin",
            authType = "PASSWORD",
            shareOrBucket = null,
            domainOrRegion = null,
            rootPath = "/",
            useTls = true,
            requireBiometric = false,
            lastConnectedMillis = 1000L
        )
        every { serverConnectionDao.getAllConnections() } returns flowOf(listOf(entity))

        val connections = repository.getServerConnections().first()
        assertEquals(1, connections.size)
        val conn = connections[0]
        assertEquals("conn-1", conn.id)
        assertEquals("Test SFTP", conn.name)
        assertEquals(NetworkProtocol.SFTP, conn.protocol)
        assertEquals("192.168.1.10", conn.host)
        assertEquals(22, conn.port)
        assertEquals("admin", conn.username)
        assertEquals(AuthType.PASSWORD, conn.authType)
        assertEquals(1000L, conn.lastConnectedMillis)
    }

    @Test
    fun testGetServerConnectionById() = runTest {
        val entity = ServerConnectionEntity(
            id = "conn-2",
            name = "Test SMB",
            protocol = "SMB",
            host = "nas.local",
            port = 445,
            username = "user",
            authType = "PASSWORD",
            shareOrBucket = "share1",
            domainOrRegion = "WORKGROUP",
            rootPath = "/docs",
            useTls = false,
            requireBiometric = true,
            lastConnectedMillis = null
        )
        coEvery { serverConnectionDao.getConnectionById("conn-2") } returns entity
        coEvery { serverConnectionDao.getConnectionById("non-existent") } returns null

        val conn = repository.getServerConnection("conn-2")
        assertNotNull(conn)
        assertEquals("conn-2", conn?.id)
        assertEquals("Test SMB", conn?.name)
        assertEquals(NetworkProtocol.SMB, conn?.protocol)
        assertEquals("share1", conn?.shareOrBucket)
        assertEquals("WORKGROUP", conn?.domainOrRegion)
        assertTrue(conn?.requireBiometric == true)

        val nullConn = repository.getServerConnection("non-existent")
        assertNull(nullConn)
    }

    @Test
    fun testSaveServerConnection() = runTest {
        val conn = ServerConnection(
            id = "conn-3",
            name = "WebDAV Nextcloud",
            protocol = NetworkProtocol.WEBDAV,
            host = "cloud.example.com",
            port = 443,
            username = "user",
            authType = AuthType.PASSWORD,
            rootPath = "/remote.php/dav/files/user/"
        )
        repository.saveServerConnection(conn)
        coVerify { serverConnectionDao.insertOrUpdate(conn.toEntity()) }
    }

    @Test
    fun testDeleteServerConnection() = runTest {
        repository.deleteServerConnection("conn-3")
        coVerify { serverConnectionDao.deleteById("conn-3") }
    }

    @Test
    fun testUpdateServerConnectionLastConnected() = runTest {
        repository.updateServerConnectionLastConnected("conn-3", 55555L)
        coVerify { serverConnectionDao.updateLastConnected("conn-3", 55555L) }
    }
}
