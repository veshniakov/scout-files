package dev.scout.files.feature.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.scout.files.core.database.repository.Bookmark
import dev.scout.files.core.model.NetworkProtocol
import dev.scout.files.core.model.ServerConnection
import dev.scout.files.core.model.StorageVolume
import dev.scout.files.core.model.TagColor
import dev.scout.files.core.model.VfsUri
import androidx.compose.ui.res.stringResource
import dev.scout.files.feature.explorer.R
import dev.scout.files.provider.local.StandardDirectory

/**
 * macOS Finder and Ubuntu Nautilus inspired navigation sidebar.
 * Displays Devices with storage bars, Favorites with quick access, and Finder Tag filters.
 */
@Composable
fun ExplorerSidebar(
    volumes: List<StorageVolume>,
    standardDirectories: List<StandardDirectory>,
    bookmarks: List<Bookmark>,
    serverConnections: List<ServerConnection> = emptyList(),
    activeUri: VfsUri?,
    selectedTagFilter: TagColor?,
    onNavigate: (VfsUri) -> Unit,
    onConnectionClick: (ServerConnection) -> Unit = {},
    onAddConnection: () -> Unit = {},
    onEditConnection: (ServerConnection) -> Unit = {},
    onDeleteConnection: (ServerConnection) -> Unit = {},
    onTagSelected: (TagColor?) -> Unit,
    onAddBookmark: () -> Unit,
    onRemoveBookmark: (VfsUri) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(280.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(scrollState)
                .padding(vertical = 16.dp, horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header / App branding
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(8.dp)
                            .fillMaxWidth(),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Column {
                    Text(
                        text = "Scout",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.app_tagline),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = DividerDefaults.color.copy(alpha = 0.5f))

            // SECTION 1: DEVICES
            SidebarSectionHeader(title = stringResource(R.string.sidebar_devices))

            volumes.forEach { volume ->
                val isSelected = activeUri != null && activeUri.raw.startsWith(volume.rootUri.raw)
                val freeStr = ExplorerUiUtils.formatFileSize(volume.availableBytes)
                val totalStr = ExplorerUiUtils.formatFileSize(volume.totalBytes)

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigate(volume.rootUri) }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = ExplorerUiUtils.getVolumeIcon(volume),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = volume.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (volume.totalBytes > 0) {
                            LinearProgressIndicator(
                                progress = { volume.usedPercentage },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = if (volume.usedPercentage > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                strokeCap = StrokeCap.Round
                            )

                            Text(
                                text = stringResource(R.string.storage_free_of_total, freeStr, totalStr),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // SECTION 1.5: NETWORK & CLOUD
            HorizontalDivider(color = DividerDefaults.color.copy(alpha = 0.5f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SidebarSectionHeader(title = stringResource(R.string.sidebar_network_and_cloud))
                IconButton(
                    onClick = onAddConnection,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.add_connection),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            serverConnections.forEach { connection ->
                val rootUri = connection.rootVfsUri()
                val isSelected = activeUri != null && (activeUri == rootUri || activeUri.raw.startsWith(rootUri.raw))
                var menuExpanded by remember { mutableStateOf(false) }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onConnectionClick(connection) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProtocolBadge(connection.protocol)

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = connection.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (connection.requireBiometric) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = stringResource(R.string.connection_require_biometric),
                                        modifier = Modifier.size(13.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            val subtitle = when (connection.protocol) {
                                NetworkProtocol.SMB -> connection.shareOrBucket?.let { "$it on ${connection.host}" } ?: connection.host
                                NetworkProtocol.S3 -> connection.shareOrBucket?.let { "s3://$it" } ?: connection.host
                                NetworkProtocol.GDRIVE -> "Google Drive"
                                else -> if (connection.username.isNotEmpty()) "${connection.username}@${connection.host}" else connection.host
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Context Menu for Edit / Delete
                        Box {
                            IconButton(
                                onClick = { menuExpanded = true },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.edit_connection)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onEditConnection(connection)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete_connection)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onDeleteConnection(connection)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = DividerDefaults.color.copy(alpha = 0.5f))

            // SECTION 2: FAVORITES
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SidebarSectionHeader(title = stringResource(R.string.sidebar_favorites))
                IconButton(
                    onClick = onAddBookmark,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BookmarkAdd,
                        contentDescription = stringResource(R.string.add_to_favorites),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Bookmarks from Database
            bookmarks.forEach { bookmark ->
                val isSelected = activeUri == bookmark.uri
                val displayTitle = if (bookmark.isPinned) {
                    ExplorerUiUtils.getDefaultBookmarkTitleRes(bookmark.iconKey)?.let { stringResource(it) } ?: bookmark.title
                } else {
                    bookmark.title
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigate(bookmark.uri) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = ExplorerUiUtils.getStandardDirIcon(bookmark.iconKey),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (!bookmark.isPinned) {
                            IconButton(
                                onClick = { onRemoveBookmark(bookmark.uri) },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.remove_bookmark),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Standard folders fallback if bookmarks empty
            if (bookmarks.isEmpty()) {
                standardDirectories.forEach { dir ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (activeUri == dir.uri) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigate(dir.uri) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = ExplorerUiUtils.getStandardDirIcon(dir.iconKey),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = dir.title,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = DividerDefaults.color.copy(alpha = 0.5f))

            // SECTION 3: TAGS (Finder Tags)
            SidebarSectionHeader(title = stringResource(R.string.sidebar_tags))

            TagColor.entries.forEach { tagColor ->
                val isTagSelected = selectedTagFilter == tagColor
                val composeColor = ExplorerUiUtils.getTagComposeColor(tagColor)

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isTagSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (isTagSelected) onTagSelected(null) else onTagSelected(tagColor)
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(composeColor)
                        )
                        Text(
                            text = stringResource(ExplorerUiUtils.getTagDisplayName(tagColor)),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isTagSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isTagSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
fun ProtocolBadge(protocol: NetworkProtocol, modifier: Modifier = Modifier) {
    val (badgeText, containerColor, contentColor) = when (protocol) {
        NetworkProtocol.SFTP -> Triple("SFTP", Color(0xFF1565C0), Color.White)
        NetworkProtocol.SMB -> Triple("SMB", Color(0xFF2E7D32), Color.White)
        NetworkProtocol.FTP -> Triple("FTP", Color(0xFF00838F), Color.White)
        NetworkProtocol.FTPS -> Triple("FTPS", Color(0xFF00695C), Color.White)
        NetworkProtocol.WEBDAV -> Triple("DAV", Color(0xFFEF6C00), Color.White)
        NetworkProtocol.GDRIVE -> Triple("GDRV", Color(0xFFD32F2F), Color.White)
        NetworkProtocol.S3 -> Triple("S3", Color(0xFF6A1B9A), Color.White)
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = containerColor,
        modifier = modifier
    ) {
        Text(
            text = badgeText,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}
