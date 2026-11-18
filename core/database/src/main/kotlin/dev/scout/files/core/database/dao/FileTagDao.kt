package dev.scout.files.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.scout.files.core.database.entity.FileTagCrossRefEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileTagDao {

    @Query("SELECT * FROM file_tags WHERE uri IN (:uris)")
    fun getTagsForUris(uris: List<String>): Flow<List<FileTagCrossRefEntity>>

    @Query("SELECT * FROM file_tags WHERE uri = :uri")
    fun getTagsForUri(uri: String): Flow<List<FileTagCrossRefEntity>>

    @Query("SELECT uri FROM file_tags WHERE tagColor = :tagColor")
    fun getUrisForTag(tagColor: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTag(crossRef: FileTagCrossRefEntity)

    @Query("DELETE FROM file_tags WHERE uri = :uri AND tagColor = :tagColor")
    suspend fun removeTag(uri: String, tagColor: String)

    @Query("DELETE FROM file_tags WHERE uri = :uri")
    suspend fun clearTagsForUri(uri: String)
}
