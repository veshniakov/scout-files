package dev.scout.files.feature.explorer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.scout.files.core.operations.model.FileOperationTask
import dev.scout.files.core.operations.model.FileOperationType
import dev.scout.files.core.operations.model.OperationStatus
import dev.scout.files.feature.explorer.R
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationsQueueSheet(
    tasks: List<FileOperationTask>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onClearFinished: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.operations_queue_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (tasks.any { it.isTerminal }) {
                        TextButton(onClick = onClearFinished) {
                            Text(stringResource(R.string.operations_clear_completed))
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.quick_look_close)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.operations_queue_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tasks, key = { it.id }) { task ->
                        OperationTaskCard(
                            task = task,
                            onPause = { onPause(task.id) },
                            onResume = { onResume(task.id) },
                            onCancel = { onCancel(task.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OperationTaskCard(
    task: FileOperationTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    val icon = when (task.type) {
                        FileOperationType.COPY -> Icons.Default.ContentCopy
                        FileOperationType.MOVE -> Icons.Default.DriveFileMove
                        FileOperationType.DELETE -> Icons.Default.Delete
                        FileOperationType.COMPRESS -> Icons.Default.Archive
                        FileOperationType.EXTRACT -> Icons.Default.Unarchive
                    }

                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )

                    val typeText = when (task.type) {
                        FileOperationType.COPY -> stringResource(R.string.operations_copy)
                        FileOperationType.MOVE -> stringResource(R.string.operations_move)
                        FileOperationType.DELETE -> stringResource(R.string.operations_delete)
                        FileOperationType.COMPRESS -> stringResource(R.string.operations_compress)
                        FileOperationType.EXTRACT -> stringResource(R.string.operations_extract)
                    }

                    Text(
                        text = "$typeText (${task.sources.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Status chip
                val statusText = when (task.status) {
                    OperationStatus.QUEUED -> "Queued"
                    OperationStatus.RUNNING -> stringResource(R.string.operations_status_running)
                    OperationStatus.PAUSED -> stringResource(R.string.operations_status_paused)
                    OperationStatus.WAITING_CONFLICT -> stringResource(R.string.operations_status_waiting_conflict)
                    OperationStatus.WAITING_PASSWORD -> stringResource(R.string.operations_status_waiting_password)
                    OperationStatus.COMPLETED -> stringResource(R.string.operations_status_completed)
                    OperationStatus.FAILED -> stringResource(R.string.operations_status_failed)
                    OperationStatus.CANCELLED -> stringResource(R.string.operations_status_cancelled)
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = when (task.status) {
                        OperationStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                        OperationStatus.FAILED -> MaterialTheme.colorScheme.error
                        OperationStatus.CANCELLED -> MaterialTheme.colorScheme.outline
                        else -> MaterialTheme.colorScheme.secondary
                    }
                )
            }

            // Current file and speed
            if (!task.currentFileName.isNullOrEmpty() || task.speedBytesPerSec > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = task.currentFileName ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )

                    if (task.speedBytesPerSec > 0 && task.status == OperationStatus.RUNNING) {
                        Text(
                            text = formatSpeed(task.speedBytesPerSec),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Progress bar
            if (!task.isTerminal) {
                if (task.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                    )
                }
            }

            // Controls for active task
            if (!task.isTerminal) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (task.status == OperationStatus.PAUSED) {
                        IconButton(onClick = onResume) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.operations_resume)
                            )
                        }
                    } else if (task.status == OperationStatus.RUNNING) {
                        IconButton(onClick = onPause) {
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = stringResource(R.string.operations_pause)
                            )
                        }
                    }

                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.operations_cancel),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return ""
    val kb = bytesPerSec / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.1f MB/s", mb)
    } else {
        String.format(Locale.US, "%.0f KB/s", kb)
    }
}
