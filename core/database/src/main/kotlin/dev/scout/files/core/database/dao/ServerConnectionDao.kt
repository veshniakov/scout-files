package dev.scout.files.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.scout.files.core.database.entity.ServerConnectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerConnectionDao {

    @Query("SELECT * FROM server_connections ORDER BY name ASC")
    fun getAllConnections(): Flow<List<ServerConnectionEntity>>

    @Query("SELECT * FROM server_connections WHERE id = :id")
    suspend fun getConnectionById(id: String): ServerConnectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: ServerConnectionEntity)

    @Query("DELETE FROM server_connections WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE server_connections SET lastConnectedMillis = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: String, timestamp: Long)

    @Query("SELECT COUNT(*) FROM server_connections")
    suspend fun getCount(): Int
}
