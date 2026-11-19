package dev.scout.files.feature.explorer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.scout.files.core.database.repository.MetadataRepository
import dev.scout.files.core.model.ArchiveUri
import dev.scout.files.core.model.FileTag
import dev.scout.files.core.model.TagColor
import dev.scout.files.core.model.VfsException
import dev.scout.files.core.model.VfsNode
import dev.scout.files.core.model.VfsUri
import dev.scout.files.core.operations.FileOperationManager
import dev.scout.files.core.operations.model.OperationStatus
import dev.scout.files.core.security.CredentialVault
import dev.scout.files.core.vfs.ArchivePasswordStore
import dev.scout.files.core.vfs.VfsRouter
import dev.scout.files.core.vfs.di.IoDispatcher
import dev.scout.files.feature.explorer.R
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
    private val fileOperationManager: FileOperationManager,
    private val archivePasswordStore: ArchivePasswordStore,
    private val volumeManager: LocalStorageVolumeManager,
    private val metadataRepository: MetadataRepository,
    private val credentialVault: CredentialVault,
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

            val defaultTitle = runCatching { context.getString(R.string.tab_default_name) }.getOrDefault("Storage").ifBlank { "Storage" }
            val initialTab = ExplorerTab(
                title = initialUri.name.ifEmpty { defaultTitle },
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

            // Observe server connections
            launch {
                metadataRepository.getServerConnections().collect { connections ->
                    _uiState.update { it.copy(serverConnections = connections) }
                }
            }

            // Observe background operations queue
            launch {
                var prevCompletedTaskIds = emptySet<String>()
                fileOperationManager.tasks.collect { tasks ->
                    val currentCompletedIds = tasks.filter { it.status == OperationStatus.COMPLETED }.map { it.id }.toSet()
                    val newlyCompleted = currentCompletedIds - prevCompletedTaskIds
                    prevCompletedTaskIds = currentCompletedIds

                    _uiState.update { it.copy(operationsTasks = tasks) }
                    if (newlyCompleted.isNotEmpty()) {
                        refreshCurrentPanes()
                    }
                }
            }

            // Observe conflict prompt
            launch {
                fileOperationManager.activeConflict.collect { conflict ->
                    _uiState.update { it.copy(activeConflict = conflict) }
                }
            }

            // Observe background password prompt
            launch {
                fileOperationManager.activePasswordPrompt.collect { prompt ->
                    _uiState.update { it.copy(activePasswordPrompt = prompt) }
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

            // Connection intents
            is ExplorerIntent.OpenCreateConnectionSheet -> _uiState.update {
                it.copy(isConnectionSheetVisible = true, editingConnection = null)
            }
            is ExplorerIntent.OpenEditConnectionSheet -> _uiState.update {
                it.copy(isConnectionSheetVisible = true, editingConnection = intent.connection)
            }
            is ExplorerIntent.DismissConnectionSheet -> _uiState.update {
                it.copy(isConnectionSheetVisible = false, editingConnection = null)
            }
            is ExplorerIntent.RequestDeleteConnection -> _uiState.update {
                it.copy(connectionToDelete = intent.connection)
            }
            is ExplorerIntent.DismissDeleteConnectionDialog -> _uiState.update {
                it.copy(connectionToDelete = null)
            }
            is ExplorerIntent.ConfirmDeleteConnection -> {
                val conn = _uiState.value.connectionToDelete
                if (conn != null) {
                    viewModelScope.launch(ioDispatcher) {
                        metadataRepository.deleteServerConnection(conn.id)
                        credentialVault.deleteCredentials(conn.id)
                    }
                    _uiState.update { it.copy(connectionToDelete = null) }
                }
            }
            is ExplorerIntent.ConnectToServer -> {
                if (intent.connection.requireBiometric && !intent.isBiometricAuthenticated) {
                    viewModelScope.launch {
                        _effects.send(ExplorerEffect.RequestBiometricAuth(intent.connection, intent.paneIndex))
                    }
                } else {
                    navigateTo(intent.connection.rootVfsUri(), intent.paneIndex)
                }
            }

            // Operation intents
            is ExplorerIntent.ShowCompressDialog -> _uiState.update { it.copy(compressDialogSources = intent.sources) }
            is ExplorerIntent.HideCompressDialog -> _uiState.update { it.copy(compressDialogSources = null) }
            is ExplorerIntent.CompressSelected -> compressSelected(intent.name, intent.isZip, intent.password, intent.paneIndex)
            is ExplorerIntent.ExtractArchive -> extractArchive(intent.archiveUri, intent.destinationDir)
            is ExplorerIntent.ToggleOperationsSheet -> _uiState.update { it.copy(isOperationsSheetVisible = intent.visible) }
            is ExplorerIntent.PauseOperation -> fileOperationManager.pause(intent.taskId)
            is ExplorerIntent.ResumeOperation -> fileOperationManager.resume(intent.taskId)
            is ExplorerIntent.CancelOperation -> fileOperationManager.cancel(intent.taskId)
            is ExplorerIntent.ClearCompletedOperations -> fileOperationManager.clearTerminalTasks()
            is ExplorerIntent.ResolveConflict -> fileOperationManager.resolveConflict(intent.decision)
            is ExplorerIntent.SubmitArchivePassword -> fileOperationManager.submitPassword(intent.password)
            is ExplorerIntent.SubmitDirectArchivePassword -> submitDirectArchivePassword(intent.password, intent.paneIndex)
            is ExplorerIntent.DismissPasswordDialog -> _uiState.update {
                it.copy(
                    isPasswordDialogVisible = false,
                    passwordError = null,
                    pendingPasswordUri = null,
                    pendingPasswordNode = null
                )
            }
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
            } catch (e: VfsException.AuthenticationRequired) {
                updatePane(paneIndex) { pane ->
                    pane.copy(isLoading = false, error = null)
                }
                val isWrong = archivePasswordStore.hasPassword(uri)
                val errMsg = if (isWrong) {
                    runCatching { context.getString(R.string.archive_password_wrong) }.getOrDefault("Incorrect password")
                } else null
                _uiState.update {
                    it.copy(
                        pendingPasswordUri = uri,
                        isPasswordDialogVisible = true,
                        passwordError = errMsg
                    )
                }
            } catch (e: Exception) {
                val errorMsg = e.localizedMessage ?: runCatching { context.getString(R.string.error_reading_directory) }.getOrDefault("Error reading directory")
                updatePane(paneIndex) { pane ->
                    pane.copy(
                        nodes = emptyList(),
                        isLoading = false,
                        error = errorMsg
                    )
                }
                val prefix = runCatching { context.getString(R.string.error_prefix, errorMsg) }.getOrDefault("Error: $errorMsg")
                _effects.send(ExplorerEffect.ShowSnackbar(prefix))
            }
        }
    }

    private fun navigateTo(uri: VfsUri, paneIndex: Int) {
        val targetUri = if (!ArchiveUri.isArchiveUri(uri) && ArchiveUri.isSupportedArchiveFile(uri.name)) {
            ArchiveUri.build(uri, "/")
        } else {
            uri
        }

        val pane = getPane(paneIndex)
        val activeTab = pane.activeTab ?: return

        val rootTitle = runCatching { context.getString(R.string.tab_root) }.getOrDefault("Root").ifBlank { "Root" }
        val newBackStack = activeTab.backStack + activeTab.currentUri
        val title = if (ArchiveUri.isArchiveUri(targetUri)) {
            ArchiveUri.extractSourceUri(targetUri)?.name ?: targetUri.name
        } else {
            targetUri.name.ifEmpty { rootTitle }
        }
        val updatedTab = activeTab.copy(
            currentUri = targetUri,
            title = title,
            backStack = newBackStack,
            forwardStack = emptyList()
        )

        updatePane(paneIndex) { p ->
            p.copy(tabs = p.tabs.map { if (it.id == updatedTab.id) updatedTab else it })
        }
        loadDirectory(targetUri, paneIndex)
    }

    private fun navigateBack(paneIndex: Int) {
        val pane = getPane(paneIndex)
        val activeTab = pane.activeTab ?: return
        if (activeTab.backStack.isEmpty()) return

        val rootTitle = runCatching { context.getString(R.string.tab_root) }.getOrDefault("Root").ifBlank { "Root" }
        val previousUri = activeTab.backStack.last()
        val newBackStack = activeTab.backStack.dropLast(1)
        val newForwardStack = listOf(activeTab.currentUri) + activeTab.forwardStack

        val updatedTab = activeTab.copy(
            currentUri = previousUri,
            title = previousUri.name.ifEmpty { rootTitle },
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

        val newTabTitle = runCatching { context.getString(R.string.tab_new) }.getOrDefault("New tab").ifBlank { "New tab" }
        val newTab = ExplorerTab(
            title = uri.name.ifEmpty { newTabTitle },
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
                val secondPaneTitle = runCatching { context.getString(R.string.tab_second_pane) }.getOrDefault("Second pane").ifBlank { "Second pane" }
                val secondTab = ExplorerTab(
                    title = primaryTab?.title ?: secondPaneTitle,
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
                val msg = runCatching { context.getString(R.string.folder_created, name) }.getOrDefault("Folder '$name' created")
                _effects.send(ExplorerEffect.ShowSnackbar(msg))
                loadDirectory(activeTab.currentUri, paneIndex)
            } else {
                val msg = runCatching { context.getString(R.string.folder_create_failed) }.getOrDefault("Failed to create folder")
                _effects.send(ExplorerEffect.ShowSnackbar(msg))
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
                val msg = runCatching { context.getString(R.string.node_renamed, newName) }.getOrDefault("Renamed to '$newName'")
                _effects.send(ExplorerEffect.ShowSnackbar(msg))
                loadDirectory(activeTab.currentUri, paneIndex)
            } else {
                val msg = runCatching { context.getString(R.string.rename_failed) }.getOrDefault("Failed to rename")
                _effects.send(ExplorerEffect.ShowSnackbar(msg))
            }
        }
    }

    private fun deleteSelected(paneIndex: Int) {
        val pane = getPane(paneIndex)
        val selected = pane.selectedUris.toList()
        if (selected.isEmpty()) return

        fileOperationManager.enqueueDelete(selected)
        clearSelection(paneIndex)
        viewModelScope.launch {
            val msg = runCatching { context.getString(R.string.items_deleted, selected.size) }.getOrDefault("Deleting items: ${selected.size}")
            _effects.send(ExplorerEffect.ShowSnackbar(msg))
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

        if (move) {
            fileOperationManager.enqueueMove(selected, targetUri)
        } else {
            fileOperationManager.enqueueCopy(selected, targetUri)
        }

        clearSelection(sourceIndex)
        viewModelScope.launch {
            val msg = if (move) {
                runCatching { context.getString(R.string.items_moved, selected.size) }.getOrDefault("Moving items: ${selected.size}")
            } else {
                runCatching { context.getString(R.string.items_copied, selected.size) }.getOrDefault("Copying items: ${selected.size}")
            }
            _effects.send(ExplorerEffect.ShowSnackbar(msg))
        }
    }

    private fun compressSelected(name: String, isZip: Boolean, password: String?, paneIndex: Int) {
        val pane = getPane(paneIndex)
        val activeTab = pane.activeTab ?: return
        val selected = _uiState.value.compressDialogSources ?: pane.selectedUris.toList()
        if (selected.isEmpty()) return

        val destDir = activeTab.currentUri
        fileOperationManager.enqueueCompress(
            sources = selected,
            destinationDir = destDir,
            archiveName = name,
            password = password
        )

        _uiState.update { it.copy(compressDialogSources = null) }
        clearSelection(paneIndex)
        viewModelScope.launch {
            val msg = runCatching { context.getString(R.string.operations_compress) }.getOrDefault("Compressing...")
            _effects.send(ExplorerEffect.ShowSnackbar(msg))
        }
    }

    private fun extractArchive(archiveUri: VfsUri, destinationDir: VfsUri) {
        fileOperationManager.enqueueExtract(archiveUri, destinationDir)
        viewModelScope.launch {
            val msg = runCatching { context.getString(R.string.operations_extract) }.getOrDefault("Extracting...")
            _effects.send(ExplorerEffect.ShowSnackbar(msg))
        }
    }

    private fun submitDirectArchivePassword(password: String, paneIndex: Int) {
        val uri = _uiState.value.pendingPasswordUri ?: return
        val pendingNode = _uiState.value.pendingPasswordNode
        archivePasswordStore.setPassword(uri, password)
        _uiState.update {
            it.copy(
                isPasswordDialogVisible = false,
                passwordError = null,
                pendingPasswordUri = null,
                pendingPasswordNode = null
            )
        }
        if (pendingNode != null) {
            openQuickLook(pendingNode)
        } else {
            loadDirectory(uri, paneIndex)
        }
    }

    private fun isTextOrCodeNode(node: VfsNode): Boolean {
        if (node.isDirectory) return false
        val mime = node.mimeType?.lowercase() ?: ""
        if (mime.startsWith("text/")) return true
        if (mime in setOf(
                "application/json",
                "application/xml",
                "application/javascript",
                "application/typescript",
                "application/x-sh",
                "application/x-yaml",
                "application/sql",
                "application/toml"
            )
        ) return true

        val ext = node.extension.lowercase()
        val textExtensions = setOf(
            "txt", "md", "markdown", "json", "xml", "html", "htm", "css",
            "js", "jsx", "ts", "tsx", "kt", "kts", "java", "c", "cpp", "cc",
            "h", "hpp", "py", "rs", "go", "rb", "sh", "bash", "zsh", "gradle",
            "properties", "toml", "yaml", "yml", "ini", "conf", "cfg", "log",
            "csv", "tsv", "sql", "env", "gitignore", "gitattributes", "pro"
        )
        return ext in textExtensions
    }

    private fun openQuickLook(node: VfsNode) {
        viewModelScope.launch {
            var preview: String? = null
            if (isTextOrCodeNode(node) && node.sizeBytes < 50L * 1024 * 1024) {
                preview = withContext(ioDispatcher) {
                    try {
                        vfsRouter.openInputStream(node.uri).use { stream ->
                            val buffer = ByteArray(8192)
                            val read = stream.read(buffer)
                            if (read > 0) {
                                val isBinary = (0 until read).any { buffer[it] == 0.toByte() }
                                if (!isBinary) {
                                    String(buffer, 0, read, Charsets.UTF_8)
                                } else null
                            } else null
                        }
                    } catch (e: VfsException.AuthenticationRequired) {
                        _uiState.update {
                            it.copy(
                                pendingPasswordUri = node.uri,
                                pendingPasswordNode = node,
                                isPasswordDialogVisible = true,
                                passwordError = if (archivePasswordStore.hasPassword(node.uri)) {
                                    context.getString(R.string.archive_password_wrong)
                                } else null
                            )
                        }
                        null
                    } catch (e: Exception) {
                        null
                    }
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
                }.getOrDefault(Pair("—", "—"))
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
            val msg = runCatching { context.getString(R.string.bookmark_added) }.getOrDefault("Added to Favorites")
            _effects.send(ExplorerEffect.ShowSnackbar(msg))
        }
    }

    private fun removeBookmark(uri: VfsUri) {
        viewModelScope.launch {
            metadataRepository.removeBookmark(uri)
            val msg = runCatching { context.getString(R.string.bookmark_removed) }.getOrDefault("Bookmark removed")
            _effects.send(ExplorerEffect.ShowSnackbar(msg))
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
