package dev.scout.files.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import dev.scout.files.core.model.ArchiveUri
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.scout.files.core.designsystem.R
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.scout.files.core.model.VfsUri

/**
 * Interactive Path Bar inspired by Ubuntu Nautilus and macOS Finder.
 * Displays breadcrumb segments with seamless transition into a raw URI text field.
 */
@Composable
fun ScoutPathBar(
    currentUri: VfsUri,
    onNavigate: (VfsUri) -> Unit,
    modifier: Modifier = Modifier
) {
    var isEditing by remember { mutableStateOf(false) }
    var textInput by remember(currentUri) { mutableStateOf(currentUri.raw) }
    val focusRequester = remember { FocusRequester() }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        tonalElevation = 2.dp
    ) {
        AnimatedContent(
            targetState = isEditing,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "PathBarModeTransition"
        ) { editing ->
            if (editing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                if (textInput.isNotBlank()) {
                                    onNavigate(VfsUri(textInput.trim()))
                                }
                                isEditing = false
                            }
                        )
                    )

                    IconButton(
                        onClick = {
                            if (textInput.isNotBlank()) {
                                onNavigate(VfsUri(textInput.trim()))
                            }
                            isEditing = false
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Confirm",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(
                        onClick = {
                            textInput = currentUri.raw
                            isEditing = false
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel"
                        )
                    }
                }

                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val scrollState = rememberScrollState()

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(scrollState)
                            .clickable { isEditing = true },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (ArchiveUri.isArchiveUri(currentUri)) {
                            val parsed = ArchiveUri.parse(currentUri)
                            val sourceUri = parsed?.first
                            val internalPath = parsed?.second ?: "/"

                            // 1. Back to parent directory of archive
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.clickable {
                                    val parentUri = sourceUri?.parent ?: VfsUri.ROOT_FILE
                                    onNavigate(parentUri)
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Archive,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = stringResource(R.string.pathbar_archive).uppercase(),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }

                            // 2. Archive root file name
                            if (sourceUri != null) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                    contentDescription = null,
                                    modifier = Modifier.size(10.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                val isArchiveRoot = internalPath == "/" || internalPath.isEmpty()
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isArchiveRoot) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    modifier = Modifier.clickable {
                                        onNavigate(ArchiveUri.build(sourceUri, "/"))
                                    }
                                ) {
                                    Text(
                                        text = sourceUri.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isArchiveRoot) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isArchiveRoot) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }

                                // 3. Subdirectories inside archive
                                val internalSegments = internalPath.split('/').filter { it.isNotEmpty() }
                                var accumulatedInternal = ""
                                internalSegments.forEachIndexed { idx, segment ->
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                        contentDescription = null,
                                        modifier = Modifier.size(10.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    accumulatedInternal += "/$segment"
                                    val isLastSeg = idx == internalSegments.size - 1
                                    val targetUri = ArchiveUri.build(sourceUri, accumulatedInternal)

                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isLastSeg) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        modifier = Modifier.clickable {
                                            onNavigate(targetUri)
                                        }
                                    ) {
                                        Text(
                                            text = segment,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isLastSeg) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (isLastSeg) {
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            // Standard Root / Scheme Item
                            val rootTargetUri = if (!currentUri.authority.isNullOrEmpty()) {
                                VfsUri("${currentUri.scheme}://${currentUri.authority}/")
                            } else {
                                VfsUri("${currentUri.scheme}:///")
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.clickable {
                                    onNavigate(rootTargetUri)
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = currentUri.scheme.uppercase(),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }

                            // Path Segments
                            val segments = currentUri.segments
                            var accumulatedPath = ""

                            segments.forEachIndexed { index, segment ->
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                    contentDescription = null,
                                    modifier = Modifier.size(10.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                accumulatedPath += "/$segment"
                                val isLast = index == segments.size - 1
                                val targetUri = VfsUri(
                                    buildString {
                                        append("${currentUri.scheme}://")
                                        if (!currentUri.host.isNullOrEmpty()) {
                                            append(currentUri.host)
                                            if (currentUri.port != null) append(":${currentUri.port}")
                                        }
                                        append(accumulatedPath)
                                    }
                                )

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isLast) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    modifier = Modifier.clickable {
                                        onNavigate(targetUri)
                                    }
                                ) {
                                    Text(
                                        text = segment,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isLast) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    IconButton(onClick = { isEditing = true }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit URI",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
