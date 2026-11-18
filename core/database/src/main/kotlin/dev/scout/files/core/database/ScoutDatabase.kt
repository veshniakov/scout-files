package dev.scout.files.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.scout.files.core.database.dao.BookmarkDao
import dev.scout.files.core.database.dao.FileTagDao
import dev.scout.files.core.database.entity.BookmarkEntity
import dev.scout.files.core.database.entity.FileTagCrossRefEntity

@Database(
    entities = [
        BookmarkEntity::class,
        FileTagCrossRefEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ScoutDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun fileTagDao(): FileTagDao
}
