package dev.scout.files.feature.explorer.model

import dev.scout.files.core.database.repository.Bookmark
import dev.scout.files.core.model.FileTag
import dev.scout.files.core.model.StorageVolume
import dev.scout.files.core.model.TagColor
import dev.scout.files.core.model.VfsNode
import dev.scout.files.core.model.VfsUri
import dev.scout.files.provider.local.StandardDirectory
import java.util.UUID

enum class ViewMode { LIST, GRID }
enum class SortField { NAME, SIZE, DATE, TYPE }
enum class SortDirection { ASCENDING, DESCENDING }

data class SortConfig(
    val field: SortField = SortField.NAME,
    val direction: SortDirection = SortDirection.ASCENDING
)

data class ExplorerTab(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val currentUri: VfsUri,
    val backStack: List<VfsUri> = emptyList(),
    val forwardStack: List<VfsUri> = emptyList(),
    val selectedTagFilter: TagColor? = null
)

data class QuickLookState(
    val node: VfsNode,
    val textPreview: String? = null,
    val md5Checksum: String? = null,
    val sha256Checksum: String? = null,
    val isLoadingChecksum: Boolean = false
)

data class ExplorerPaneState(
    val tabs: List<ExplorerTab> = emptyList(),
    val activeTabId: String = "",
    val nodes: List<VfsNode> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val selectedUris: Set<VfsUri> = emptySet(),
    val nodeTags: Map<VfsUri, List<FileTag>> = emptyMap()
) {
    val activeTab: ExplorerTab?
        get() = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()

    fun getVisibleNodes(showHiddenFiles: Boolean, sortConfig: SortConfig): List<VfsNode> {
        var result = nodes

        if (!showHiddenFiles) {
            result = result.filterNot { it.isHidden }
        }

        val tagFilter = activeTab?.selectedTagFilter
        if (tagFilter != null) {
            result = result.filter { node ->
                nodeTags[node.uri]?.any { it.color == tagFilter } == true
            }
        }

        if (searchQuery.isNotBlank()) {
            result = result.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }

        val comparator = Comparator<VfsNode> { a, b ->
            if (a.isDirectory != b.isDirectory) {
                return@Comparator if (a.isDirectory) -1 else 1
            }
            val comp = when (sortConfig.field) {
                SortField.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                SortField.SIZE -> a.sizeBytes.compareTo(b.sizeBytes)
                SortField.DATE -> a.lastModifiedMillis.compareTo(b.lastModifiedMillis)
                SortField.TYPE -> (a.extension ?: "").compareTo(b.extension ?: "", ignoreCase = true)
            }
            if (sortConfig.direction == SortDirection.ASCENDING) comp else -comp
        }

        return result.sortedWith(comparator)
    }
}

data class ExplorerUiState(
    val primaryPane: ExplorerPaneState = ExplorerPaneState(),
    val secondaryPane: ExplorerPaneState? = null,
    val isDualPaneEnabled: Boolean = false,
    val focusedPaneIndex: Int = 0,
    val viewMode: ViewMode = ViewMode.LIST,
    val sortConfig: SortConfig = SortConfig(),
    val showHiddenFiles: Boolean = false,
    val volumes: List<StorageVolume> = emptyList(),
    val standardDirectories: List<StandardDirectory> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val quickLook: QuickLookState? = null,
    val hasStoragePermission: Boolean = true
) {
    fun visibleNodesForPane(paneIndex: Int = 0): List<VfsNode> {
        val pane = if (paneIndex == 1 && secondaryPane != null) secondaryPane else primaryPane
        return pane.getVisibleNodes(showHiddenFiles, sortConfig)
    }
}

sealed interface ExplorerIntent {
    data class NavigateTo(val uri: VfsUri, val paneIndex: Int = 0) : ExplorerIntent
    data class NavigateBack(val paneIndex: Int = 0) : ExplorerIntent
    data class OpenNewTab(val initialUri: VfsUri? = null, val paneIndex: Int = 0) : ExplorerIntent
    data class CloseTab(val tabId: String, val paneIndex: Int = 0) : ExplorerIntent
    data class SelectTab(val tabId: String, val paneIndex: Int = 0) : ExplorerIntent
    data object ToggleDualPane : ExplorerIntent
    data class SetFocusedPane(val paneIndex: Int) : ExplorerIntent
    data class SetSearchQuery(val query: String, val paneIndex: Int = 0) : ExplorerIntent
    data class SetViewMode(val viewMode: ViewMode) : ExplorerIntent
    data class SetSortConfig(val sortConfig: SortConfig) : ExplorerIntent
    data object ToggleShowHiddenFiles : ExplorerIntent
    data class ToggleSelectNode(val uri: VfsUri, val paneIndex: Int = 0) : ExplorerIntent
    data class SelectAll(val paneIndex: Int = 0) : ExplorerIntent
    data class ClearSelection(val paneIndex: Int = 0) : ExplorerIntent
    data class FilterByTag(val color: TagColor?, val paneIndex: Int = 0) : ExplorerIntent
    data class AssignTag(val uri: VfsUri, val color: TagColor) : ExplorerIntent
    data class CreateDirectory(val name: String, val paneIndex: Int = 0) : ExplorerIntent
    data class RenameNode(val oldUri: VfsUri, val newName: String, val paneIndex: Int = 0) : ExplorerIntent
    data class DeleteSelected(val paneIndex: Int = 0) : ExplorerIntent
    data class CopySelectedToAdjacentPane(val move: Boolean = false) : ExplorerIntent
    data class OpenQuickLook(val node: VfsNode) : ExplorerIntent
    data object ComputeQuickLookChecksums : ExplorerIntent
    data object CloseQuickLook : ExplorerIntent
    data class AddBookmark(val uri: VfsUri, val title: String) : ExplorerIntent
    data class RemoveBookmark(val uri: VfsUri) : ExplorerIntent
    data object RefreshVolumesAndPermissions : ExplorerIntent
}

sealed interface ExplorerEffect {
    data class ShowSnackbar(val message: String) : ExplorerEffect
    data class OpenFile(val node: VfsNode) : ExplorerEffect
    data object RequestStoragePermission : ExplorerEffect
}
