package dev.scout.files.core.vfs

import dev.scout.files.core.model.VfsException
import dev.scout.files.core.model.VfsNode
import dev.scout.files.core.model.VfsUri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class VfsRouterTest {

    private val testDispatcher = StandardTestDispatcher()

    /**
     * In-memory FakeFileSystemProvider for testing routing and transfers.
     */
    private class FakeProvider(
        override val scheme: String
    ) : FileSystemProvider {
        val files = ConcurrentHashMap<String, ByteArray>()
        val directories = ConcurrentHashMap.newKeySet<String>().apply { add("/") }

        override suspend fun listDirectory(uri: VfsUri): List<VfsNode> {
            val path = uri.path
            val result = mutableListOf<VfsNode>()
            files.keys.filter { it.startsWith(path) && it != path }.forEach { filePath ->
                val rel = filePath.removePrefix(path).removePrefix("/")
                val name = rel.substringBefore('/')
                val isDir = rel.contains('/')
                val childUri = uri.resolve(name)
                val node = VfsNode(
                    uri = childUri,
                    name = name,
                    isDirectory = isDir,
                    sizeBytes = if (isDir) 0L else (files[filePath]?.size?.toLong() ?: 0L)
                )
                if (result.none { it.name == name }) {
                    result.add(node)
                }
            }
            return result
        }

        override suspend fun getMetadata(uri: VfsUri): VfsNode {
            val content = files[uri.path]
            val isDir = directories.contains(uri.path)
            if (content == null && !isDir) {
                throw VfsException.NotFound(uri)
            }
            return VfsNode(
                uri = uri,
                name = uri.name,
                isDirectory = isDir,
                sizeBytes = content?.size?.toLong() ?: 0L
            )
        }

        override suspend fun exists(uri: VfsUri): Boolean {
            return files.containsKey(uri.path) || directories.contains(uri.path)
        }

        override suspend fun openInputStream(uri: VfsUri): InputStream {
            val data = files[uri.path] ?: throw VfsException.NotFound(uri)
            return ByteArrayInputStream(data)
        }

        override suspend fun openOutputStream(uri: VfsUri, append: Boolean): OutputStream {
            return object : ByteArrayOutputStream() {
                override fun close() {
                    super.close()
                    val existing = if (append) files[uri.path] ?: ByteArray(0) else ByteArray(0)
                    files[uri.path] = existing + toByteArray()
                }
            }
        }

        override suspend fun openRandomAccess(uri: VfsUri, mode: String): RandomAccessContent {
            throw UnsupportedOperationException()
        }

        override suspend fun createDirectory(uri: VfsUri): Boolean {
            directories.add(uri.path)
            return true
        }

        override suspend fun delete(uri: VfsUri, recursive: Boolean): Boolean {
            files.remove(uri.path)
            directories.remove(uri.path)
            return true
        }

        override suspend fun rename(sourceUri: VfsUri, targetUri: VfsUri): Boolean {
            val data = files.remove(sourceUri.path) ?: return false
            files[targetUri.path] = data
            return true
        }

        override suspend fun copyTo(
            sourceUri: VfsUri,
            targetUri: VfsUri,
            onProgress: ((Long, Long) -> Unit)?
        ): Boolean {
            val data = files[sourceUri.path] ?: return false
            files[targetUri.path] = data.copyOf()
            onProgress?.invoke(data.size.toLong(), data.size.toLong())
            return true
        }
    }

    @Test
    fun testRoutingByScheme() = runTest(testDispatcher) {
        val fileProvider = FakeProvider("file")
        val sftpProvider = FakeProvider("sftp")
        val router = VfsRouter(setOf(fileProvider, sftpProvider), testDispatcher)

        assertEquals(setOf("file", "sftp"), router.supportedSchemes())
        assertEquals("file", router.getProvider("file").scheme)
        assertEquals("sftp", router.getProvider(VfsUri("sftp://example.com/file.txt")).scheme)
    }

    @Test
    fun testUnsupportedSchemeThrows() = runTest(testDispatcher) {
        val router = VfsRouter(emptySet(), testDispatcher)

        try {
            router.getProvider("smb")
            fail("Expected VfsException.UnsupportedScheme")
        } catch (e: VfsException.UnsupportedScheme) {
            assertEquals("smb", e.uri.scheme)
        }
    }

    @Test
    fun testCrossProtocolCopyFile() = runTest(testDispatcher) {
        val sourceProvider = FakeProvider("source")
        val targetProvider = FakeProvider("target")
        val router = VfsRouter(setOf(sourceProvider, targetProvider), testDispatcher)

        val testPayload = "Hello Scout cross-protocol copy!".toByteArray(Charsets.UTF_8)
        sourceProvider.files["/documents/hello.txt"] = testPayload

        val sourceUri = VfsUri("source:///documents/hello.txt")
        val targetUri = VfsUri("target:///backup/hello.txt")

        var progressCalls = 0
        var lastCopied = 0L
        val success = router.copy(sourceUri, targetUri) { copied, total ->
            progressCalls++
            lastCopied = copied
            assertEquals(testPayload.size.toLong(), total)
        }

        assertTrue(success)
        assertTrue(progressCalls > 0)
        assertEquals(testPayload.size.toLong(), lastCopied)
        assertArrayEquals(testPayload, targetProvider.files["/backup/hello.txt"])
    }

    @Test
    fun testRenameAcrossProvidersDelegatesToCrossCopyAndDelete() = runTest(testDispatcher) {
        val sourceProvider = FakeProvider("p1")
        val targetProvider = FakeProvider("p2")
        val router = VfsRouter(setOf(sourceProvider, targetProvider), testDispatcher)

        val data = "Move me!".toByteArray()
        sourceProvider.files["/data.bin"] = data

        val src = VfsUri("p1:///data.bin")
        val dst = VfsUri("p2:///data.bin")

        val moved = router.rename(src, dst)
        assertTrue(moved)
        assertFalse(sourceProvider.files.containsKey("/data.bin"))
        assertArrayEquals(data, targetProvider.files["/data.bin"])
    }
}
