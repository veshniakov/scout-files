package dev.scout.files.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey
    val uri: String,
    val title: String,
    val iconKey: String = "folder",
    val sortOrder: Int = 0,
    val isPinned: Boolean = true
)
