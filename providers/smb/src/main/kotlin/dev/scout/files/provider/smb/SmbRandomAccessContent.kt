package dev.scout.files.provider.smb

import com.hierynomus.smbj.share.File
import dev.scout.files.core.vfs.RandomAccessContent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * [RandomAccessContent] implementation backed by SMBJ [File].
 */
class SmbRandomAccessContent(
    private val file: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : RandomAccessContent {

    private var _position: Long = 0L

    override val length: Long
        get() = file.fileInformation.standardInformation.endOfFile

    override val position: Long
        get() = _position

    override suspend fun seek(position: Long) = withContext(dispatcher) {
        ensureActive()
        require(position >= 0) { "Negative seek position: $position" }
        _position = position
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = withContext(dispatcher) {
        ensureActive()
        if (length == 0) return@withContext 0
        val bytesRead = file.read(buffer, _position, offset, length)
        if (bytesRead > 0) {
            _position += bytesRead
        }
        bytesRead
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = withContext(dispatcher) {
        ensureActive()
        if (length == 0) return@withContext
        val bytesWritten = file.write(buffer, _position, offset, length)
        _position += bytesWritten
    }

    override suspend fun setLength(newLength: Long) = withContext(dispatcher) {
        ensureActive()
        require(newLength >= 0) { "Negative length: $newLength" }
        file.setLength(newLength)
    }

    override fun close() {
        file.close()
    }
}
