package dev.scout.files.feature.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.scout.files.core.model.TagColor
import dev.scout.files.feature.explorer.R

@Composable
fun SelectionActionModeBar(
    selectedCount: Int,
    isDualPane: Boolean,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onAssignTag: (TagColor) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopyAdjacent: () -> Unit,
    onMoveAdjacent: () -> Unit,
    onQuickLook: () -> Unit,
    modifier: Modifier = Modifier
) {
    var tagMenuExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onClearSelection) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.selection_cancel),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Text(
                text = stringResource(R.string.selection_count, selectedCount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )

            // Select All
            IconButton(onClick = onSelectAll) {
                Icon(
                    imageVector = Icons.Default.SelectAll,
                    contentDescription = stringResource(R.string.selection_select_all),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            // Assign Tag
            Box {
                IconButton(onClick = { tagMenuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.Label,
                        contentDescription = stringResource(R.string.selection_assign_tag),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                DropdownMenu(
                    expanded = tagMenuExpanded,
                    onDismissRequest = { tagMenuExpanded = false }
                ) {
                    TagColor.entries.forEach { tagColor ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(ExplorerUiUtils.getTagComposeColor(tagColor))
                                    )
                                    Text(stringResource(ExplorerUiUtils.getTagDisplayName(tagColor)))
                                }
                            },
                            onClick = {
                                onAssignTag(tagColor)
                                tagMenuExpanded = false
                            }
                        )
                    }
                }
            }

            // Quick Look if 1 selected
            if (selectedCount == 1) {
                IconButton(onClick = onQuickLook) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = stringResource(R.string.selection_quick_look),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                IconButton(onClick = onRename) {
                    Icon(
                        imageVector = Icons.Default.DriveFileRenameOutline,
                        contentDescription = stringResource(R.string.selection_rename),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Dual Pane actions: Copy / Move to adjacent pane
            if (isDualPane) {
                IconButton(onClick = onCopyAdjacent) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.selection_copy_adjacent),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                IconButton(onClick = onMoveAdjacent) {
                    Icon(
                        imageVector = Icons.Default.DriveFileMove,
                        contentDescription = stringResource(R.string.selection_move_adjacent),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Delete
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.selection_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
