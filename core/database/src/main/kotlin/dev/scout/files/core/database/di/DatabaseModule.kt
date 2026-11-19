package dev.scout.files.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.scout.files.core.database.ScoutDatabase
import dev.scout.files.core.database.dao.BookmarkDao
import dev.scout.files.core.database.dao.FileTagDao
import dev.scout.files.core.database.dao.ServerConnectionDao
import dev.scout.files.core.database.repository.MetadataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS server_connections (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    protocol TEXT NOT NULL,
                    host TEXT NOT NULL,
                    port INTEGER NOT NULL,
                    username TEXT NOT NULL,
                    authType TEXT NOT NULL,
                    shareOrBucket TEXT,
                    domainOrRegion TEXT,
                    rootPath TEXT NOT NULL,
                    useTls INTEGER NOT NULL,
                    requireBiometric INTEGER NOT NULL,
                    lastConnectedMillis INTEGER
                )
                """.trimIndent()
            )
        }
    }

    @Provides
    @Singleton
    fun provideScoutDatabase(
        @ApplicationContext context: Context
    ): ScoutDatabase {
        return Room.databaseBuilder(
            context,
            ScoutDatabase::class.java,
            "scout_metadata.db"
        )
        .addMigrations(MIGRATION_1_2)
        .fallbackToDestructiveMigration()
        .addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                // Insert initial default bookmarks on creation
                db.execSQL("INSERT OR IGNORE INTO bookmarks (uri, title, iconKey, sortOrder, isPinned) VALUES ('file:///storage/emulated/0', 'Домашняя папка', 'home', 0, 1)")
                db.execSQL("INSERT OR IGNORE INTO bookmarks (uri, title, iconKey, sortOrder, isPinned) VALUES ('file:///storage/emulated/0/Download', 'Загрузки', 'downloads', 1, 1)")
                db.execSQL("INSERT OR IGNORE INTO bookmarks (uri, title, iconKey, sortOrder, isPinned) VALUES ('file:///storage/emulated/0/Documents', 'Документы', 'documents', 2, 1)")
                db.execSQL("INSERT OR IGNORE INTO bookmarks (uri, title, iconKey, sortOrder, isPinned) VALUES ('file:///storage/emulated/0/Pictures', 'Изображения', 'pictures', 3, 1)")
            }
        }).build()
    }

    @Provides
    fun provideBookmarkDao(database: ScoutDatabase): BookmarkDao = database.bookmarkDao()

    @Provides
    fun provideFileTagDao(database: ScoutDatabase): FileTagDao = database.fileTagDao()

    @Provides
    fun provideServerConnectionDao(database: ScoutDatabase): ServerConnectionDao = database.serverConnectionDao()
}
