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
import androidx.compose.ui.unit.dp
import dev.scout.files.core.designsystem.component.ScoutPathBar
import dev.scout.files.core.model.VfsUri
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
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Боковое меню"
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                if (isSearching) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSetSearchQuery,
                        singleLine = true,
                        placeholder = { Text("Поиск в текущей папке...") },
                        trailingIcon = {
                            IconButton(onClick = {
                                onSetSearchQuery("")
                                isSearching = false
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Закрыть поиск")
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
                        contentDescription = "Поиск"
                    )
                }
            }

            // View Mode Toggle (List / Grid)
            IconButton(onClick = onToggleViewMode) {
                Icon(
                    imageVector = if (viewMode == ViewMode.LIST) Icons.Default.GridView else Icons.Default.ViewList,
                    contentDescription = if (viewMode == ViewMode.LIST) "Режим сетки" else "Режим списка"
                )
            }

            // Sort Dropdown
            Box {
                IconButton(onClick = { sortMenuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.Sort,
                        contentDescription = "Сортировка"
                    )
                }

                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }
                ) {
                    SortMenuItem(
                        title = "По имени",
                        isSelected = sortConfig.field == SortField.NAME,
                        onClick = {
                            onSetSortConfig(sortConfig.copy(field = SortField.NAME))
                            sortMenuExpanded = false
                        }
                    )
                    SortMenuItem(
                        title = "По размеру",
                        isSelected = sortConfig.field == SortField.SIZE,
                        onClick = {
                            onSetSortConfig(sortConfig.copy(field = SortField.SIZE))
                            sortMenuExpanded = false
                        }
                    )
                    SortMenuItem(
                        title = "По дате изменения",
                        isSelected = sortConfig.field == SortField.DATE,
                        onClick = {
                            onSetSortConfig(sortConfig.copy(field = SortField.DATE))
                            sortMenuExpanded = false
                        }
                    )
                    SortMenuItem(
                        title = "По типу расширения",
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
                                    if (sortConfig.direction == SortDirection.ASCENDING) "По возрастанию" else "По убыванию"
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
                        contentDescription = "Еще"
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
                                Text("Создать папку")
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
                                Text(if (isDualPaneEnabled) "Отключить 2 панели" else "Включить 2 панели")
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
                                Text(if (showHiddenFiles) "Скрыть скрытые файлы" else "Показывать скрытые (.dotfiles)")
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
