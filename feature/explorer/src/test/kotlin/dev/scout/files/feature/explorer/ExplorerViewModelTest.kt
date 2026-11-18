package dev.scout.files.feature.explorer

import android.content.Context
import dev.scout.files.core.database.repository.MetadataRepository
import dev.scout.files.core.model.StorageVolume
import dev.scout.files.core.model.VfsNode
import dev.scout.files.core.model.VfsUri
import dev.scout.files.core.model.VolumeType
import dev.scout.files.core.vfs.VfsRouter
import dev.scout.files.feature.explorer.model.ExplorerIntent
import dev.scout.files.feature.explorer.model.SortConfig
import dev.scout.files.feature.explorer.model.SortDirection
import dev.scout.files.feature.explorer.model.SortField
import dev.scout.files.provider.local.LocalStorageVolumeManager
import dev.scout.files.provider.local.StandardDirectory
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ExplorerViewModel {
        return ExplorerViewModel(
            vfsRouter = vfsRouter,
            volumeManager = volumeManager,
            metadataRepository = metadataRepository,
            context = context,
            ioDispatcher = testDispatcher
        )
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
}
