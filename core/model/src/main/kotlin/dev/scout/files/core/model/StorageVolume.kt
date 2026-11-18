package dev.scout.files.core.model

enum class VolumeType {
    INTERNAL,
    PRIMARY_STORAGE,
    SD_CARD,
    USB_DRIVE,
    NETWORK_SHARE,
    CLOUD
}

data class StorageVolume(
    val id: String,
    val name: String,
    val rootUri: VfsUri,
    val totalBytes: Long = 0L,
    val availableBytes: Long = 0L,
    val isRemovable: Boolean = false,
    val isEmulated: Boolean = false,
    val type: VolumeType = VolumeType.INTERNAL
) {
    val usedBytes: Long
        get() = (totalBytes - availableBytes).coerceAtLeast(0L)

    val usedPercentage: Float
        get() = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes.toFloat()) else 0f
}
