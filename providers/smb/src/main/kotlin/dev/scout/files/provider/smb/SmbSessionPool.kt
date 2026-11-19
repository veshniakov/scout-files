package dev.scout.files.provider.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import dev.scout.files.core.model.AuthType
import dev.scout.files.core.model.ServerConnection
import dev.scout.files.core.model.ServerCredentials
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class CachedSmbSession(
    val client: SMBClient,
    val connection: Connection,
    val session: Session
) : Closeable {
    val isValid: Boolean
        get() = connection.isConnected

    override fun close() {
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }
}

@Singleton
open class SmbSessionPool(
    private val clientFactory: () -> SMBClient = {
        val config = SmbConfig.builder()
            .withMultiProtocolNegotiate(true)
            .build()
        SMBClient(config)
    }
) : Closeable {

    @Inject
    constructor() : this({
        val config = SmbConfig.builder()
            .withMultiProtocolNegotiate(true)
            .build()
        SMBClient(config)
    })

    private val sessions = ConcurrentHashMap<String, CachedSmbSession>()
    private val shares = ConcurrentHashMap<String, DiskShare>()

    open fun getDiskShare(
        connection: ServerConnection,
        credentials: ServerCredentials?,
        shareName: String
    ): DiskShare {
        val shareKey = "${connection.id}/$shareName"
        val existingShare = shares[shareKey]
        if (existingShare != null && existingShare.isConnected) {
            return existingShare
        }

        existingShare?.let { runCatching { it.close() } }
        shares.remove(shareKey)

        val session = getSession(connection, credentials)
        val newShare = session.session.connectShare(shareName) as DiskShare
        shares[shareKey] = newShare
        return newShare
    }

    private fun getSession(
        connection: ServerConnection,
        credentials: ServerCredentials?
    ): CachedSmbSession {
        val existing = sessions[connection.id]
        if (existing != null && existing.isValid) {
            return existing
        }

        existing?.close()
        sessions.remove(connection.id)

        val client = clientFactory()
        val conn = client.connect(connection.host, connection.port)
        val authContext = when (connection.authType) {
            AuthType.GUEST -> AuthenticationContext.guest()
            AuthType.PASSWORD -> {
                val password = credentials?.password ?: ""
                val domain = connection.domainOrRegion ?: ""
                AuthenticationContext(connection.username, password.toCharArray(), domain)
            }
            else -> throw IllegalArgumentException("Unsupported auth type for SMB: ${connection.authType}")
        }
        val session = conn.authenticate(authContext)
        val cachedSession = CachedSmbSession(client, conn, session)
        sessions[connection.id] = cachedSession
        return cachedSession
    }

    private fun isNonFatalFileError(e: SMBRuntimeException): Boolean {
        if (e is SMBApiException) {
            return when (e.status) {
                NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
                NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
                NtStatus.STATUS_NO_SUCH_FILE,
                NtStatus.STATUS_NOT_FOUND,
                NtStatus.STATUS_ACCESS_DENIED,
                NtStatus.STATUS_OBJECT_NAME_COLLISION,
                NtStatus.STATUS_OBJECT_NAME_INVALID,
                NtStatus.STATUS_FILE_IS_A_DIRECTORY,
                NtStatus.STATUS_NOT_A_DIRECTORY,
                NtStatus.STATUS_DIRECTORY_NOT_EMPTY,
                NtStatus.STATUS_CANNOT_DELETE,
                NtStatus.STATUS_FILE_DELETED,
                NtStatus.STATUS_FILE_CLOSED,
                NtStatus.STATUS_FILE_LOCK_CONFLICT,
                NtStatus.STATUS_SHARING_VIOLATION,
                NtStatus.STATUS_DELETE_PENDING,
                NtStatus.STATUS_END_OF_FILE,
                NtStatus.STATUS_NAME_TOO_LONG -> true
                else -> false
            }
        }
        return false
    }

    open fun <T> withShare(
        connection: ServerConnection,
        credentials: ServerCredentials?,
        shareName: String,
        block: (DiskShare) -> T
    ): T {
        val share = getDiskShare(connection, credentials, shareName)
        return try {
            block(share)
        } catch (e: SMBRuntimeException) {
            if (isNonFatalFileError(e)) {
                throw e
            }
            invalidate(connection.id)
            val reconnected = getDiskShare(connection, credentials, shareName)
            block(reconnected)
        }
    }

    open fun openFile(
        connection: ServerConnection,
        credentials: ServerCredentials?,
        shareName: String,
        path: String,
        accessMask: Set<AccessMask>,
        attributes: Set<FileAttributes>?,
        shareAccess: Set<SMB2ShareAccess>,
        createDisposition: SMB2CreateDisposition,
        createOptions: Set<SMB2CreateOptions>
    ): File {
        val share = getDiskShare(connection, credentials, shareName)
        return try {
            share.openFile(path, accessMask, attributes, shareAccess, createDisposition, createOptions)
        } catch (e: SMBRuntimeException) {
            if (isNonFatalFileError(e)) {
                throw e
            }
            invalidate(connection.id)
            val reconnected = getDiskShare(connection, credentials, shareName)
            reconnected.openFile(path, accessMask, attributes, shareAccess, createDisposition, createOptions)
        }
    }

    open fun invalidate(connectionId: String) {
        val prefix = "$connectionId/"
        val keysToRemove = shares.keys.filter { it.startsWith(prefix) }
        for (key in keysToRemove) {
            shares.remove(key)?.let { runCatching { it.close() } }
        }
        sessions.remove(connectionId)?.close()
    }

    override fun close() {
        shares.values.forEach { runCatching { it.close() } }
        shares.clear()
        sessions.values.forEach { it.close() }
        sessions.clear()
    }
}
