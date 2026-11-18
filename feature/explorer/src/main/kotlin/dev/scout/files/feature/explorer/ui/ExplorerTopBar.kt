package dev.scout.files.feature.explorer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.scout.files.core.designsystem.component.ScoutPathBar
import dev.scout.files.core.model.VfsUri
import dev.scout.files.feature.explorer.R
import dev.scout.files.feature.explorer.model.SortConfig
import dev.scout.files.feature.explorer.model.SortDirection
import dev.scout.files.feature.explorer.model.SortField
import dev.scout.files.feature.explorer.model.ViewMode

@Composable
fun ExplorerTopBar(
    currentUri: VfsUri,
    viewMode: ViewMode,
    sortConfig: SortConfig,
    showHiddenFiles: Boolean,
    isDualPaneEnabled: Boolean,
    searchQuery: String,
    onNavigate: (VfsUri) -> Unit,
    onOpenDrawer: () -> Unit,
    onToggleViewMode: () -> Unit,
    onSetSortConfig: (SortConfig) -> Unit,
    onToggleShowHiddenFiles: () -> Unit,
    onToggleDualPane: () -> Unit,
    onSetSearchQuery: (String) -> Unit,
    onCreateDirectory: () -> Unit,
    showNavigationIcon: Boolean = true,
    modifier: Modifier = Modifier
) {
    var isSearching by remember { mutableStateOf(searchQuery.isNotEmpty()) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var moreMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showNavigationIcon) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = stringResource(R.string.topbar_menu)
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                if (isSearching) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSetSearchQuery,
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.topbar_search_hint)) },
                        trailingIcon = {
                            IconButton(onClick = {
                                onSetSearchQuery("")
                                isSearching = false
                            }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.topbar_close_search))
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .padding(horizontal = 4.dp)
                    )
                } else {
                    ScoutPathBar(
                        currentUri = currentUri,
                        onNavigate = onNavigate
                    )
                }
            }

            // Search Button
            if (!isSearching) {
                IconButton(onClick = { isSearching = true }) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.topbar_search)
                    )
                }
            }

            // View Mode Toggle (List / Grid)
            IconButton(onClick = onToggleViewMode) {
                Icon(
                    imageVector = if (viewMode == ViewMode.LIST) Icons.Default.GridView else Icons.Default.ViewList,
                    contentDescription = if (viewMode == ViewMode.LIST) stringResource(R.string.topbar_grid_mode) else stringResource(R.string.topbar_list_mode)
                )
            }

            // Sort Dropdown
            Box {
                IconButton(onClick = { sortMenuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.Sort,
                        contentDescription = stringResource(R.string.topbar_sort)
                    )
                }

                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }
                ) {
                    SortMenuItem(
                        title = stringResource(R.string.sort_by_name),
                        isSelected = sortConfig.field == SortField.NAME,
                        onClick = {
                            onSetSortConfig(sortConfig.copy(field = SortField.NAME))
                            sortMenuExpanded = false
                        }
                    )
                    SortMenuItem(
                        title = stringResource(R.string.sort_by_size),
                        isSelected = sortConfig.field == SortField.SIZE,
                        onClick = {
                            onSetSortConfig(sortConfig.copy(field = SortField.SIZE))
                            sortMenuExpanded = false
                        }
                    )
                    SortMenuItem(
                        title = stringResource(R.string.sort_by_date),
                        isSelected = sortConfig.field == SortField.DATE,
                        onClick = {
                            onSetSortConfig(sortConfig.copy(field = SortField.DATE))
                            sortMenuExpanded = false
                        }
                    )
                    SortMenuItem(
                        title = stringResource(R.string.sort_by_type),
                        isSelected = sortConfig.field == SortField.TYPE,
                        onClick = {
                            onSetSortConfig(sortConfig.copy(field = SortField.TYPE))
                            sortMenuExpanded = false
                        }
                    )

                    HorizontalDivider()

                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (sortConfig.direction == SortDirection.ASCENDING) {
                                        Icons.Default.ArrowUpward
                                    } else {
                                        Icons.Default.ArrowDownward
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    if (sortConfig.direction == SortDirection.ASCENDING) {
                                        stringResource(R.string.sort_ascending)
                                    } else {
                                        stringResource(R.string.sort_descending)
                                    }
                                )
                            }
                        },
                        onClick = {
                            val newDir = if (sortConfig.direction == SortDirection.ASCENDING) {
                                SortDirection.DESCENDING
                            } else {
                                SortDirection.ASCENDING
                            }
                            onSetSortConfig(sortConfig.copy(direction = newDir))
                            sortMenuExpanded = false
                        }
                    )
                }
            }

            // More Menu
            Box {
                IconButton(onClick = { moreMenuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.topbar_more)
                    )
                }

                DropdownMenu(
                    expanded = moreMenuExpanded,
                    onDismissRequest = { moreMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CreateNewFolder,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(stringResource(R.string.action_create_folder))
                            }
                        },
                        onClick = {
                            onCreateDirectory()
                            moreMenuExpanded = false
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VerticalSplit,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    if (isDualPaneEnabled) {
                                        stringResource(R.string.action_disable_dual_pane)
                                    } else {
                                        stringResource(R.string.action_enable_dual_pane)
                                    }
                                )
                            }
                        },
                        onClick = {
                            onToggleDualPane()
                            moreMenuExpanded = false
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (showHiddenFiles) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    if (showHiddenFiles) {
                                        stringResource(R.string.action_hide_hidden)
                                    } else {
                                        stringResource(R.string.action_show_hidden)
                                    }
                                )
                            }
                        },
                        onClick = {
                            onToggleShowHiddenFiles()
                            moreMenuExpanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SortMenuItem(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title)
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Выбрано",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        },
        onClick = onClick
    )
}
