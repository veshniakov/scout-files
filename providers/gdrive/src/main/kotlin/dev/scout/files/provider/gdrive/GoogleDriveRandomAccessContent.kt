package dev.scout.files.provider.gdrive

import dev.scout.files.core.vfs.RandomAccessContent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

class GoogleDriveRandomAccessContent(
    override val length: Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val openRangeStream: suspend (start: Long) -> InputStream
) : RandomAccessContent {

    private var _position: Long = 0L
    private var streamPosition: Long = 0L
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
            streamPosition = position
        }
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = withContext(dispatcher) {
        ensureActive()
        if (isClosed) throw IOException("RandomAccessContent is closed")
        if (length <= 0) return@withContext 0
        if (_position >= this@GoogleDriveRandomAccessContent.length) return@withContext -1

        if (currentStream == null || streamPosition != _position) {
            closeCurrentStream()
            currentStream = openRangeStream(_position)
            streamPosition = _position
        }

        val bytesRead = currentStream!!.read(buffer, offset, length)
        if (bytesRead > 0) {
            _position += bytesRead
            streamPosition += bytesRead
        } else if (bytesRead == -1) {
            closeCurrentStream()
        }
        bytesRead
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        throw UnsupportedOperationException("Random access write is not supported on Google Drive content")
    }

    override suspend fun setLength(newLength: Long) {
        throw UnsupportedOperationException("setLength is not supported on Google Drive content")
    }

    private fun closeCurrentStream() {
        currentStream?.let { stream ->
            try {
                stream.close()
            } catch (_: Exception) {}
            currentStream = null
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        closeCurrentStream()
    }
}
