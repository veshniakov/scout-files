package dev.scout.files.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.scout.files.core.database.dao.BookmarkDao
import dev.scout.files.core.database.dao.FileTagDao
import dev.scout.files.core.database.dao.ServerConnectionDao
import dev.scout.files.core.database.entity.BookmarkEntity
import dev.scout.files.core.database.entity.FileTagCrossRefEntity
import dev.scout.files.core.database.entity.ServerConnectionEntity

@Database(
    entities = [
        BookmarkEntity::class,
        FileTagCrossRefEntity::class,
        ServerConnectionEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class ScoutDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun fileTagDao(): FileTagDao
    abstract fun serverConnectionDao(): ServerConnectionDao
}
