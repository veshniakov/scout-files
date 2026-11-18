package dev.scout.files.feature.explorer

import android.content.Context
import dev.scout.files.core.database.repository.MetadataRepository
import dev.scout.files.core.model.ArchiveUri
import dev.scout.files.core.model.StorageVolume
import dev.scout.files.core.model.VfsNode
import dev.scout.files.core.model.VfsUri
import dev.scout.files.core.model.VolumeType
import dev.scout.files.core.operations.FileOperationManager
import dev.scout.files.core.operations.model.FileOperationTask
import dev.scout.files.core.operations.model.FileOperationType
import dev.scout.files.core.operations.model.OperationStatus
import dev.scout.files.core.vfs.ArchivePasswordStore
import dev.scout.files.core.vfs.VfsRouter
import dev.scout.files.feature.explorer.model.ExplorerIntent
import dev.scout.files.feature.explorer.model.SortConfig
import dev.scout.files.feature.explorer.model.SortDirection
import dev.scout.files.feature.explorer.model.SortField
import dev.scout.files.provider.local.LocalStorageVolumeManager
import dev.scout.files.provider.local.StandardDirectory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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

@OptIn(ExperimentalCoroutinesApi::class)
class ExplorerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val vfsRouter: VfsRouter = mockk(relaxed = true)
    private val fileOperationManager: FileOperationManager = mockk(relaxed = true)
    private val archivePasswordStore: ArchivePasswordStore = mockk(relaxed = true)
    private val volumeManager: LocalStorageVolumeManager = mockk(relaxed = true)
    private val metadataRepository: MetadataRepository = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)

    private val rootUri = VfsUri("file:///storage/emulated/0")
    private val testVolume = StorageVolume(
        id = "primary",
        name = "Внутренняя память",
        rootUri = rootUri,
        totalBytes = 64_000_000_000L,
        availableBytes = 32_000_000_000L,
        type = VolumeType.PRIMARY_STORAGE
    )

    private val sampleNodes = listOf(
        VfsNode(
            uri = rootUri.resolve("Documents"),
            name = "Documents",
            isDirectory = true,
            sizeBytes = 0L,
            lastModifiedMillis = 2000L
        ),
        VfsNode(
            uri = rootUri.resolve("Downloads"),
            name = "Downloads",
            isDirectory = true,
            sizeBytes = 0L,
            lastModifiedMillis = 1000L
        ),
        VfsNode(
            uri = rootUri.resolve("notes.txt"),
            name = "notes.txt",
            isDirectory = false,
            sizeBytes = 1024L,
            lastModifiedMillis = 3000L,
            extension = "txt"
        ),
        VfsNode(
            uri = rootUri.resolve("big_video.mp4"),
            name = "big_video.mp4",
            isDirectory = false,
            sizeBytes = 500_000L,
            lastModifiedMillis = 4000L,
            extension = "mp4"
        ),
        VfsNode(
            uri = rootUri.resolve(".hidden_file"),
            name = ".hidden_file",
            isDirectory = false,
            sizeBytes = 50L,
            lastModifiedMillis = 500L,
            isHidden = true,
            extension = ""
        )
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        coEvery { volumeManager.getStorageVolumes() } returns listOf(testVolume)
        coEvery { volumeManager.getStandardDirectories() } returns listOf(
            StandardDirectory("Документы", rootUri.resolve("Documents"), "documents")
        )
        coEvery { metadataRepository.checkAndPrepopulateDefaults() } returns Unit
        coEvery { metadataRepository.getBookmarks() } returns flowOf(emptyList())
        coEvery { metadataRepository.getTagsForUris(any()) } returns flowOf(emptyMap())
        coEvery { vfsRouter.listDirectory(any()) } returns sampleNodes

        every { fileOperationManager.tasks } returns MutableStateFlow(emptyList())
        every { fileOperationManager.activeConflict } returns MutableStateFlow(null)
        every { fileOperationManager.activePasswordPrompt } returns MutableStateFlow(null)
        every { fileOperationManager.enqueueDelete(any()) } returns "task_del_1"
        every { fileOperationManager.enqueueCopy(any(), any()) } returns "task_copy_1"
        every { fileOperationManager.enqueueMove(any(), any()) } returns "task_move_1"
        every { fileOperationManager.enqueueCompress(any(), any(), any(), any()) } returns "task_comp_1"
        every { fileOperationManager.enqueueExtract(any(), any()) } returns "task_ext_1"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ExplorerViewModel {
        return ExplorerViewModel(
            vfsRouter = vfsRouter,
            fileOperationManager = fileOperationManager,
            archivePasswordStore = archivePasswordStore,
            volumeManager = volumeManager,
            metadataRepository = metadataRepository,
            context = context,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun testArchiveFileNavigationOpensAsArchiveDirectory() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val zipFileUri = rootUri.resolve("archive.zip")
        val archiveExpectedUri = ArchiveUri.build(zipFileUri, "/")
        coEvery { vfsRouter.listDirectory(archiveExpectedUri) } returns listOf(
            VfsNode(
                uri = ArchiveUri.build(zipFileUri, "content.txt"),
                name = "content.txt",
                isDirectory = false,
                sizeBytes = 120L
            )
        )

        viewModel.handleIntent(ExplorerIntent.NavigateTo(zipFileUri))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val activeTab = state.primaryPane.activeTab
        assertNotNull(activeTab)
        assertEquals(archiveExpectedUri, activeTab?.currentUri)
        assertEquals(1, state.primaryPane.nodes.size)
        assertEquals("content.txt", state.primaryPane.nodes.first().name)
    }

    @Test
    fun testDeleteSelectedEnqueuesOperation() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val file1 = rootUri.resolve("notes.txt")
        viewModel.handleIntent(ExplorerIntent.ToggleSelectNode(file1))
        advanceUntilIdle()

        viewModel.handleIntent(ExplorerIntent.DeleteSelected(0))
        advanceUntilIdle()

        io.mockk.verify { fileOperationManager.enqueueDelete(listOf(file1)) }
        assertTrue(viewModel.uiState.value.primaryPane.selectedUris.isEmpty())
    }

    @Test
    fun testInitializationAndInitialTab() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.primaryPane.tabs.size)
        assertEquals(rootUri, state.primaryPane.activeTab?.currentUri)
        assertEquals(sampleNodes.size, state.primaryPane.nodes.size)
        assertFalse(state.isDualPaneEnabled)
    }

    @Test
    fun testNavigationToAndBack() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val subfolderUri = rootUri.resolve("Documents")
        val subfolderNodes = listOf(
            VfsNode(
                uri = subfolderUri.resolve("doc1.pdf"),
                name = "doc1.pdf",
                isDirectory = false,
                sizeBytes = 2048L
            )
        )
        coEvery { vfsRouter.listDirectory(subfolderUri) } returns subfolderNodes

        // Navigate forward
        viewModel.handleIntent(ExplorerIntent.NavigateTo(subfolderUri))
        advanceUntilIdle()

        var state = viewModel.uiState.value
        val activeTab = state.primaryPane.activeTab
        assertNotNull(activeTab)
        assertEquals(subfolderUri, activeTab!!.currentUri)
        assertEquals(listOf(rootUri), activeTab.backStack)
        assertEquals(1, state.primaryPane.nodes.size)
        assertEquals("doc1.pdf", state.primaryPane.nodes.first().name)

        // Navigate back
        viewModel.handleIntent(ExplorerIntent.NavigateBack())
        advanceUntilIdle()

        state = viewModel.uiState.value
        val backTab = state.primaryPane.activeTab
        assertNotNull(backTab)
        assertEquals(rootUri, backTab!!.currentUri)
        assertTrue(backTab.backStack.isEmpty())
    }

    @Test
    fun testTabManagement() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val newUri = rootUri.resolve("Downloads")
        viewModel.handleIntent(ExplorerIntent.OpenNewTab(newUri))
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertEquals(2, state.primaryPane.tabs.size)
        assertEquals(newUri, state.primaryPane.activeTab?.currentUri)

        val firstTabId = state.primaryPane.tabs[0].id
        val secondTabId = state.primaryPane.tabs[1].id

        // Switch back to first tab
        viewModel.handleIntent(ExplorerIntent.SelectTab(firstTabId))
        advanceUntilIdle()

        state = viewModel.uiState.value
        assertEquals(firstTabId, state.primaryPane.activeTabId)

        // Close second tab
        viewModel.handleIntent(ExplorerIntent.CloseTab(secondTabId))
        advanceUntilIdle()

        state = viewModel.uiState.value
        assertEquals(1, state.primaryPane.tabs.size)
        assertEquals(firstTabId, state.primaryPane.activeTabId)
    }

    @Test
    fun testSortingAndHiddenFilesFiltering() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.showHiddenFiles)

        // 1. By default, hidden files are filtered out
        val visibleDefault = state.visibleNodesForPane(0)
        assertFalse(visibleDefault.any { it.name == ".hidden_file" })
        assertEquals(4, visibleDefault.size) // Documents, Downloads, notes.txt, big_video.mp4

        // 2. Toggle show hidden files
        viewModel.handleIntent(ExplorerIntent.ToggleShowHiddenFiles)
        advanceUntilIdle()

        val stateHidden = viewModel.uiState.value
        assertTrue(stateHidden.showHiddenFiles)
        val visibleWithHidden = stateHidden.visibleNodesForPane(0)
        assertTrue(visibleWithHidden.any { it.name == ".hidden_file" })
        assertEquals(5, visibleWithHidden.size)

        // 3. Sort by NAME DESCENDING
        viewModel.handleIntent(
            ExplorerIntent.SetSortConfig(
                SortConfig(field = SortField.NAME, direction = SortDirection.DESCENDING)
            )
        )
        advanceUntilIdle()

        val sortedDesc = viewModel.uiState.value.visibleNodesForPane(0)
        // Directories come first: Downloads, Documents
        assertEquals("Downloads", sortedDesc[0].name)
        assertEquals("Documents", sortedDesc[1].name)
        // Then files descending: notes.txt, big_video.mp4, .hidden_file
        assertEquals("notes.txt", sortedDesc[2].name)
        assertEquals("big_video.mp4", sortedDesc[3].name)

        // 4. Sort by SIZE ASCENDING
        viewModel.handleIntent(
            ExplorerIntent.SetSortConfig(
                SortConfig(field = SortField.SIZE, direction = SortDirection.ASCENDING)
            )
        )
        advanceUntilIdle()

        val sortedSize = viewModel.uiState.value.visibleNodesForPane(0)
        // Files after directories: .hidden_file (50B), notes.txt (1024B), big_video.mp4 (500000B)
        assertEquals(".hidden_file", sortedSize[2].name)
        assertEquals("notes.txt", sortedSize[3].name)
        assertEquals("big_video.mp4", sortedSize[4].name)
    }

    @Test
    fun testDualPaneToggle() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isDualPaneEnabled)
        assertNull(viewModel.uiState.value.secondaryPane)

        // Enable Dual Pane
        viewModel.handleIntent(ExplorerIntent.ToggleDualPane)
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertTrue(state.isDualPaneEnabled)
        assertNotNull(state.secondaryPane)
        assertEquals(1, state.secondaryPane!!.tabs.size)

        // Focus secondary pane
        viewModel.handleIntent(ExplorerIntent.SetFocusedPane(1))
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.focusedPaneIndex)

        // Disable Dual Pane
        viewModel.handleIntent(ExplorerIntent.ToggleDualPane)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isDualPaneEnabled)
    }

    @Test
    fun testQuickLook() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val node = sampleNodes.find { it.name == "notes.txt" }!!
        coEvery { vfsRouter.openInputStream(node.uri) } returns "Sample note content".byteInputStream()

        viewModel.handleIntent(ExplorerIntent.OpenQuickLook(node))
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertNotNull(state.quickLook)
        assertEquals(node, state.quickLook?.node)
        assertEquals("Sample note content", state.quickLook?.textPreview)

        viewModel.handleIntent(ExplorerIntent.CloseQuickLook)
        advanceUntilIdle()

        state = viewModel.uiState.value
        assertNull(state.quickLook)
    }

    @Test
    fun testQuickLookBinaryFileDoesNotReadTextPreview() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val videoNode = sampleNodes.find { it.name == "big_video.mp4" }!!
        viewModel.handleIntent(ExplorerIntent.OpenQuickLook(videoNode))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.quickLook)
        assertEquals(videoNode, state.quickLook?.node)
        assertNull(state.quickLook?.textPreview)
    }

    @Test
    fun testQuickLookPasswordChallengeAndRetry() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val secretFileUri = ArchiveUri.build(rootUri.resolve("vault.zip"), "secret.txt")
        val secretNode = VfsNode(
            uri = secretFileUri,
            name = "secret.txt",
            isDirectory = false,
            sizeBytes = 100L,
            extension = "txt"
        )

        // 1. First attempt requires authentication
        coEvery { vfsRouter.openInputStream(secretFileUri) } throws dev.scout.files.core.model.VfsException.AuthenticationRequired(
            secretFileUri,
            "Password required"
        ) andThen "Decrypted top secret content".byteInputStream()

        viewModel.handleIntent(ExplorerIntent.OpenQuickLook(secretNode))
        advanceUntilIdle()

        val state1 = viewModel.uiState.value
        assertTrue(state1.isPasswordDialogVisible)
        assertEquals(secretFileUri, state1.pendingPasswordUri)
        assertEquals(secretNode, state1.pendingPasswordNode)

        // 2. Submit password - should store in ArchivePasswordStore and auto-retry Quick Look
        viewModel.handleIntent(ExplorerIntent.SubmitDirectArchivePassword("password123", 0))
        advanceUntilIdle()

        val state2 = viewModel.uiState.value
        assertFalse(state2.isPasswordDialogVisible)
        assertNull(state2.pendingPasswordUri)
        assertNull(state2.pendingPasswordNode)

        assertNotNull(state2.quickLook)
        assertEquals(secretNode, state2.quickLook?.node)
        assertEquals("Decrypted top secret content", state2.quickLook?.textPreview)
    }

    @Test
    fun testSubsequentOperationsBothTriggerRefresh() = runTest(testDispatcher) {
        val tasksFlow = MutableStateFlow<List<FileOperationTask>>(emptyList())
        every { fileOperationManager.tasks } returns tasksFlow

        val viewModel = createViewModel()
        advanceUntilIdle()

        val task1 = FileOperationTask(
            id = "t1",
            type = FileOperationType.COPY,
            sources = listOf(rootUri.resolve("f1.txt")),
            destinationDir = rootUri,
            status = OperationStatus.RUNNING
        )
        val task2 = FileOperationTask(
            id = "t2",
            type = FileOperationType.MOVE,
            sources = listOf(rootUri.resolve("f2.txt")),
            destinationDir = rootUri,
            status = OperationStatus.RUNNING
        )

        tasksFlow.value = listOf(task1, task2)
        advanceUntilIdle()

        // Complete task 1
        tasksFlow.value = listOf(task1.copy(status = OperationStatus.COMPLETED), task2)
        advanceUntilIdle()

        // Complete task 2
        tasksFlow.value = listOf(task1.copy(status = OperationStatus.COMPLETED), task2.copy(status = OperationStatus.COMPLETED))
        advanceUntilIdle()

        // Both completed tasks observed
        val state = viewModel.uiState.value
        assertEquals(2, state.operationsTasks.size)
        assertTrue(state.operationsTasks.all { it.status == OperationStatus.COMPLETED })
    }
}
