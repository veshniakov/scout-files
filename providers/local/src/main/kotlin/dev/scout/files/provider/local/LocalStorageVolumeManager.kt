package dev.scout.files.provider.local

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.scout.files.core.model.StorageVolume
import dev.scout.files.core.model.VfsUri
import dev.scout.files.core.model.VolumeType
import dev.scout.files.core.vfs.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class StandardDirectory(
    val title: String,
    val uri: VfsUri,
    val iconKey: String
)

/**
 * Service for querying physical and virtual storage volumes and standard user directories.
 */
@Singleton
class LocalStorageVolumeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    private val storageManager: StorageManager by lazy {
        context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    }

    /**
     * Retrieves all mounted storage volumes with accurate capacity and free space metrics.
     */
    suspend fun getStorageVolumes(): List<StorageVolume> = withContext(ioDispatcher) {
        val result = mutableListOf<StorageVolume>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val volumes = storageManager.storageVolumes
            for (vol in volumes) {
                val dir = vol.directory ?: continue
                if (!dir.exists() || !dir.canRead()) continue

                val statFs = runCatching { StatFs(dir.absolutePath) }.getOrNull()
                val totalBytes = statFs?.totalBytes ?: 0L
                val availableBytes = statFs?.availableBytes ?: 0L

                val type = when {
                    vol.isPrimary -> VolumeType.PRIMARY_STORAGE
                    vol.isRemovable -> VolumeType.SD_CARD
                    else -> VolumeType.INTERNAL
                }

                val displayName = vol.getDescription(context).ifBlank {
                    if (vol.isPrimary) "Внутренняя память" else "Внешний накопитель"
                }

                result.add(
                    StorageVolume(
                        id = vol.uuid ?: "primary",
                        name = displayName,
                        rootUri = VfsUri.fromFile(dir.absolutePath),
                        totalBytes = totalBytes,
                        availableBytes = availableBytes,
                        isRemovable = vol.isRemovable,
                        isEmulated = vol.isEmulated,
                        type = type
                    )
                )
            }
        }

        // Fallback or guarantee for primary storage if not added
        if (result.none { it.type == VolumeType.PRIMARY_STORAGE }) {
            val primaryDir = Environment.getExternalStorageDirectory()
            if (primaryDir != null && primaryDir.exists()) {
                val statFs = runCatching { StatFs(primaryDir.absolutePath) }.getOrNull()
                result.add(
                    0,
                    StorageVolume(
                        id = "primary",
                        name = "Внутренняя память",
                        rootUri = VfsUri.fromFile(primaryDir.absolutePath),
                        totalBytes = statFs?.totalBytes ?: 0L,
                        availableBytes = statFs?.availableBytes ?: 0L,
                        isRemovable = false,
                        isEmulated = true,
                        type = VolumeType.PRIMARY_STORAGE
                    )
                )
            }
        }

        result
    }

    /**
     * Returns standard system folders (Home, Downloads, Documents, Pictures, Music, Movies, DCIM).
     */
    fun getStandardDirectories(): List<StandardDirectory> {
        val list = mutableListOf<StandardDirectory>()

        val primaryPath = Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
        list.add(
            StandardDirectory(
                title = "Домашняя папка",
                uri = VfsUri.fromFile(primaryPath),
                iconKey = "home"
            )
        )

        fun addIfValid(dirType: String, title: String, iconKey: String) {
            val dir = Environment.getExternalStoragePublicDirectory(dirType)
            if (dir != null) {
                list.add(
                    StandardDirectory(
                        title = title,
                        uri = VfsUri.fromFile(dir.absolutePath),
                        iconKey = iconKey
                    )
                )
            }
        }

        addIfValid(Environment.DIRECTORY_DOWNLOADS, "Загрузки", "downloads")
        addIfValid(Environment.DIRECTORY_DOCUMENTS, "Документы", "documents")
        addIfValid(Environment.DIRECTORY_PICTURES, "Изображения", "pictures")
        addIfValid(Environment.DIRECTORY_DCIM, "Фото (DCIM)", "camera")
        addIfValid(Environment.DIRECTORY_MUSIC, "Музыка", "music")
        addIfValid(Environment.DIRECTORY_MOVIES, "Видео", "movies")

        return list
    }
}
