package dev.scout.files.provider.archive

import dev.scout.files.core.model.ArchiveUri
import dev.scout.files.core.model.VfsException
import dev.scout.files.core.model.VfsUri
import dev.scout.files.core.vfs.ArchivePasswordStore
import dev.scout.files.core.vfs.VfsRouter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.inject.Provider

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveFileSystemProviderTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var tempDir: File
    private lateinit var passwordStore: ArchivePasswordStore
    private lateinit var provider: ArchiveFileSystemProvider
    private lateinit var vfsRouter: VfsRouter

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("scout_archive_test").toFile()
        passwordStore = ArchivePasswordStore()
        val routerProvider = Provider<VfsRouter> { vfsRouter }
        provider = ArchiveFileSystemProvider(passwordStore, routerProvider, testDispatcher)
        vfsRouter = VfsRouter(setOf(provider), testDispatcher)
    }

    @Test
    fun testRegularZipInspectionAndRead() = runTest(testDispatcher) {
        val zipFile = File(tempDir, "sample.zip")
        val sampleText = "Hello from zip!"
        val fileToAdd = File(tempDir, "greeting.txt").apply { writeText(sampleText) }

        val subDir = File(tempDir, "nested").apply { mkdirs() }
        val nestedFile = File(subDir, "inner.txt").apply { writeText("Inner content") }

        ZipFile(zipFile).apply {
            addFile(fileToAdd)
            addFolder(subDir)
        }

        val baseUri = ArchiveUri.build(VfsUri.fromFile(zipFile.absolutePath), "/")
        val rootChildren = provider.listDirectory(baseUri)

        assertTrue(rootChildren.any { it.name == "greeting.txt" && !it.isDirectory })
        assertTrue(rootChildren.any { it.name == "nested" && it.isDirectory })

        val greetingUri = ArchiveUri.build(VfsUri.fromFile(zipFile.absolutePath), "greeting.txt")
        val meta = provider.getMetadata(greetingUri)
        assertEquals("greeting.txt", meta.name)
        assertFalse(meta.isDirectory)
        assertEquals(sampleText.toByteArray().size.toLong(), meta.sizeBytes)

        val content = provider.openInputStream(greetingUri).bufferedReader().use { it.readText() }
        assertEquals(sampleText, content)

        // Read nested file
        val nestedUri = ArchiveUri.build(VfsUri.fromFile(zipFile.absolutePath), "nested/inner.txt")
        val nestedContent = provider.openInputStream(nestedUri).bufferedReader().use { it.readText() }
        assertEquals("Inner content", nestedContent)
    }

    @Test
    fun testEncryptedZipWithAes256Password() = runTest(testDispatcher) {
        val zipFile = File(tempDir, "secret.zip")
        val secretContent = "Top Secret 2026 Scout Data"
        val secretFile = File(tempDir, "secret.txt").apply { writeText(secretContent) }
        val password = "SuperSecretPassword123"

        val zipParams = ZipParameters().apply {
            compressionMethod = CompressionMethod.DEFLATE
            isEncryptFiles = true
            encryptionMethod = EncryptionMethod.AES
            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
        }

        ZipFile(zipFile, password.toCharArray()).apply {
            addFile(secretFile, zipParams)
        }

        val fileUri = VfsUri.fromFile(zipFile.absolutePath)
        val secretUri = ArchiveUri.build(fileUri, "secret.txt")

        // 1. Reading listDirectory or entry without password should throw AuthenticationRequired
        try {
            provider.listDirectory(ArchiveUri.build(fileUri, "/"))
            fail("Expected AuthenticationRequired for encrypted zip")
        } catch (e: VfsException.AuthenticationRequired) {
            assertNotNull(e)
        }

        try {
            provider.openInputStream(secretUri)
            fail("Expected AuthenticationRequired when opening encrypted entry without password")
        } catch (e: VfsException.AuthenticationRequired) {
            assertNotNull(e)
        }

        // 2. Set password in store and try again
        passwordStore.setPassword(fileUri, password)

        val children = provider.listDirectory(ArchiveUri.build(fileUri, "/"))
        assertTrue(children.any { it.name == "secret.txt" })

        val stream = provider.openInputStream(secretUri)
        val read = stream.bufferedReader().use { it.readText() }
        assertEquals(secretContent, read)
    }

    @Test
    fun testTarGzInspectionAndRead() = runTest(testDispatcher) {
        val tarGzFile = File(tempDir, "archive.tar.gz")
        val content = "Linux tar.gz test file content"

        TarArchiveOutputStream(GzipCompressorOutputStream(tarGzFile.outputStream().buffered())).use { tos ->
            val data = content.toByteArray()
            val entry = TarArchiveEntry("docs/readme.txt").apply {
                size = data.size.toLong()
            }
            tos.putArchiveEntry(entry)
            tos.write(data)
            tos.closeArchiveEntry()
        }

        val baseUri = ArchiveUri.build(VfsUri.fromFile(tarGzFile.absolutePath), "/")
        val rootChildren = provider.listDirectory(baseUri)
        assertTrue(rootChildren.any { it.name == "docs" && it.isDirectory })

        val readmeUri = ArchiveUri.build(VfsUri.fromFile(tarGzFile.absolutePath), "docs/readme.txt")
        val read = provider.openInputStream(readmeUri).bufferedReader().use { it.readText() }
        assertEquals(content, read)
    }

    @Test
    fun testTarWithDotSlashEntries() = runTest(testDispatcher) {
        val tarFile = File(tempDir, "prefixed.tar")
        val content = "Testing dot-slash prefixed tar entry"

        TarArchiveOutputStream(tarFile.outputStream().buffered()).use { tos ->
            // Root dot entry
            tos.putArchiveEntry(TarArchiveEntry("./").apply { isDirectory })
            tos.closeArchiveEntry()

            // File entry with ./ prefix
            val data = content.toByteArray()
            val fileEntry = TarArchiveEntry("./test_file.txt").apply {
                size = data.size.toLong()
            }
            tos.putArchiveEntry(fileEntry)
            tos.write(data)
            tos.closeArchiveEntry()
        }

        val baseUri = ArchiveUri.build(VfsUri.fromFile(tarFile.absolutePath), "/")
        val rootChildren = provider.listDirectory(baseUri)

        // Root directory should not contain '.'
        assertFalse(rootChildren.any { it.name == "." })
        assertTrue(rootChildren.any { it.name == "test_file.txt" && !it.isDirectory })

        val fileUri = ArchiveUri.build(VfsUri.fromFile(tarFile.absolutePath), "test_file.txt")
        val read = provider.openInputStream(fileUri).bufferedReader().use { it.readText() }
        assertEquals(content, read)
    }

    @Test
    fun testReadOnlyFileSystemRestrictions() = runTest(testDispatcher) {
        val zipFile = File(tempDir, "readonly.zip")
        val innerFile = File(tempDir, "inner.txt").apply { writeText("Sample") }
        ZipFile(zipFile).addFile(innerFile)

        val baseUri = ArchiveUri.build(VfsUri.fromFile(zipFile.absolutePath), "inner.txt")

        try {
            provider.createDirectory(baseUri)
            fail("Expected ReadOnlyFileSystem for createDirectory")
        } catch (e: VfsException.ReadOnlyFileSystem) {
            assertNotNull(e)
        }

        try {
            provider.delete(baseUri, recursive = false)
            fail("Expected ReadOnlyFileSystem for delete")
        } catch (e: VfsException.ReadOnlyFileSystem) {
            assertNotNull(e)
        }

        try {
            provider.rename(baseUri, baseUri)
            fail("Expected ReadOnlyFileSystem for rename")
        } catch (e: VfsException.ReadOnlyFileSystem) {
            assertNotNull(e)
        }

        try {
            provider.openOutputStream(baseUri, append = false)
            fail("Expected ReadOnlyFileSystem for openOutputStream")
        } catch (e: VfsException.ReadOnlyFileSystem) {
            assertNotNull(e)
        }
    }

    @Test
    fun testZipWrongPasswordChallenge() = runTest(testDispatcher) {
        val zipFile = File(tempDir, "locked.zip")
        val content = "Encrypted data"
        val inner = File(tempDir, "locked.txt").apply { writeText(content) }
        val correctPassword = "CorrectPassword123"

        val zipParams = ZipParameters().apply {
            compressionMethod = CompressionMethod.DEFLATE
            isEncryptFiles = true
            encryptionMethod = EncryptionMethod.AES
            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
        }

        ZipFile(zipFile, correctPassword.toCharArray()).addFile(inner, zipParams)
        val fileUri = VfsUri.fromFile(zipFile.absolutePath)
        val entryUri = ArchiveUri.build(fileUri, "locked.txt")

        // Set wrong password
        passwordStore.setPassword(fileUri, "WrongPassword999")

        try {
            provider.openInputStream(entryUri)
            fail("Expected AuthenticationRequired on wrong password")
        } catch (e: VfsException.AuthenticationRequired) {
            assertNotNull(e)
        }

        // Set correct password and verify read succeeds
        passwordStore.setPassword(fileUri, correctPassword)
        val read = provider.openInputStream(entryUri).bufferedReader().use { it.readText() }
        assertEquals(content, read)
    }
}
