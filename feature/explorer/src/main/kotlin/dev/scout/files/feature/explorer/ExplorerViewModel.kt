package dev.scout.files.feature.explorer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.scout.files.core.database.repository.MetadataRepository
import dev.scout.files.core.model.FileTag
import dev.scout.files.core.model.TagColor
import dev.scout.files.core.model.VfsNode
import dev.scout.files.core.model.VfsUri
import dev.scout.files.core.vfs.VfsRouter
import dev.scout.files.core.vfs.di.IoDispatcher
import dev.scout.files.feature.explorer.model.ExplorerEffect
import dev.scout.files.feature.explorer.model.ExplorerIntent
import dev.scout.files.feature.explorer.model.ExplorerPaneState
import dev.scout.files.feature.explorer.model.ExplorerTab
import dev.scout.files.feature.explorer.model.ExplorerUiState
import dev.scout.files.feature.explorer.model.QuickLookState
import dev.scout.files.feature.explorer.model.SortDirection
import dev.scout.files.feature.explorer.model.SortField
import dev.scout.files.feature.explorer.model.ViewMode
import dev.scout.files.provider.local.LocalStorageVolumeManager
import dev.scout.files.provider.local.StoragePermissionHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject

@HiltViewModel
class ExplorerViewModel @Inject constructor(
    private val vfsRouter: VfsRouter,
    private val volumeManager: LocalStorageVolumeManager,
    private val metadataRepository: MetadataRepository,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExplorerUiState())
    val uiState: StateFlow<ExplorerUiState> = _uiState.asStateFlow()

    private val _effects = Channel<ExplorerEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        initExplorer()
    }

    private fun initExplorer() {
        viewModelScope.launch {
            val hasPerm = StoragePermissionHelper.hasAllFilesAccess(context)
            val volumes = volumeManager.getStorageVolumes()
            val stdDirs = volumeManager.getStandardDirectories()

            metadataRepository.checkAndPrepopulateDefaults()

            val initialUri = volumes.firstOrNull()?.rootUri
                ?: stdDirs.firstOrNull()?.uri
                ?: VfsUri("file:///storage/emulated/0")

            val initialTab = ExplorerTab(
                title = initialUri.name.ifEmpty { "Накопитель" },
                currentUri = initialUri
            )

            _uiState.update { current ->
                current.copy(
                    hasStoragePermission = hasPerm,
                    volumes = volumes,
                    standardDirectories = stdDirs,
                    primaryPane = current.primaryPane.copy(
                        tabs = listOf(initialTab),
                        activeTabId = initialTab.id
                    )
                )
            }

            // Observe bookmarks
            launch {
                metadataRepository.getBookmarks().collect { bookmarks ->
                    _uiState.update { it.copy(bookmarks = bookmarks) }
                }
            }

            loadDirectory(initialUri, paneIndex = 0)
        }
    }

    fun handleIntent(intent: ExplorerIntent) {
        when (intent) {
            is ExplorerIntent.NavigateTo -> navigateTo(intent.uri, intent.paneIndex)
            is ExplorerIntent.NavigateBack -> navigateBack(intent.paneIndex)
            is ExplorerIntent.OpenNewTab -> openNewTab(intent.initialUri, intent.paneIndex)
            is ExplorerIntent.CloseTab -> closeTab(intent.tabId, intent.paneIndex)
            is ExplorerIntent.SelectTab -> selectTab(intent.tabId, intent.paneIndex)
            is ExplorerIntent.ToggleDualPane -> toggleDualPane()
            is ExplorerIntent.SetFocusedPane -> _uiState.update { it.copy(focusedPaneIndex = intent.paneIndex) }
            is ExplorerIntent.SetSearchQuery -> setSearchQuery(intent.query, intent.paneIndex)
            is ExplorerIntent.SetViewMode -> _uiState.update { it.copy(viewMode = intent.viewMode) }
            is ExplorerIntent.SetSortConfig -> {
                _uiState.update { it.copy(sortConfig = intent.sortConfig) }
                refreshCurrentPanes()
            }
            is ExplorerIntent.ToggleShowHiddenFiles -> {
                _uiState.update { it.copy(showHiddenFiles = !it.showHiddenFiles) }
                refreshCurrentPanes()
            }
            is ExplorerIntent.ToggleSelectNode -> toggleSelectNode(intent.uri, intent.paneIndex)
            is ExplorerIntent.SelectAll -> selectAll(intent.paneIndex)
            is ExplorerIntent.ClearSelection -> clearSelection(intent.paneIndex)
            is ExplorerIntent.FilterByTag -> filterByTag(intent.color, intent.paneIndex)
            is ExplorerIntent.AssignTag -> assignTag(intent.uri, intent.color)
            is ExplorerIntent.CreateDirectory -> createDirectory(intent.name, intent.paneIndex)
            is ExplorerIntent.RenameNode -> renameNode(intent.oldUri, intent.newName, intent.paneIndex)
            is ExplorerIntent.DeleteSelected -> deleteSelected(intent.paneIndex)
            is ExplorerIntent.CopySelectedToAdjacentPane -> copySelectedToAdjacentPane(intent.move)
            is ExplorerIntent.OpenQuickLook -> openQuickLook(intent.node)
            is ExplorerIntent.ComputeQuickLookChecksums -> computeQuickLookChecksums()
            is ExplorerIntent.CloseQuickLook -> _uiState.update { it.copy(quickLook = null) }
            is ExplorerIntent.AddBookmark -> addBookmark(intent.uri, intent.title)
            is ExplorerIntent.RemoveBookmark -> removeBookmark(intent.uri)
            is ExplorerIntent.RefreshVolumesAndPermissions -> refreshVolumesAndPermissions()
        }
    }

    private fun loadDirectory(uri: VfsUri, paneIndex: Int) {
        viewModelScope.launch {
            updatePane(paneIndex) { it.copy(isLoading = true, error = null) }

            try {
                val nodes = vfsRouter.listDirectory(uri)
                val nodeTags = metadataRepository.getTagsForUris(nodes.map { it.uri }).first()

                updatePane(paneIndex) { pane ->
                    pane.copy(
                        nodes = nodes,
                        nodeTags = nodeTags,
                        isLoading = false,
                        selectedUris = emptySet(),
                        error = null
                    )
                }
            } catch (e: Exception) {
                updatePane(paneIndex) { pane ->
                    pane.copy(
                        nodes = emptyList(),
                        isLoading = false,
                        error = e.localizedMessage ?: "Ошибка чтения каталога"
                    )
                }
                _effects.send(ExplorerEffect.ShowSnackbar("Ошибка: ${e.localizedMessage ?: e.message}"))
            }
        }
    }

    private fun navigateTo(uri: VfsUri, paneIndex: Int) {
        val pane = getPane(paneIndex)
        val activeTab = pane.activeTab ?: return

        val newBackStack = activeTab.backStack + activeTab.currentUri
        val updatedTab = activeTab.copy(
            currentUri = uri,
            title = uri.name.ifEmpty { "Корень" },
            backStack = newBackStack,
            forwardStack = emptyList()
        )

        updatePane(paneIndex) { p ->
            p.copy(tabs = p.tabs.map { if (it.id == updatedTab.id) updatedTab else it })
        }
        loadDirectory(uri, paneIndex)
    }

    private fun navigateBack(paneIndex: Int) {
        val pane = getPane(paneIndex)
        val activeTab = pane.activeTab ?: return
        if (activeTab.backStack.isEmpty()) return

        val previousUri = activeTab.backStack.last()
        val newBackStack = activeTab.backStack.dropLast(1)
        val newForwardStack = listOf(activeTab.currentUri) + activeTab.forwardStack

        val updatedTab = activeTab.copy(
            currentUri = previousUri,
            title = previousUri.name.ifEmpty { "Корень" },
            backStack = newBackStack,
            forwardStack = newForwardStack
        )

        updatePane(paneIndex) { p ->
            p.copy(tabs = p.tabs.map { if (it.id == updatedTab.id) updatedTab else it })
        }
        loadDirectory(previousUri, paneIndex)
    }

    private fun openNewTab(initialUri: VfsUri?, paneIndex: Int) {
        val uri = initialUri
            ?: getPane(paneIndex).activeTab?.currentUri
            ?: VfsUri("file:///storage/emulated/0")

        val newTab = ExplorerTab(
            title = uri.name.ifEmpty { "Новая вкладка" },
            currentUri = uri
        )

        updatePane(paneIndex) { pane ->
            pane.copy(
                tabs = pane.tabs + newTab,
                activeTabId = newTab.id
            )
        }
        loadDirectory(uri, paneIndex)
    }

    private fun closeTab(tabId: String, paneIndex: Int) {
        val pane = getPane(paneIndex)
        if (pane.tabs.size <= 1) return // Keep at least one tab

        val newTabs = pane.tabs.filter { it.id != tabId }
        val newActiveId = if (pane.activeTabId == tabId) newTabs.last().id else pane.activeTabId

        updatePane(paneIndex) { p ->
            p.copy(tabs = newTabs, activeTabId = newActiveId)
        }
        val targetUri = newTabs.find { it.id == newActiveId }?.currentUri
        if (targetUri != null) {
            loadDirectory(targetUri, paneIndex)
        }
    }

    private fun selectTab(tabId: String, paneIndex: Int) {
        val pane = getPane(paneIndex)
        val tab = pane.tabs.find { it.id == tabId } ?: return

        updatePane(paneIndex) { it.copy(activeTabId = tabId) }
        loadDirectory(tab.currentUri, paneIndex)
    }

    private fun toggleDualPane() {
        _uiState.update { state ->
            val newEnabled = !state.isDualPaneEnabled
            if (newEnabled && state.secondaryPane == null) {
                val primaryTab = state.primaryPane.activeTab
                val secondTab = ExplorerTab(
                    title = primaryTab?.title ?: "Вторая панель",
                    currentUri = primaryTab?.currentUri ?: VfsUri("file:///storage/emulated/0")
                )
                state.copy(
                    isDualPaneEnabled = true,
                    secondaryPane = ExplorerPaneState(
                        tabs = listOf(secondTab),
                        activeTabId = secondTab.id
                    )
                )
            } else {
                state.copy(isDualPaneEnabled = newEnabled)
            }
        }
        if (_uiState.value.isDualPaneEnabled) {
            _uiState.value.secondaryPane?.activeTab?.currentUri?.let { uri ->
                loadDirectory(uri, paneIndex = 1)
            }
        }
    }

    private fun setSearchQuery(query: String, paneIndex: Int) {
        updatePane(paneIndex) { it.copy(searchQuery = query) }
    }

    private fun toggleSelectNode(uri: VfsUri, paneIndex: Int) {
        updatePane(paneIndex) { pane ->
            val set = pane.selectedUris.toMutableSet()
            if (set.contains(uri)) set.remove(uri) else set.add(uri)
            pane.copy(selectedUris = set)
        }
    }

    private fun selectAll(paneIndex: Int) {
        val pane = getPane(paneIndex)
        val allUris = pane.nodes.map { it.uri }.toSet()
        updatePane(paneIndex) { it.copy(selectedUris = allUris) }
    }

    private fun clearSelection(paneIndex: Int) {
        updatePane(paneIndex) { it.copy(selectedUris = emptySet()) }
    }

    private fun filterByTag(color: TagColor?, paneIndex: Int) {
        val pane = getPane(paneIndex)
        val tab = pane.activeTab ?: return
        val updatedTab = tab.copy(selectedTagFilter = color)
        updatePane(paneIndex) { p ->
            p.copy(tabs = p.tabs.map { if (it.id == tab.id) updatedTab else it })
        }
    }

    private fun assignTag(uri: VfsUri, color: TagColor) {
        viewModelScope.launch {
            val tags = metadataRepository.getTagsForUri(uri).first()
            val hasTag = tags.any { it.color == color }
            metadataRepository.toggleTag(uri, color, hasTag)

            // Update in-memory tags for current panes
            refreshCurrentPanes()
        }
    }

    private fun createDirectory(name: String, paneIndex: Int) {
        val pane = getPane(paneIndex)
        val activeTab = pane.activeTab ?: return
        val newDirUri = activeTab.currentUri.resolve(name)

        viewModelScope.launch {
            val success = vfsRouter.createDirectory(newDirUri)
            if (success) {
                _effects.send(ExplorerEffect.ShowSnackbar("Папка '$name' создана"))
                loadDirectory(activeTab.currentUri, paneIndex)
            } else {
                _effects.send(ExplorerEffect.ShowSnackbar("Не удалось создать папку"))
            }
        }
    }

    private fun renameNode(oldUri: VfsUri, newName: String, paneIndex: Int) {
        val parent = oldUri.parent ?: return
        val targetUri = parent.resolve(newName)
        val activeTab = getPane(paneIndex).activeTab ?: return

        viewModelScope.launch {
            val success = vfsRouter.rename(oldUri, targetUri)
            if (success) {
                _effects.send(ExplorerEffect.ShowSnackbar("Переименовано в '$newName'"))
                loadDirectory(activeTab.currentUri, paneIndex)
            } else {
                _effects.send(ExplorerEffect.ShowSnackbar("Ошибка переименования"))
            }
        }
    }

    private fun deleteSelected(paneIndex: Int) {
        val pane = getPane(paneIndex)
        val selected = pane.selectedUris.toList()
        if (selected.isEmpty()) return
        val activeTab = pane.activeTab ?: return

        viewModelScope.launch {
            var count = 0
            for (uri in selected) {
                if (vfsRouter.delete(uri, recursive = true)) count++
            }
            _effects.send(ExplorerEffect.ShowSnackbar("Удалено элементов: $count"))
            loadDirectory(activeTab.currentUri, paneIndex)
        }
    }

    private fun copySelectedToAdjacentPane(move: Boolean) {
        val isDual = _uiState.value.isDualPaneEnabled
        if (!isDual) return

        val sourceIndex = _uiState.value.focusedPaneIndex
        val targetIndex = if (sourceIndex == 0) 1 else 0

        val sourcePane = getPane(sourceIndex)
        val targetPane = getPane(targetIndex)
        val targetUri = targetPane.activeTab?.currentUri ?: return
        val selected = sourcePane.selectedUris.toList()
        if (selected.isEmpty()) return

        viewModelScope.launch {
            var successCount = 0
            for (src in selected) {
                val dst = targetUri.resolve(src.name)
                val ok = if (move) vfsRouter.rename(src, dst) else vfsRouter.copy(src, dst)
                if (ok) successCount++
            }
            val action = if (move) "Перемещено" else "Скопировано"
            _effects.send(ExplorerEffect.ShowSnackbar("$action элементов: $successCount"))
            sourcePane.activeTab?.currentUri?.let { loadDirectory(it, sourceIndex) }
            loadDirectory(targetUri, targetIndex)
        }
    }

    private fun openQuickLook(node: VfsNode) {
        viewModelScope.launch {
            var preview: String? = null
            if (!node.isDirectory && node.sizeBytes < 50L * 1024 * 1024) {
                preview = withContext(ioDispatcher) {
                    runCatching {
                        vfsRouter.openInputStream(node.uri).use { stream ->
                            val buffer = ByteArray(8192)
                            val read = stream.read(buffer)
                            if (read > 0) {
                                String(buffer, 0, read, Charsets.UTF_8)
                            } else null
                        }
                    }.getOrNull()
                }
            }

            _uiState.update {
                it.copy(
                    quickLook = QuickLookState(
                        node = node,
                        textPreview = preview
                    )
                )
            }
        }
    }

    private fun computeQuickLookChecksums() {
        val ql = _uiState.value.quickLook ?: return
        val node = ql.node
        if (node.isDirectory) return

        viewModelScope.launch {
            _uiState.update { it.copy(quickLook = it.quickLook?.copy(isLoadingChecksum = true)) }

            val (md5, sha256) = withContext(ioDispatcher) {
                runCatching {
                    val md5Digest = MessageDigest.getInstance("MD5")
                    val sha256Digest = MessageDigest.getInstance("SHA-256")
                    vfsRouter.openInputStream(node.uri).use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            md5Digest.update(buffer, 0, read)
                            sha256Digest.update(buffer, 0, read)
                        }
                    }
                    val md5Hex = md5Digest.digest().joinToString("") { "%02x".format(it) }
                    val sha256Hex = sha256Digest.digest().joinToString("") { "%02x".format(it) }
                    Pair(md5Hex, sha256Hex)
                }.getOrDefault(Pair("Ошибка", "Ошибка"))
            }

            _uiState.update {
                it.copy(
                    quickLook = it.quickLook?.copy(
                        md5Checksum = md5,
                        sha256Checksum = sha256,
                        isLoadingChecksum = false
                    )
                )
            }
        }
    }

    private fun addBookmark(uri: VfsUri, title: String) {
        viewModelScope.launch {
            metadataRepository.addBookmark(uri, title)
            _effects.send(ExplorerEffect.ShowSnackbar("Добавлено в Избранное"))
        }
    }

    private fun removeBookmark(uri: VfsUri) {
        viewModelScope.launch {
            metadataRepository.removeBookmark(uri)
            _effects.send(ExplorerEffect.ShowSnackbar("Закладка удалена"))
        }
    }

    private fun refreshVolumesAndPermissions() {
        viewModelScope.launch {
            val hasPerm = StoragePermissionHelper.hasAllFilesAccess(context)
            val volumes = volumeManager.getStorageVolumes()
            _uiState.update {
                it.copy(hasStoragePermission = hasPerm, volumes = volumes)
            }
            refreshCurrentPanes()
        }
    }

    private fun refreshCurrentPanes() {
        val s = _uiState.value
        s.primaryPane.activeTab?.currentUri?.let { loadDirectory(it, 0) }
        if (s.isDualPaneEnabled) {
            s.secondaryPane?.activeTab?.currentUri?.let { loadDirectory(it, 1) }
        }
    }

    private fun getPane(index: Int): ExplorerPaneState {
        return if (index == 1 && _uiState.value.secondaryPane != null) {
            _uiState.value.secondaryPane!!
        } else {
            _uiState.value.primaryPane
        }
    }

    private fun updatePane(index: Int, transform: (ExplorerPaneState) -> ExplorerPaneState) {
        _uiState.update { state ->
            if (index == 1 && state.secondaryPane != null) {
                state.copy(secondaryPane = transform(state.secondaryPane))
            } else {
                state.copy(primaryPane = transform(state.primaryPane))
            }
        }
    }
}
