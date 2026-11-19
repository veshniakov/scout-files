package dev.scout.files.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.scout.files.core.model.AuthType
import dev.scout.files.core.model.NetworkProtocol
import dev.scout.files.core.model.ServerConnection

@Entity(tableName = "server_connections")
data class ServerConnectionEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: String,
    val shareOrBucket: String?,
    val domainOrRegion: String?,
    val rootPath: String,
    val useTls: Boolean,
    val requireBiometric: Boolean,
    val lastConnectedMillis: Long?
)

fun ServerConnectionEntity.toDomain(): ServerConnection = ServerConnection(
    id = id,
    name = name,
    protocol = runCatching { NetworkProtocol.valueOf(protocol) }.getOrDefault(NetworkProtocol.SFTP),
    host = host,
    port = port,
    username = username,
    authType = runCatching { AuthType.valueOf(authType) }.getOrDefault(AuthType.PASSWORD),
    shareOrBucket = shareOrBucket,
    domainOrRegion = domainOrRegion,
    rootPath = rootPath,
    useTls = useTls,
    requireBiometric = requireBiometric,
    lastConnectedMillis = lastConnectedMillis
)

fun ServerConnection.toEntity(): ServerConnectionEntity = ServerConnectionEntity(
    id = id,
    name = name,
    protocol = protocol.name,
    host = host,
    port = port,
    username = username,
    authType = authType.name,
    shareOrBucket = shareOrBucket,
    domainOrRegion = domainOrRegion,
    rootPath = rootPath,
    useTls = useTls,
    requireBiometric = requireBiometric,
    lastConnectedMillis = lastConnectedMillis
)
