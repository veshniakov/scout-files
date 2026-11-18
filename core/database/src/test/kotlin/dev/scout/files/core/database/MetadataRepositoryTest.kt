package dev.scout.files.core.database

import dev.scout.files.core.database.dao.BookmarkDao
import dev.scout.files.core.database.dao.FileTagDao
import dev.scout.files.core.database.entity.BookmarkEntity
import dev.scout.files.core.database.entity.FileTagCrossRefEntity
import dev.scout.files.core.database.repository.MetadataRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MetadataRepositoryTest {

    private val bookmarkDao: BookmarkDao = mockk(relaxed = true)
    private val fileTagDao: FileTagDao = mockk(relaxed = true)
    private lateinit var repository: MetadataRepository

    @Before
    fun setup() {
        repository = MetadataRepository(bookmarkDao, fileTagDao)
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
}
