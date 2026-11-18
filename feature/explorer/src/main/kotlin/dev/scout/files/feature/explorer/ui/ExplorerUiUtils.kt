package dev.scout.files.feature.explorer.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.scout.files.core.model.StorageVolume
import dev.scout.files.core.model.TagColor
import dev.scout.files.core.model.VfsNode
import dev.scout.files.core.model.VolumeType
import dev.scout.files.feature.explorer.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExplorerUiUtils {

    fun formatFileSize(bytes: Long): String {
        val isRussian = Locale.getDefault().language.equals("ru", ignoreCase = true)
        val zeroUnit = if (isRussian) "0 Б" else "0 B"
        if (bytes <= 0L) return zeroUnit

        val units = if (isRussian) {
            arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        } else {
            arrayOf("B", "KB", "MB", "GB", "TB")
        }
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val index = digitGroups.coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, index.toDouble())
        return if (index == 0) {
            "${bytes} ${units[index]}"
        } else {
            String.format(Locale.getDefault(), "%.1f %s", value, units[index])
        }
    }

    @StringRes
    fun getTagDisplayName(tagColor: TagColor): Int {
        return when (tagColor) {
            TagColor.RED -> R.string.tag_red
            TagColor.ORANGE -> R.string.tag_orange
            TagColor.YELLOW -> R.string.tag_yellow
            TagColor.GREEN -> R.string.tag_green
            TagColor.BLUE -> R.string.tag_blue
            TagColor.PURPLE -> R.string.tag_purple
            TagColor.GRAY -> R.string.tag_gray
        }
    }

    @StringRes
    fun getDefaultBookmarkTitleRes(iconKey: String): Int? {
        return when (iconKey.lowercase()) {
            "home" -> R.string.dir_home
            "downloads" -> R.string.dir_downloads
            "documents" -> R.string.dir_documents
            "pictures" -> R.string.dir_pictures
            "camera" -> R.string.dir_camera
            "music" -> R.string.dir_music
            "movies" -> R.string.dir_movies
            else -> null
        }
    }

    fun formatDate(millis: Long): String {
        if (millis <= 0L) return "—"
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(millis))
    }

    fun getNodeIcon(node: VfsNode): ImageVector {
        if (node.isDirectory) return Icons.Default.Folder

        val ext = node.extension?.lowercase() ?: ""
        val mime = node.mimeType?.lowercase() ?: ""

        return when {
            mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "svg", "bmp") ->
                Icons.Default.Image

            mime.startsWith("video/") || ext in setOf("mp4", "mkv", "avi", "mov", "webm") ->
                Icons.Default.VideoFile

            mime.startsWith("audio/") || ext in setOf("mp3", "flac", "wav", "ogg", "m4a", "aac") ->
                Icons.Default.AudioFile

            ext == "pdf" ->
                Icons.Default.PictureAsPdf

            ext == "apk" ->
                Icons.Default.Android

            ext in setOf("zip", "tar", "gz", "bz2", "xz", "7z", "rar") ->
                Icons.Default.FolderZip

            ext in setOf("kt", "java", "xml", "json", "html", "css", "js", "ts", "py", "sh", "c", "cpp", "h", "gradle", "kts", "sql", "yaml", "yml", "md") ->
                Icons.Default.Code

            mime.startsWith("text/") || ext in setOf("txt", "log", "conf", "cfg") ->
                Icons.Default.Description

            else ->
                Icons.Default.InsertDriveFile
        }
    }

    fun getVolumeIcon(volume: StorageVolume): ImageVector {
        return when (volume.type) {
            VolumeType.INTERNAL, VolumeType.PRIMARY_STORAGE -> Icons.Default.Storage
            VolumeType.SD_CARD -> Icons.Default.SdStorage
            VolumeType.USB_DRIVE -> Icons.Default.Usb
            else -> Icons.Default.Storage
        }
    }

    fun getStandardDirIcon(iconKey: String): ImageVector {
        return when (iconKey.lowercase()) {
            "home" -> Icons.Default.Home
            "downloads" -> Icons.Default.Download
            "documents" -> Icons.Default.Description
            "pictures" -> Icons.Default.PhotoLibrary
            "camera" -> Icons.Default.CameraAlt
            "music" -> Icons.Default.MusicNote
            "movies" -> Icons.Default.Movie
            else -> Icons.Default.Folder
        }
    }

    fun getTagComposeColor(tagColor: TagColor): Color {
        return when (tagColor) {
            TagColor.RED -> Color(0xFFFF453A)
            TagColor.ORANGE -> Color(0xFFFF9F0A)
            TagColor.YELLOW -> Color(0xFFFFD60A)
            TagColor.GREEN -> Color(0xFF30D158)
            TagColor.BLUE -> Color(0xFF0A84FF)
            TagColor.PURPLE -> Color(0xFFBF5AF2)
            TagColor.GRAY -> Color(0xFF8E8E93)
        }
    }
}
