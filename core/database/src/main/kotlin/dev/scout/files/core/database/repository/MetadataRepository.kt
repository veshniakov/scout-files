package dev.scout.files.core.database.repository

import android.os.Environment
import dev.scout.files.core.database.dao.BookmarkDao
import dev.scout.files.core.database.dao.FileTagDao
import dev.scout.files.core.database.dao.ServerConnectionDao
import dev.scout.files.core.database.entity.BookmarkEntity
import dev.scout.files.core.database.entity.FileTagCrossRefEntity
import dev.scout.files.core.database.entity.toDomain
import dev.scout.files.core.database.entity.toEntity
import dev.scout.files.core.model.FileTag
import dev.scout.files.core.model.ServerConnection
import dev.scout.files.core.model.TagColor
import dev.scout.files.core.model.VfsUri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class Bookmark(
    val uri: VfsUri,
    val title: String,
    val iconKey: String = "folder",
    val sortOrder: Int = 0,
    val isPinned: Boolean = true
)

@Singleton
class MetadataRepository @Inject constructor(
    private val bookmarkDao: BookmarkDao,
    private val fileTagDao: FileTagDao,
    private val serverConnectionDao: ServerConnectionDao
) {

    suspend fun checkAndPrepopulateDefaults() {
        if (bookmarkDao.getCount() == 0) {
            val primaryPath = Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
            val defaults = listOf(
                BookmarkEntity(
                    uri = VfsUri.fromFile(primaryPath).raw,
                    title = "Домашняя папка",
                    iconKey = "home",
                    sortOrder = 0,
                    isPinned = true
                ),
                BookmarkEntity(
                    uri = VfsUri.fromFile("$primaryPath/Download").raw,
                    title = "Загрузки",
                    iconKey = "downloads",
                    sortOrder = 1,
                    isPinned = true
                ),
                BookmarkEntity(
                    uri = VfsUri.fromFile("$primaryPath/Documents").raw,
                    title = "Документы",
                    iconKey = "documents",
                    sortOrder = 2,
                    isPinned = true
                ),
                BookmarkEntity(
                    uri = VfsUri.fromFile("$primaryPath/Pictures").raw,
                    title = "Изображения",
                    iconKey = "pictures",
                    sortOrder = 3,
                    isPinned = true
                )
            )
            bookmarkDao.insertAll(defaults)
        }
    }

    fun getBookmarks(): Flow<List<Bookmark>> {
        return bookmarkDao.getAllBookmarks().map { entities ->
            entities.map { entity ->
                Bookmark(
                    uri = VfsUri(entity.uri),
                    title = entity.title,
                    iconKey = entity.iconKey,
                    sortOrder = entity.sortOrder,
                    isPinned = entity.isPinned
                )
            }
        }
    }

    suspend fun addBookmark(uri: VfsUri, title: String, iconKey: String = "folder") {
        bookmarkDao.insertOrUpdate(
            BookmarkEntity(
                uri = uri.raw,
                title = title,
                iconKey = iconKey,
                sortOrder = 99,
                isPinned = true
            )
        )
    }

    suspend fun removeBookmark(uri: VfsUri) {
        bookmarkDao.deleteByUri(uri.raw)
    }

    fun getTagsForUris(uris: List<VfsUri>): Flow<Map<VfsUri, List<FileTag>>> {
        val rawUris = uris.map { it.raw }
        return fileTagDao.getTagsForUris(rawUris).map { entities ->
            val result = mutableMapOf<VfsUri, MutableList<FileTag>>()
            for (entity in entities) {
                val color = runCatching { TagColor.valueOf(entity.tagColor.uppercase()) }.getOrNull()
                if (color != null) {
                    val uri = VfsUri(entity.uri)
                    val tag = FileTag(id = color.name, name = color.name, color = color)
                    result.getOrPut(uri) { mutableListOf() }.add(tag)
                }
            }
            result
        }
    }

    fun getTagsForUri(uri: VfsUri): Flow<List<FileTag>> {
        return fileTagDao.getTagsForUri(uri.raw).map { entities ->
            entities.mapNotNull { entity ->
                val color = runCatching { TagColor.valueOf(entity.tagColor.uppercase()) }.getOrNull()
                color?.let { FileTag(id = it.name, name = it.name, color = it) }
            }
        }
    }

    fun getUrisForTag(color: TagColor): Flow<List<VfsUri>> {
        return fileTagDao.getUrisForTag(color.name).map { rawList ->
            rawList.map { VfsUri(it) }
        }
    }

    suspend fun addTag(uri: VfsUri, color: TagColor) {
        fileTagDao.addTag(FileTagCrossRefEntity(uri = uri.raw, tagColor = color.name))
    }

    suspend fun removeTag(uri: VfsUri, color: TagColor) {
        fileTagDao.removeTag(uri = uri.raw, tagColor = color.name)
    }

    suspend fun toggleTag(uri: VfsUri, color: TagColor, hasTag: Boolean) {
        if (hasTag) {
            removeTag(uri, color)
        } else {
            addTag(uri, color)
        }
    }

    suspend fun clearTags(uri: VfsUri) {
        fileTagDao.clearTagsForUri(uri.raw)
    }

    fun getServerConnections(): Flow<List<ServerConnection>> {
        return serverConnectionDao.getAllConnections().map { list ->
            list.map { it.toDomain() }
        }
    }

    suspend fun getServerConnection(id: String): ServerConnection? {
        return serverConnectionDao.getConnectionById(id)?.toDomain()
    }

    suspend fun saveServerConnection(connection: ServerConnection) {
        serverConnectionDao.insertOrUpdate(connection.toEntity())
    }

    suspend fun deleteServerConnection(id: String) {
        serverConnectionDao.deleteById(id)
    }

    suspend fun updateServerConnectionLastConnected(id: String, timestamp: Long = System.currentTimeMillis()) {
        serverConnectionDao.updateLastConnected(id, timestamp)
    }
}
