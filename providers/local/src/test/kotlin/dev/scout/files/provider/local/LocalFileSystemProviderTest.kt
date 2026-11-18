package dev.scout.files.provider.local

import dev.scout.files.core.model.VfsUri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LocalFileSystemProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var provider: LocalFileSystemProvider

    @Before
    fun setup() {
        provider = LocalFileSystemProvider(testDispatcher)
    }

    @Test
    fun testListDirectoryAndDotfiles() = runTest(testDispatcher) {
        val root = tempFolder.root
        val regularFile = File(root, "document.txt").apply { writeText("Hello") }
        val dotFile = File(root, ".hidden_config").apply { writeText("Secret") }
        val subDir = File(root, "photos").apply { mkdir() }

        val uri = VfsUri.fromFile(root.absolutePath)
        val nodes = provider.listDirectory(uri)

        assertEquals(3, nodes.size)

        val hiddenNode = nodes.find { it.name == ".hidden_config" }
        assertNotNull(hiddenNode)
        assertTrue(hiddenNode!!.isHidden)

        val regularNode = nodes.find { it.name == "document.txt" }
        assertNotNull(regularNode)
        assertFalse(regularNode!!.isDirectory)
        assertFalse(regularNode.isHidden)
        assertEquals("txt", regularNode.extension)
        assertEquals(5L, regularNode.sizeBytes)

        val dirNode = nodes.find { it.name == "photos" }
        assertNotNull(dirNode)
        assertTrue(dirNode!!.isDirectory)
    }

    @Test
    fun testCreateDirectoryAndExists() = runTest(testDispatcher) {
        val root = tempFolder.root
        val newDirUri = VfsUri.fromFile(File(root, "new_folder").absolutePath)

        assertFalse(provider.exists(newDirUri))
        val created = provider.createDirectory(newDirUri)
        assertTrue(created)
        assertTrue(provider.exists(newDirUri))
    }

    @Test
    fun testDeleteAndRename() = runTest(testDispatcher) {
        val root = tempFolder.root
        val file = File(root, "test.txt").apply { writeText("Sample data") }
        val fileUri = VfsUri.fromFile(file.absolutePath)
        val targetUri = VfsUri.fromFile(File(root, "renamed.txt").absolutePath)

        assertTrue(provider.exists(fileUri))

        val renamed = provider.rename(fileUri, targetUri)
        assertTrue(renamed)
        assertFalse(provider.exists(fileUri))
        assertTrue(provider.exists(targetUri))

        val deleted = provider.delete(targetUri, recursive = false)
        assertTrue(deleted)
        assertFalse(provider.exists(targetUri))
    }
}
