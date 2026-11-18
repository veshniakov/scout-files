package dev.scout.files.core.database.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "file_tags",
    primaryKeys = ["uri", "tagColor"],
    indices = [Index(value = ["uri"]), Index(value = ["tagColor"])]
)
data class FileTagCrossRefEntity(
    val uri: String,
    val tagColor: String
)
