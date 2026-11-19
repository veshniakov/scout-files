package dev.scout.files.provider.ftp

import dev.scout.files.core.vfs.RandomAccessContent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTPClient
import java.io.IOException
import java.io.InputStream

class FtpRandomAccessContent(
    private val client: FTPClient,
    private val remotePath: String,
    override val length: Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onCloseCallback: (() -> Unit)? = null
) : RandomAccessContent {

    private var _position: Long = 0L
    private var currentStream: InputStream? = null
    private var isClosed = false

    override val position: Long
        get() = _position

    override suspend fun seek(position: Long): Unit = withContext(dispatcher) {
        ensureActive()
        if (isClosed) throw IOException("RandomAccessContent is closed")
        require(position >= 0) { "Negative position: $position" }
        if (position != _position) {
            closeCurrentStream()
            _position = position
        }
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = withContext(dispatcher) {
        ensureActive()
        if (isClosed) throw IOException("RandomAccessContent is closed")
        if (this@FtpRandomAccessContent.length in 1.._position) return@withContext -1

        if (currentStream == null) {
            client.setRestartOffset(_position)
            currentStream = client.retrieveFileStream(remotePath)
            if (currentStream == null) {
                return@withContext -1
            }
        }

        val bytesRead = currentStream!!.read(buffer, offset, length)
        if (bytesRead > 0) {
            _position += bytesRead
        } else if (bytesRead == -1) {
            closeCurrentStream()
        }
        bytesRead
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        throw UnsupportedOperationException("Random access write is not supported on FTP")
    }

    override suspend fun setLength(newLength: Long) {
        throw UnsupportedOperationException("setLength is not supported on FTP")
    }

    private fun closeCurrentStream() {
        currentStream?.let { stream ->
            try {
                stream.close()
            } catch (_: Exception) {}
            try {
                client.completePendingCommand()
            } catch (_: Exception) {}
            currentStream = null
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        closeCurrentStream()
        try {
            client.logout()
        } catch (_: Exception) {}
        try {
            if (client.isConnected) {
                client.disconnect()
            }
        } catch (_: Exception) {}
        onCloseCallback?.invoke()
    }
}
