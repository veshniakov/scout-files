package dev.scout.files.core.model

/**
 * macOS Finder style color tags.
 */
enum class TagColor(val hex: String, val displayNameEn: String, val displayNameRu: String) {
    RED("#FF453A", "Red", "Красный"),
    ORANGE("#FF9F0A", "Orange", "Оранжевый"),
    YELLOW("#FFD60A", "Yellow", "Желтый"),
    GREEN("#30D158", "Green", "Зеленый"),
    BLUE("#0A84FF", "Blue", "Синий"),
    PURPLE("#BF5AF2", "Purple", "Фиолетовый"),
    GRAY("#8E8E93", "Gray", "Серый")
}

data class FileTag(
    val id: String,
    val name: String,
    val color: TagColor
)
