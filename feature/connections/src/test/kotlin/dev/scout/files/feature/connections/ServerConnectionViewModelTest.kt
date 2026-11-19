package dev.scout.files.feature.connections

import dev.scout.files.core.database.repository.MetadataRepository
import dev.scout.files.core.model.AuthType
import dev.scout.files.core.model.NetworkProtocol
import dev.scout.files.core.model.ServerConnection
import dev.scout.files.core.model.ServerCredentials
import dev.scout.files.core.model.VfsNode
import dev.scout.files.core.model.VfsUri
import dev.scout.files.core.model.WebDavPreset
import dev.scout.files.core.security.CredentialVault
import dev.scout.files.core.vfs.VfsRouter
import dev.scout.files.feature.connections.model.ConnectionTestState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ServerConnectionViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val metadataRepository: MetadataRepository = mockk(relaxed = true)
    private val credentialVault: CredentialVault = mockk(relaxed = true)
    private val vfsRouter: VfsRouter = mockk(relaxed = true)

    private lateinit var viewModel: ServerConnectionViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = ServerConnectionViewModel(
            metadataRepository = metadataRepository,
            credentialVault = credentialVault,
            vfsRouter = vfsRouter,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `default state has SFTP protocol and port 22`() {
        val state = viewModel.formState.value
        assertEquals(NetworkProtocol.SFTP, state.protocol)
        assertEquals("22", state.portText)
        assertEquals("/", state.rootPath)
        assertEquals(AuthType.PASSWORD, state.authType)
        assertFalse(state.requireBiometric)
        assertEquals(ConnectionTestState.Idle, state.testState)
    }

    @Test
    fun `init with existing connection populates state correctly`() = runTest(testDispatcher) {
        val existing = ServerConnection(
            id = "conn_123",
            name = "My NAS",
            protocol = NetworkProtocol.SMB,
            host = "192.168.1.10",
            port = 445,
            username = "admin",
            authType = AuthType.PASSWORD,
            shareOrBucket = "media",
            domainOrRegion = "HOME",
            rootPath = "/videos",
            useTls = false,
            requireBiometric = true
        )
        val credentials = ServerCredentials(password = "secret123")
        coEvery { credentialVault.getCredentials("conn_123") } returns credentials

        viewModel.init(existing)
        advanceUntilIdle()

        val state = viewModel.formState.value
        assertEquals("conn_123", state.id)
        assertEquals("My NAS", state.name)
        assertEquals(NetworkProtocol.SMB, state.protocol)
        assertEquals("192.168.1.10", state.host)
        assertEquals("445", state.portText)
        assertEquals("admin", state.username)
        assertEquals("secret123", state.password)
        assertEquals("media", state.shareOrBucket)
        assertEquals("HOME", state.domainOrRegion)
        assertEquals("/videos", state.rootPath)
        assertTrue(state.requireBiometric)
        assertTrue(state.isEditMode)
    }

    @Test
    fun `updateProtocol adjusts default port and TLS settings`() {
        viewModel.updateProtocol(NetworkProtocol.FTP)
        assertEquals("21", viewModel.formState.value.portText)
        assertFalse(viewModel.formState.value.useTls)

        viewModel.updateProtocol(NetworkProtocol.FTPS)
        assertEquals("990", viewModel.formState.value.portText)
        assertTrue(viewModel.formState.value.useTls)

        viewModel.updateProtocol(NetworkProtocol.SMB)
        assertEquals("445", viewModel.formState.value.portText)
        assertEquals("shared", viewModel.formState.value.shareOrBucket)

        viewModel.updateProtocol(NetworkProtocol.S3)
        assertEquals("443", viewModel.formState.value.portText)
        assertEquals(AuthType.ACCESS_KEY, viewModel.formState.value.authType)

        viewModel.updateProtocol(NetworkProtocol.GDRIVE)
        assertEquals(AuthType.OAUTH2, viewModel.formState.value.authType)
    }

    @Test
    fun `updateWebDavPreset applies Yandex Disk presets`() {
        viewModel.updateProtocol(NetworkProtocol.WEBDAV)
        viewModel.updateWebDavPreset(WebDavPreset.YANDEX_DISK)

        val state = viewModel.formState.value
        assertEquals("webdav.yandex.ru", state.host)
        assertEquals("443", state.portText)
        assertEquals("/", state.rootPath)
        assertTrue(state.useTls)
        assertEquals(WebDavPreset.YANDEX_DISK, state.webDavPreset)
    }

    @Test
    fun `updateWebDavPreset applies Nextcloud and ownCloud presets`() {
        viewModel.updateProtocol(NetworkProtocol.WEBDAV)

        viewModel.updateWebDavPreset(WebDavPreset.NEXTCLOUD)
        assertEquals("/remote.php/dav/files/{username}/", viewModel.formState.value.rootPath)

        viewModel.updateWebDavPreset(WebDavPreset.OWNCLOUD)
        assertEquals("/remote.php/webdav/", viewModel.formState.value.rootPath)
    }

    @Test
    fun `saveConnection validates required fields`() = runTest(testDispatcher) {
        viewModel.updateHost("")
        viewModel.updateName("")

        var saved = false
        viewModel.saveConnection { saved = true }
        advanceUntilIdle()

        assertFalse(saved)
        assertNotNull(viewModel.formState.value.errorMessage)
    }

    @Test
    fun `saveConnection validates SMB share name`() = runTest(testDispatcher) {
        viewModel.updateProtocol(NetworkProtocol.SMB)
        viewModel.updateName("My SMB")
        viewModel.updateHost("192.168.1.1")
        viewModel.updateShareOrBucket("")

        var saved = false
        viewModel.saveConnection { saved = true }
        advanceUntilIdle()

        assertFalse(saved)
        assertTrue(viewModel.formState.value.errorMessage?.contains("share name") == true)
    }

    @Test
    fun `saveConnection saves valid connection and credentials`() = runTest(testDispatcher) {
        viewModel.updateProtocol(NetworkProtocol.SFTP)
        viewModel.updateName("Dev Box")
        viewModel.updateHost("10.0.0.1")
        viewModel.updatePortText("2222")
        viewModel.updateUsername("root")
        viewModel.updatePassword("toor")
        viewModel.updateRequireBiometric(true)

        val connSlot = slot<ServerConnection>()
        val credSlot = slot<ServerCredentials>()
        coEvery { metadataRepository.saveServerConnection(capture(connSlot)) } returns Unit
        coEvery { credentialVault.storeCredentials(any(), capture(credSlot)) } returns Unit

        var savedConnection: ServerConnection? = null
        viewModel.saveConnection { savedConnection = it }
        advanceUntilIdle()

        assertNotNull(savedConnection)
        assertEquals("Dev Box", connSlot.captured.name)
        assertEquals("10.0.0.1", connSlot.captured.host)
        assertEquals(2222, connSlot.captured.port)
        assertEquals("root", connSlot.captured.username)
        assertTrue(connSlot.captured.requireBiometric)
        assertEquals("toor", credSlot.captured.password)
    }

    @Test
    fun `testConnection succeeds and cleans up temporary credentials`() = runTest(testDispatcher) {
        viewModel.updateProtocol(NetworkProtocol.SFTP)
        viewModel.updateName("Test Server")
        viewModel.updateHost("192.168.1.5")
        viewModel.updatePortText("22")
        viewModel.updateUsername("user")
        viewModel.updatePassword("pass")

        val dummyNodes = listOf(
            VfsNode(
                uri = VfsUri("sftp://test/file.txt"),
                name = "file.txt",
                isDirectory = false
            )
        )
        coEvery { vfsRouter.listDirectory(any()) } returns dummyNodes

        viewModel.testConnection()
        advanceUntilIdle()

        val testState = viewModel.formState.value.testState
        assertTrue(testState is ConnectionTestState.Success)
        assertEquals(1, (testState as ConnectionTestState.Success).itemCount)

        // Verify cleanup of temp credentials and connection
        coVerify(atLeast = 1) { metadataRepository.deleteServerConnection(match { it.startsWith("test_") }) }
        coVerify(atLeast = 1) { credentialVault.deleteCredentials(match { it.startsWith("test_") }) }
    }

    @Test
    fun `testConnection on error sets error state and cleans up`() = runTest(testDispatcher) {
        viewModel.updateProtocol(NetworkProtocol.SFTP)
        viewModel.updateName("Failing Server")
        viewModel.updateHost("192.168.1.99")
        viewModel.updateUsername("user")

        coEvery { vfsRouter.listDirectory(any()) } throws IOException("Connection timed out")

        viewModel.testConnection()
        advanceUntilIdle()

        val testState = viewModel.formState.value.testState
        assertTrue(testState is ConnectionTestState.Error)
        assertTrue((testState as ConnectionTestState.Error).message.contains("Connection timed out"))

        // Verify cleanup even on failure
        coVerify(atLeast = 1) { metadataRepository.deleteServerConnection(match { it.startsWith("test_") }) }
        coVerify(atLeast = 1) { credentialVault.deleteCredentials(match { it.startsWith("test_") }) }
    }
}
