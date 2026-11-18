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
                    if (vol.isPrimary) {
                        context.getString(R.string.volume_internal)
                    } else {
                        context.getString(R.string.volume_external)
                    }
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

        // Secondary / Removable storage discovery for API 26-29 or fallback
        if (result.none { it.type == VolumeType.SD_CARD }) {
            val externalDirs = runCatching { context.getExternalFilesDirs(null) }.getOrNull() ?: emptyArray()
            for (extFile in externalDirs) {
                if (extFile == null) continue
                val fullPath = extFile.absolutePath
                if (fullPath.contains("/Android/")) {
                    val rootPath = fullPath.substringBefore("/Android/")
                    val rootDir = File(rootPath)
                    val isPrimary = Environment.getExternalStorageDirectory()?.absolutePath == rootPath
                    if (!isPrimary && rootDir.exists() && rootDir.canRead()) {
                        val statFs = runCatching { StatFs(rootDir.absolutePath) }.getOrNull()
                        val totalBytes = statFs?.totalBytes ?: 0L
                        val availableBytes = statFs?.availableBytes ?: 0L
                        result.add(
                            StorageVolume(
                                id = rootDir.name,
                                name = context.getString(R.string.volume_sd_card),
                                rootUri = VfsUri.fromFile(rootDir.absolutePath),
                                totalBytes = totalBytes,
                                availableBytes = availableBytes,
                                isRemovable = true,
                                isEmulated = false,
                                type = VolumeType.SD_CARD
                            )
                        )
                    }
                }
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
                        name = context.getString(R.string.volume_internal),
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
                title = context.getString(R.string.dir_home),
                uri = VfsUri.fromFile(primaryPath),
                iconKey = "home"
            )
        )

        fun addIfValid(dirType: String, stringResId: Int, iconKey: String) {
            val dir = Environment.getExternalStoragePublicDirectory(dirType)
            if (dir != null) {
                list.add(
                    StandardDirectory(
                        title = context.getString(stringResId),
                        uri = VfsUri.fromFile(dir.absolutePath),
                        iconKey = iconKey
                    )
                )
            }
        }

        addIfValid(Environment.DIRECTORY_DOWNLOADS, R.string.dir_downloads, "downloads")
        addIfValid(Environment.DIRECTORY_DOCUMENTS, R.string.dir_documents, "documents")
        addIfValid(Environment.DIRECTORY_PICTURES, R.string.dir_pictures, "pictures")
        addIfValid(Environment.DIRECTORY_DCIM, R.string.dir_camera, "camera")
        addIfValid(Environment.DIRECTORY_MUSIC, R.string.dir_music, "music")
        addIfValid(Environment.DIRECTORY_MOVIES, R.string.dir_movies, "movies")

        return list
    }
}
