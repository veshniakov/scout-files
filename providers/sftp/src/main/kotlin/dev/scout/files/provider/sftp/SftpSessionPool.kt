package dev.scout.files.provider.sftp

import dev.scout.files.core.model.AuthType
import dev.scout.files.core.model.ServerConnection
import dev.scout.files.core.model.ServerCredentials
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class SftpSession(
    val sshClient: SSHClient,
    val sftpClient: SFTPClient
) : Closeable {
    val isValid: Boolean
        get() = sshClient.isConnected && sshClient.isAuthenticated

    override fun close() {
        runCatching { sftpClient.close() }
        runCatching { sshClient.disconnect() }
        runCatching { sshClient.close() }
    }
}

@Singleton
open class SftpSessionPool(
    private val clientFactory: () -> SSHClient = { SSHClient() }
) : Closeable {

    @Inject
    constructor() : this({ SSHClient() })

    private val sessions = ConcurrentHashMap<String, SftpSession>()

    init {
        BouncyCastleInitializer.init()
    }

    open fun getSession(
        connection: ServerConnection,
        credentials: ServerCredentials?
    ): SftpSession {
        val existing = sessions[connection.id]
        if (existing != null && existing.isValid) {
            return existing
        }

        // Invalidate stale or non-connected session
        existing?.close()
        sessions.remove(connection.id)

        val newSession = createSession(connection, credentials)
        sessions[connection.id] = newSession
        return newSession
    }

    open fun getSftpClient(
        connection: ServerConnection,
        credentials: ServerCredentials?
    ): SFTPClient = getSession(connection, credentials).sftpClient

    protected open fun createSession(
        connection: ServerConnection,
        credentials: ServerCredentials?
    ): SftpSession {
        BouncyCastleInitializer.init()
        val ssh = clientFactory()
        ssh.addHostKeyVerifier(PromiscuousVerifier())
        ssh.connect(connection.host, connection.port)

        when (connection.authType) {
            AuthType.PASSWORD -> {
                val password = credentials?.password ?: ""
                ssh.authPassword(connection.username, password)
            }
            AuthType.SSH_KEY -> {
                val privateKeyPem = credentials?.privateKeyPem ?: ""
                val passphrase = credentials?.privateKeyPassphrase?.takeIf { it.isNotEmpty() }
                val passwordFinder = passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                val keyProvider = ssh.loadKeys(privateKeyPem, null, passwordFinder)
                ssh.authPublickey(connection.username, keyProvider)
            }
            else -> {
                throw IllegalArgumentException("Unsupported auth type for SFTP: ${connection.authType}")
            }
        }

        val sftp = ssh.newSFTPClient()
        return SftpSession(ssh, sftp)
    }

    open fun <T> execute(
        connection: ServerConnection,
        credentials: ServerCredentials?,
        block: (SFTPClient) -> T
    ): T {
        val session = getSession(connection, credentials)
        return try {
            block(session.sftpClient)
        } catch (e: TransportException) {
            invalidate(connection.id)
            val reconnected = getSession(connection, credentials)
            block(reconnected.sftpClient)
        }
    }

    open fun openRemoteFile(
        connection: ServerConnection,
        credentials: ServerCredentials?,
        remotePath: String,
        modes: Set<OpenMode>
    ): RemoteFile {
        val session = getSession(connection, credentials)
        return try {
            session.sftpClient.open(remotePath, modes)
        } catch (e: TransportException) {
            invalidate(connection.id)
            val reconnected = getSession(connection, credentials)
            reconnected.sftpClient.open(remotePath, modes)
        }
    }

    open fun invalidate(connectionId: String) {
        sessions.remove(connectionId)?.close()
    }

    override fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }
}
