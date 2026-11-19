package dev.scout.files.feature.explorer.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.fragment.app.FragmentActivity
import dev.scout.files.core.security.BiometricAuthChecker
import dev.scout.files.feature.connections.ui.ServerConnectionSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.scout.files.core.model.ArchiveUri
import dev.scout.files.core.model.VfsNode
import dev.scout.files.core.model.VfsUri
import dev.scout.files.feature.explorer.ExplorerViewModel
import dev.scout.files.feature.explorer.R
import dev.scout.files.feature.explorer.model.ExplorerEffect
import dev.scout.files.feature.explorer.model.ExplorerIntent
import dev.scout.files.feature.explorer.model.ExplorerPaneState
import dev.scout.files.feature.explorer.model.ExplorerUiState
import dev.scout.files.feature.explorer.model.ViewMode
import dev.scout.files.provider.local.StoragePermissionHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun ExplorerScreen(
    viewModel: ExplorerViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val biometricAuthChecker = remember(context) { BiometricAuthChecker(context.applicationContext) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.handleIntent(ExplorerIntent.RefreshVolumesAndPermissions)
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is ExplorerEffect.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is ExplorerEffect.OpenFile -> {
                    viewModel.handleIntent(ExplorerIntent.OpenQuickLook(effect.node))
                }
                is ExplorerEffect.RequestStoragePermission -> {
                    val intent = StoragePermissionHelper.createManageAllFilesAccessIntent(context)
                    permissionLauncher.launch(intent)
                }
                is ExplorerEffect.RequestBiometricAuth -> {
                    val activity = context as? FragmentActivity
                    if (activity != null) {
                        scope.launch {
                            val authSuccess = biometricAuthChecker.authenticate(
                                activity = activity,
                                title = effect.connection.name,
                                subtitle = context.getString(R.string.biometric_prompt_subtitle)
                            )
                            if (authSuccess) {
                                viewModel.handleIntent(
                                    ExplorerIntent.ConnectToServer(
                                        connection = effect.connection,
                                        isBiometricAuthenticated = true,
                                        paneIndex = effect.paneIndex
                                    )
                                )
                            } else {
                                snackbarHostState.showSnackbar(context.getString(R.string.biometric_auth_failed))
                            }
                        }
                    } else {
                        snackbarHostState.showSnackbar(context.getString(R.string.biometric_auth_failed))
                    }
                }
            }
        }
    }

    var showCreateDirDialog by remember { mutableStateOf(false) }
    var renameTargetNode by remember { mutableStateOf<VfsNode?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val activePaneIndex = uiState.focusedPaneIndex
    val activePane = if (activePaneIndex == 1 && uiState.secondaryPane != null) {
        uiState.secondaryPane!!
    } else {
        uiState.primaryPane
    }
    val activeTab = activePane.activeTab

    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600
    val defaultFolderTitle = stringResource(R.string.node_folder)

    val sidebarContent = @Composable {
        ExplorerSidebar(
            volumes = uiState.volumes,
            standardDirectories = uiState.standardDirectories,
            bookmarks = uiState.bookmarks,
            serverConnections = uiState.serverConnections,
            activeUri = activeTab?.currentUri,
            selectedTagFilter = activeTab?.selectedTagFilter,
            onNavigate = { uri ->
                viewModel.handleIntent(ExplorerIntent.NavigateTo(uri, activePaneIndex))
                scope.launch { drawerState.close() }
            },
            onConnectionClick = { connection ->
                viewModel.handleIntent(
                    ExplorerIntent.ConnectToServer(connection, paneIndex = activePaneIndex)
                )
                scope.launch { drawerState.close() }
            },
            onAddConnection = {
                viewModel.handleIntent(ExplorerIntent.OpenCreateConnectionSheet)
            },
            onEditConnection = { connection ->
                viewModel.handleIntent(ExplorerIntent.OpenEditConnectionSheet(connection))
            },
            onDeleteConnection = { connection ->
                viewModel.handleIntent(ExplorerIntent.RequestDeleteConnection(connection))
            },
            onTagSelected = { tagColor ->
                viewModel.handleIntent(ExplorerIntent.FilterByTag(tagColor, activePaneIndex))
                scope.launch { drawerState.close() }
            },
            onAddBookmark = {
                activeTab?.currentUri?.let { uri ->
                    viewModel.handleIntent(ExplorerIntent.AddBookmark(uri, uri.name.ifEmpty { defaultFolderTitle }))
                }
            },
            onRemoveBookmark = { uri ->
                viewModel.handleIntent(ExplorerIntent.RemoveBookmark(uri))
            }
        )
    }

    if (isWideScreen) {
        Row(modifier = modifier.fillMaxSize()) {
            sidebarContent()
            VerticalDivider()
            ExplorerMainScaffold(
                uiState = uiState,
                activePane = activePane,
                activeTab = activeTab,
                activePaneIndex = activePaneIndex,
                snackbarHostState = snackbarHostState,
                showNavigationIcon = false,
                onOpenDrawer = {},
                onRequestPermission = {
                    val intent = StoragePermissionHelper.createManageAllFilesAccessIntent(context)
                    permissionLauncher.launch(intent)
                },
                onIntent = viewModel::handleIntent,
                onCreateDirectory = { showCreateDirDialog = true },
                onRename = { node -> renameTargetNode = node },
                onDelete = { showDeleteConfirmDialog = true },
                modifier = Modifier.weight(1f)
            )
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    sidebarContent()
                }
            },
            modifier = modifier
        ) {
            ExplorerMainScaffold(
                uiState = uiState,
                activePane = activePane,
                activeTab = activeTab,
                activePaneIndex = activePaneIndex,
                snackbarHostState = snackbarHostState,
                showNavigationIcon = true,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onRequestPermission = {
                    val intent = StoragePermissionHelper.createManageAllFilesAccessIntent(context)
                    permissionLauncher.launch(intent)
                },
                onIntent = viewModel::handleIntent,
                onCreateDirectory = { showCreateDirDialog = true },
                onRename = { node -> renameTargetNode = node },
                onDelete = { showDeleteConfirmDialog = true },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    // Quick Look Modal Sheet
    uiState.quickLook?.let { qlState ->
        QuickLookSheet(
            state = qlState,
            onComputeChecksums = { viewModel.handleIntent(ExplorerIntent.ComputeQuickLookChecksums) },
            onDismiss = { viewModel.handleIntent(ExplorerIntent.CloseQuickLook) }
        )
    }

    // Dialogs
    if (showCreateDirDialog) {
        CreateDirectoryDialog(
            onDismiss = { showCreateDirDialog = false },
            onConfirm = { name ->
                viewModel.handleIntent(ExplorerIntent.CreateDirectory(name, activePaneIndex))
                showCreateDirDialog = false
            }
        )
    }

    renameTargetNode?.let { node ->
        RenameDialog(
            initialName = node.name,
            onDismiss = { renameTargetNode = null },
            onConfirm = { newName ->
                viewModel.handleIntent(ExplorerIntent.RenameNode(node.uri, newName, activePaneIndex))
                renameTargetNode = null
            }
        )
    }

    if (showDeleteConfirmDialog) {
        DeleteConfirmationDialog(
            itemCount = activePane.selectedUris.size,
            onDismiss = { showDeleteConfirmDialog = false },
            onConfirm = {
                viewModel.handleIntent(ExplorerIntent.DeleteSelected(activePaneIndex))
                showDeleteConfirmDialog = false
            }
        )
    }

    // Operations Queue Sheet
    if (uiState.isOperationsSheetVisible) {
        OperationsQueueSheet(
            tasks = uiState.operationsTasks,
            onPause = { taskId -> viewModel.handleIntent(ExplorerIntent.PauseOperation(taskId)) },
            onResume = { taskId -> viewModel.handleIntent(ExplorerIntent.ResumeOperation(taskId)) },
            onCancel = { taskId -> viewModel.handleIntent(ExplorerIntent.CancelOperation(taskId)) },
            onClearFinished = { viewModel.handleIntent(ExplorerIntent.ClearCompletedOperations) },
            onDismiss = { viewModel.handleIntent(ExplorerIntent.ToggleOperationsSheet(false)) }
        )
    }

    // File Conflict Dialog
    uiState.activeConflict?.let { conflict ->
        FileConflictDialog(
            fileName = conflict.targetUri.name,
            onDecision = { decision ->
                viewModel.handleIntent(ExplorerIntent.ResolveConflict(decision))
            }
        )
    }

    // Background Password Dialog
    uiState.activePasswordPrompt?.let { prompt ->
        ArchivePasswordDialog(
            archiveName = prompt.archiveUri.name,
            isWrongPassword = prompt.isWrongPassword,
            onDismiss = { viewModel.handleIntent(ExplorerIntent.SubmitArchivePassword(null)) },
            onSubmit = { pass -> viewModel.handleIntent(ExplorerIntent.SubmitArchivePassword(pass)) }
        )
    }

    // Direct Browser / QuickLook Password Dialog
    if (uiState.isPasswordDialogVisible) {
        ArchivePasswordDialog(
            archiveName = uiState.pendingPasswordUri?.name,
            isWrongPassword = uiState.passwordError != null,
            onDismiss = { viewModel.handleIntent(ExplorerIntent.DismissPasswordDialog) },
            onSubmit = { pass ->
                viewModel.handleIntent(ExplorerIntent.SubmitDirectArchivePassword(pass, activePaneIndex))
            }
        )
    }

    // Compress Dialog
    uiState.compressDialogSources?.let { sources ->
        val defaultName = sources.firstOrNull()?.name ?: "archive.zip"
        CompressDialog(
            initialName = defaultName,
            onDismiss = { viewModel.handleIntent(ExplorerIntent.HideCompressDialog) },
            onConfirm = { name, isZip, password ->
                viewModel.handleIntent(ExplorerIntent.CompressSelected(name, isZip, password, activePaneIndex))
            }
        )
    }

    // Connection Wizard Sheet
    if (uiState.isConnectionSheetVisible) {
        ServerConnectionSheet(
            connectionToEdit = uiState.editingConnection,
            onDismiss = { viewModel.handleIntent(ExplorerIntent.DismissConnectionSheet) },
            onSaved = { savedConnection ->
                viewModel.handleIntent(ExplorerIntent.DismissConnectionSheet)
                viewModel.handleIntent(
                    ExplorerIntent.ConnectToServer(savedConnection, paneIndex = activePaneIndex)
                )
            }
        )
    }

    // Delete Connection Dialog
    uiState.connectionToDelete?.let { conn ->
        AlertDialog(
            onDismissRequest = { viewModel.handleIntent(ExplorerIntent.DismissDeleteConnectionDialog) },
            title = { Text(stringResource(R.string.delete_connection_title)) },
            text = { Text(stringResource(R.string.delete_connection_message, conn.name)) },
            confirmButton = {
                Button(
                    onClick = { viewModel.handleIntent(ExplorerIntent.ConfirmDeleteConnection) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.dialog_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.handleIntent(ExplorerIntent.DismissDeleteConnectionDialog) }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }
}

@Composable
private fun ExplorerMainScaffold(
    uiState: ExplorerUiState,
    activePane: ExplorerPaneState,
    activeTab: dev.scout.files.feature.explorer.model.ExplorerTab?,
    activePaneIndex: Int,
    snackbarHostState: SnackbarHostState,
    showNavigationIcon: Boolean,
    onOpenDrawer: () -> Unit,
    onRequestPermission: () -> Unit,
    onIntent: (ExplorerIntent) -> Unit,
    onCreateDirectory: () -> Unit,
    onRename: (VfsNode) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            val hasSelection = activePane.selectedUris.isNotEmpty()
            val isInsideArchive = ArchiveUri.isArchiveUri(activeTab?.currentUri ?: VfsUri.ROOT_FILE)
            if (hasSelection) {
                SelectionActionModeBar(
                    selectedCount = activePane.selectedUris.size,
                    isDualPane = uiState.isDualPaneEnabled,
                    isInsideArchive = isInsideArchive,
                    onClearSelection = {
                        onIntent(ExplorerIntent.ClearSelection(activePaneIndex))
                    },
                    onSelectAll = {
                        onIntent(ExplorerIntent.SelectAll(activePaneIndex))
                    },
                    onAssignTag = { color ->
                        activePane.selectedUris.forEach { uri ->
                            onIntent(ExplorerIntent.AssignTag(uri, color))
                        }
                    },
                    onRename = {
                        val selectedUri = activePane.selectedUris.firstOrNull()
                        val node = activePane.nodes.find { it.uri == selectedUri }
                        if (node != null) onRename(node)
                    },
                    onDelete = onDelete,
                    onCopyAdjacent = {
                        onIntent(ExplorerIntent.CopySelectedToAdjacentPane(move = false))
                    },
                    onMoveAdjacent = {
                        onIntent(ExplorerIntent.CopySelectedToAdjacentPane(move = true))
                    },
                    onQuickLook = {
                        val selectedUri = activePane.selectedUris.firstOrNull()
                        val node = activePane.nodes.find { it.uri == selectedUri }
                        if (node != null) onIntent(ExplorerIntent.OpenQuickLook(node))
                    },
                    onCompress = {
                        onIntent(ExplorerIntent.ShowCompressDialog(activePane.selectedUris.toList()))
                    },
                    onExtract = {
                        val dest = if (uiState.isDualPaneEnabled && uiState.secondaryPane != null) {
                            val otherIndex = if (activePaneIndex == 0) 1 else 0
                            val otherPane = if (otherIndex == 1) uiState.secondaryPane else uiState.primaryPane
                            otherPane?.activeTab?.currentUri ?: VfsUri("file:///storage/emulated/0/Download")
                        } else {
                            val curUri = activeTab?.currentUri ?: VfsUri.ROOT_FILE
                            val srcUri = ArchiveUri.extractSourceUri(curUri)
                            srcUri?.parent ?: VfsUri("file:///storage/emulated/0/Download")
                        }
                        activePane.selectedUris.forEach { selUri ->
                            onIntent(ExplorerIntent.ExtractArchive(selUri, dest))
                        }
                        onIntent(ExplorerIntent.ClearSelection(activePaneIndex))
                    }
                )
            } else {
                ExplorerTopBar(
                    currentUri = activeTab?.currentUri ?: VfsUri("file:///storage/emulated/0"),
                    viewMode = uiState.viewMode,
                    sortConfig = uiState.sortConfig,
                    showHiddenFiles = uiState.showHiddenFiles,
                    isDualPaneEnabled = uiState.isDualPaneEnabled,
                    searchQuery = activePane.searchQuery,
                    showNavigationIcon = showNavigationIcon,
                    onNavigate = { uri -> onIntent(ExplorerIntent.NavigateTo(uri, activePaneIndex)) },
                    onOpenDrawer = onOpenDrawer,
                    onToggleViewMode = {
                        val newMode = if (uiState.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
                        onIntent(ExplorerIntent.SetViewMode(newMode))
                    },
                    onSetSortConfig = { cfg -> onIntent(ExplorerIntent.SetSortConfig(cfg)) },
                    onToggleShowHiddenFiles = { onIntent(ExplorerIntent.ToggleShowHiddenFiles) },
                    onToggleDualPane = { onIntent(ExplorerIntent.ToggleDualPane) },
                    onSetSearchQuery = { query -> onIntent(ExplorerIntent.SetSearchQuery(query, activePaneIndex)) },
                    onCreateDirectory = onCreateDirectory,
                    activeOperationsCount = uiState.activeOperationsCount,
                    onOpenOperationsQueue = { onIntent(ExplorerIntent.ToggleOperationsSheet(true)) }
                )
            }
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Storage permission banner if needed
            if (!uiState.hasStoragePermission) {
                PermissionBanner(onRequestPermission = onRequestPermission)
            }

            // Explorer Main Workspace (Single Pane or Dual Pane)
            if (uiState.isDualPaneEnabled && uiState.secondaryPane != null) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    // Left Pane (Primary)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .border(
                                width = if (uiState.focusedPaneIndex == 0) 2.dp else 0.5.dp,
                                color = if (uiState.focusedPaneIndex == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            )
                            .clickable { onIntent(ExplorerIntent.SetFocusedPane(0)) }
                    ) {
                        ExplorerPaneContent(
                            paneState = uiState.primaryPane,
                            paneIndex = 0,
                            uiState = uiState,
                            onIntent = onIntent
                        )
                    }

                    // Right Pane (Secondary)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .border(
                                width = if (uiState.focusedPaneIndex == 1) 2.dp else 0.5.dp,
                                color = if (uiState.focusedPaneIndex == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            )
                            .clickable { onIntent(ExplorerIntent.SetFocusedPane(1)) }
                    ) {
                        ExplorerPaneContent(
                            paneState = uiState.secondaryPane,
                            paneIndex = 1,
                            uiState = uiState,
                            onIntent = onIntent
                        )
                    }
                }
            } else {
                // Single Primary Pane
                ExplorerPaneContent(
                    paneState = uiState.primaryPane,
                    paneIndex = 0,
                    uiState = uiState,
                    onIntent = onIntent,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                )
            }
        }
    }
}

@Composable
fun ExplorerPaneContent(
    paneState: ExplorerPaneState,
    paneIndex: Int,
    uiState: ExplorerUiState,
    onIntent: (ExplorerIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    val visibleNodes = uiState.visibleNodesForPane(paneIndex)

    Column(modifier = modifier.fillMaxSize()) {
        // Tab Bar
        ExplorerTabBar(
            tabs = paneState.tabs,
            activeTabId = paneState.activeTabId,
            onSelectTab = { id -> onIntent(ExplorerIntent.SelectTab(id, paneIndex)) },
            onCloseTab = { id -> onIntent(ExplorerIntent.CloseTab(id, paneIndex)) },
            onNewTab = { onIntent(ExplorerIntent.OpenNewTab(null, paneIndex)) }
        )

        // Loading or Error or Content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            when {
                paneState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(44.dp)
                            .align(Alignment.Center)
                    )
                }

                paneState.error != null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = paneState.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = {
                            paneState.activeTab?.currentUri?.let { uri ->
                                onIntent(ExplorerIntent.NavigateTo(uri, paneIndex))
                            }
                        }) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(stringResource(R.string.retry))
                        }
                    }
                }

                visibleNodes.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = stringResource(R.string.empty_folder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                uiState.viewMode == ViewMode.LIST -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(visibleNodes, key = { it.uri.raw }) { node ->
                            val isSelected = paneState.selectedUris.contains(node.uri)
                            val tags = paneState.nodeTags[node.uri] ?: emptyList()

                            VfsNodeListItem(
                                node = node,
                                tags = tags,
                                isSelected = isSelected,
                                isSelectionMode = paneState.selectedUris.isNotEmpty(),
                                onClick = {
                                    if (paneState.selectedUris.isNotEmpty()) {
                                        onIntent(ExplorerIntent.ToggleSelectNode(node.uri, paneIndex))
                                    } else if (node.isDirectory) {
                                        onIntent(ExplorerIntent.NavigateTo(node.uri, paneIndex))
                                    } else if (ArchiveUri.isSupportedArchiveFile(node.name)) {
                                        onIntent(ExplorerIntent.NavigateTo(ArchiveUri.build(node.uri, "/"), paneIndex))
                                    } else {
                                        onIntent(ExplorerIntent.OpenQuickLook(node))
                                    }
                                },
                                onLongClick = {
                                    onIntent(ExplorerIntent.ToggleSelectNode(node.uri, paneIndex))
                                },
                                onQuickLook = {
                                    onIntent(ExplorerIntent.OpenQuickLook(node))
                                }
                            )
                        }
                    }
                }

                uiState.viewMode == ViewMode.GRID -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 100.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(visibleNodes, key = { it.uri.raw }) { node ->
                            val isSelected = paneState.selectedUris.contains(node.uri)
                            val tags = paneState.nodeTags[node.uri] ?: emptyList()

                            VfsNodeGridItem(
                                node = node,
                                tags = tags,
                                isSelected = isSelected,
                                isSelectionMode = paneState.selectedUris.isNotEmpty(),
                                onClick = {
                                    if (paneState.selectedUris.isNotEmpty()) {
                                        onIntent(ExplorerIntent.ToggleSelectNode(node.uri, paneIndex))
                                    } else if (node.isDirectory) {
                                        onIntent(ExplorerIntent.NavigateTo(node.uri, paneIndex))
                                    } else if (ArchiveUri.isSupportedArchiveFile(node.name)) {
                                        onIntent(ExplorerIntent.NavigateTo(ArchiveUri.build(node.uri, "/"), paneIndex))
                                    } else {
                                        onIntent(ExplorerIntent.OpenQuickLook(node))
                                    }
                                },
                                onLongClick = {
                                    onIntent(ExplorerIntent.ToggleSelectNode(node.uri, paneIndex))
                                },
                                onQuickLook = {
                                    onIntent(ExplorerIntent.OpenQuickLook(node))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
