package dev.scout.files.core.operations

import dev.scout.files.core.model.ArchiveUri
import dev.scout.files.core.model.VfsException
import dev.scout.files.core.model.VfsNode
import dev.scout.files.core.model.VfsUri
import dev.scout.files.core.operations.model.ConflictAction
import dev.scout.files.core.operations.model.ConflictDecision
import dev.scout.files.core.operations.model.OperationStatus
import dev.scout.files.core.vfs.ArchivePasswordStore
import dev.scout.files.core.vfs.FileSystemProvider
import dev.scout.files.core.vfs.RandomAccessContent
import dev.scout.files.core.vfs.VfsRouter
import dev.scout.files.provider.archive.ArchiveFileSystemProvider
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.lingala.zip4j.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class FileOperationManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var tempDir: File
    private lateinit var vfsRouter: VfsRouter
    private lateinit var passwordStore: ArchivePasswordStore
    private lateinit var manager: FileOperationManager

    private class TestFileProvider : FileSystemProvider {
        override val scheme = "file"

        override suspend fun listDirectory(uri: VfsUri): List<VfsNode> {
            val file = File(uri.path)
            val children = file.listFiles() ?: emptyArray()
            return children.map { child ->
                VfsNode(
                    uri = VfsUri.fromFile(child.absolutePath),
                    name = child.name,
                    isDirectory = child.isDirectory,
                    sizeBytes = if (child.isDirectory) 0L else child.length(),
                    lastModifiedMillis = child.lastModified()
                )
            }
        }

        override suspend fun getMetadata(uri: VfsUri): VfsNode {
            val file = File(uri.path)
            if (!file.exists()) throw VfsException.NotFound(uri)
            return VfsNode(
                uri = uri,
                name = file.name,
                isDirectory = file.isDirectory,
                sizeBytes = if (file.isDirectory) 0L else file.length(),
                lastModifiedMillis = file.lastModified()
            )
        }

        override suspend fun exists(uri: VfsUri): Boolean = File(uri.path).exists()

        override suspend fun openInputStream(uri: VfsUri): InputStream = FileInputStream(File(uri.path))

        override suspend fun openOutputStream(uri: VfsUri, append: Boolean): OutputStream {
            val f = File(uri.path)
            f.parentFile?.mkdirs()
            return FileOutputStream(f, append)
        }

        override suspend fun openRandomAccess(uri: VfsUri, mode: String): RandomAccessContent {
            throw UnsupportedOperationException()
        }

        override suspend fun createDirectory(uri: VfsUri): Boolean = File(uri.path).mkdirs()

        override suspend fun delete(uri: VfsUri, recursive: Boolean): Boolean {
            val f = File(uri.path)
            return if (recursive) f.deleteRecursively() else f.delete()
        }

        override suspend fun rename(sourceUri: VfsUri, targetUri: VfsUri): Boolean {
            return File(sourceUri.path).renameTo(File(targetUri.path))
        }

        override suspend fun copyTo(
            sourceUri: VfsUri,
            targetUri: VfsUri,
            onProgress: ((Long, Long) -> Unit)?
        ): Boolean = false
    }

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("scout_ops_test").toFile()
        passwordStore = ArchivePasswordStore()
        val routerProvider = Provider<VfsRouter> { vfsRouter }
        val archiveProvider = ArchiveFileSystemProvider(passwordStore, routerProvider, testDispatcher)
        val fileProvider = TestFileProvider()
        vfsRouter = VfsRouter(setOf(fileProvider, archiveProvider), testDispatcher)
        manager = FileOperationManager(vfsRouter, passwordStore, testDispatcher)
    }

    @Test
    fun testCopyStreamingOperation() = runTest(testDispatcher) {
        val srcFile = File(tempDir, "source.txt").apply { writeText("Hello Scout Operations") }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }

        val srcUri = VfsUri.fromFile(srcFile.absolutePath)
        val destDirUri = VfsUri.fromFile(destFolder.absolutePath)

        val taskId = manager.enqueueCopy(listOf(srcUri), destDirUri)
        advanceUntilIdle()

        val task = manager.tasks.value.first { it.id == taskId }
        assertEquals(OperationStatus.COMPLETED, task.status)

        val copiedFile = File(destFolder, "source.txt")
        assertTrue(copiedFile.exists())
        assertEquals("Hello Scout Operations", copiedFile.readText())
    }

    @Test
    fun testConflictOverwrite() = runTest(testDispatcher) {
        val srcFile = File(tempDir, "doc.txt").apply { writeText("New Content") }
        val destFolder = File(tempDir, "dest_conflict_ow").apply { mkdirs() }
        val existing = File(destFolder, "doc.txt").apply { writeText("Old Content") }

        val srcUri = VfsUri.fromFile(srcFile.absolutePath)
        val destDirUri = VfsUri.fromFile(destFolder.absolutePath)

        val taskId = manager.enqueueCopy(listOf(srcUri), destDirUri)
        advanceTimeBy(100)

        // Conflict should be active
        val conflict = manager.activeConflict.value
        assertNotNull(conflict)
        assertEquals(taskId, conflict?.taskId)

        // Choose OVERWRITE
        manager.resolveConflict(ConflictDecision(ConflictAction.OVERWRITE, applyToAll = false))
        advanceUntilIdle()

        assertEquals("New Content", existing.readText())
        val task = manager.tasks.value.first { it.id == taskId }
        assertEquals(OperationStatus.COMPLETED, task.status)
    }

    @Test
    fun testConflictSkip() = runTest(testDispatcher) {
        val srcFile = File(tempDir, "doc.txt").apply { writeText("New Content") }
        val destFolder = File(tempDir, "dest_conflict_sk").apply { mkdirs() }
        val existing = File(destFolder, "doc.txt").apply { writeText("Preserve Content") }

        val srcUri = VfsUri.fromFile(srcFile.absolutePath)
        val destDirUri = VfsUri.fromFile(destFolder.absolutePath)

        val taskId = manager.enqueueCopy(listOf(srcUri), destDirUri)
        advanceTimeBy(100)

        val conflict = manager.activeConflict.value
        assertNotNull(conflict)

        // Choose SKIP
        manager.resolveConflict(ConflictDecision(ConflictAction.SKIP, applyToAll = false))
        advanceUntilIdle()

        assertEquals("Preserve Content", existing.readText())
        val task = manager.tasks.value.first { it.id == taskId }
        assertEquals(OperationStatus.COMPLETED, task.status)
    }

    @Test
    fun testConflictKeepBoth() = runTest(testDispatcher) {
        val srcFile = File(tempDir, "report.pdf").apply { writeText("Report V2") }
        val destFolder = File(tempDir, "dest_conflict_kb").apply { mkdirs() }
        val existing = File(destFolder, "report.pdf").apply { writeText("Report V1") }

        val srcUri = VfsUri.fromFile(srcFile.absolutePath)
        val destDirUri = VfsUri.fromFile(destFolder.absolutePath)

        val taskId = manager.enqueueCopy(listOf(srcUri), destDirUri)
        advanceTimeBy(100)

        val conflict = manager.activeConflict.value
        assertNotNull(conflict)

        // Choose KEEP_BOTH
        manager.resolveConflict(ConflictDecision(ConflictAction.KEEP_BOTH, applyToAll = false))
        advanceUntilIdle()

        val originalFile = File(destFolder, "report.pdf")
        val uniqueFile = File(destFolder, "report (1).pdf")

        assertTrue(originalFile.exists())
        assertEquals("Report V1", originalFile.readText())

        assertTrue(uniqueFile.exists())
        assertEquals("Report V2", uniqueFile.readText())
    }

    @Test
    fun testCompressToEncryptedZip() = runTest(testDispatcher) {
        val file1 = File(tempDir, "pass_test.txt").apply { writeText("Protected payload") }
        val destFolder = File(tempDir, "zip_out").apply { mkdirs() }
        val password = "StrongZipPassword456"

        val taskId = manager.enqueueCompress(
            sources = listOf(VfsUri.fromFile(file1.absolutePath)),
            destinationDir = VfsUri.fromFile(destFolder.absolutePath),
            archiveName = "secured.zip",
            password = password
        )
        advanceUntilIdle()

        val task = manager.tasks.value.first { it.id == taskId }
        assertEquals(OperationStatus.COMPLETED, task.status)

        val zipFile = File(destFolder, "secured.zip")
        assertTrue(zipFile.exists())

        // Verify with Zip4j that it's encrypted and readable with password
        val zf = ZipFile(zipFile, password.toCharArray())
        assertTrue(zf.isEncrypted)
        val header = zf.getFileHeader("pass_test.txt")
        assertNotNull(header)
        assertTrue(header.isEncrypted)

        val stream = zf.getInputStream(header)
        val content = stream.bufferedReader().use { it.readText() }
        assertEquals("Protected payload", content)
    }

    @Test
    fun testPauseAndCancel() = runTest(testDispatcher) {
        val srcFile = File(tempDir, "huge.bin").apply { writeBytes(ByteArray(1024 * 1024)) }
        val destFolder = File(tempDir, "dest_pause").apply { mkdirs() }

        val taskId = manager.enqueueCopy(
            listOf(VfsUri.fromFile(srcFile.absolutePath)),
            VfsUri.fromFile(destFolder.absolutePath)
        )

        manager.pause(taskId)
        assertEquals(OperationStatus.PAUSED, manager.tasks.value.first { it.id == taskId }.status)

        manager.cancel(taskId)
        assertEquals(OperationStatus.CANCELLED, manager.tasks.value.first { it.id == taskId }.status)
    }

    @Test
    fun testExtractWithConflictOverwrite() = runTest(testDispatcher) {
        val zipFile = File(tempDir, "archive_ow.zip")
        val fileInZip = File(tempDir, "data.txt").apply { writeText("Zip Data Content") }
        ZipFile(zipFile).addFile(fileInZip)

        val destFolder = File(tempDir, "extract_dest_ow").apply { mkdirs() }
        val existing = File(destFolder, "data.txt").apply { writeText("Existing Disk Data") }

        val taskId = manager.enqueueExtract(
            VfsUri.fromFile(zipFile.absolutePath),
            VfsUri.fromFile(destFolder.absolutePath)
        )
        advanceTimeBy(100)

        // Conflict should trigger for data.txt
        val conflict = manager.activeConflict.value
        assertNotNull(conflict)
        assertEquals("data.txt", conflict?.targetUri?.name)

        manager.resolveConflict(ConflictDecision(ConflictAction.OVERWRITE, applyToAll = false))
        advanceUntilIdle()

        val task = manager.tasks.value.first { it.id == taskId }
        assertEquals(OperationStatus.COMPLETED, task.status)
        assertEquals("Zip Data Content", existing.readText())
    }

    @Test
    fun testExtractWithConflictSkip() = runTest(testDispatcher) {
        val zipFile = File(tempDir, "archive_sk.zip")
        val fileInZip = File(tempDir, "data_sk.txt").apply { writeText("Zip Skip Content") }
        ZipFile(zipFile).addFile(fileInZip)

        val destFolder = File(tempDir, "extract_dest_sk").apply { mkdirs() }
        val existing = File(destFolder, "data_sk.txt").apply { writeText("Preserved Disk Data") }

        val taskId = manager.enqueueExtract(
            VfsUri.fromFile(zipFile.absolutePath),
            VfsUri.fromFile(destFolder.absolutePath)
        )
        advanceTimeBy(100)

        val conflict = manager.activeConflict.value
        assertNotNull(conflict)

        manager.resolveConflict(ConflictDecision(ConflictAction.SKIP, applyToAll = false))
        advanceUntilIdle()

        val task = manager.tasks.value.first { it.id == taskId }
        assertEquals(OperationStatus.COMPLETED, task.status)
        assertEquals("Preserved Disk Data", existing.readText())
    }

    @Test
    fun testExtractWithConflictKeepBoth() = runTest(testDispatcher) {
        val zipFile = File(tempDir, "archive_kb.zip")
        val fileInZip = File(tempDir, "data_kb.txt").apply { writeText("Zip KeepBoth Content") }
        ZipFile(zipFile).addFile(fileInZip)

        val destFolder = File(tempDir, "extract_dest_kb").apply { mkdirs() }
        val existing = File(destFolder, "data_kb.txt").apply { writeText("Existing KB Data") }

        val taskId = manager.enqueueExtract(
            VfsUri.fromFile(zipFile.absolutePath),
            VfsUri.fromFile(destFolder.absolutePath)
        )
        advanceTimeBy(100)

        val conflict = manager.activeConflict.value
        assertNotNull(conflict)

        manager.resolveConflict(ConflictDecision(ConflictAction.KEEP_BOTH, applyToAll = false))
        advanceUntilIdle()

        val task = manager.tasks.value.first { it.id == taskId }
        assertEquals(OperationStatus.COMPLETED, task.status)
        assertEquals("Existing KB Data", existing.readText())

        val uniqueFile = File(destFolder, "data_kb (1).txt")
        assertTrue(uniqueFile.exists())
        assertEquals("Zip KeepBoth Content", uniqueFile.readText())
    }

    @Test
    fun testExtractSingleFileFromInsideArchive() = runTest(testDispatcher) {
        val zipFile = File(tempDir, "archive_single.zip")
        val fileInZip = File(tempDir, "single_entry.txt").apply { writeText("Single File Content") }
        ZipFile(zipFile).addFile(fileInZip)

        val destFolder = File(tempDir, "extract_dest_single").apply { mkdirs() }
        val internalUri = ArchiveUri.build(VfsUri.fromFile(zipFile.absolutePath), "single_entry.txt")

        val taskId = manager.enqueueExtract(internalUri, VfsUri.fromFile(destFolder.absolutePath))
        advanceUntilIdle()

        val task = manager.tasks.value.first { it.id == taskId }
        assertEquals(OperationStatus.COMPLETED, task.status)

        val extracted = File(destFolder, "single_entry.txt")
        assertTrue(extracted.exists())
        assertEquals("Single File Content", extracted.readText())
    }
}
