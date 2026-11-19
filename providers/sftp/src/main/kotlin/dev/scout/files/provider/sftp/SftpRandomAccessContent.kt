package dev.scout.files.provider.sftp

import dev.scout.files.core.vfs.RandomAccessContent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.RemoteFile

/**
 * [RandomAccessContent] implementation backed by SSHJ [RemoteFile].
 */
class SftpRandomAccessContent(
    private val remoteFile: RemoteFile,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : RandomAccessContent {

    private var _position: Long = 0L

    override val length: Long
        get() = remoteFile.length()

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
        val bytesRead = remoteFile.read(_position, buffer, offset, length)
        if (bytesRead > 0) {
            _position += bytesRead
        }
        bytesRead
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = withContext(dispatcher) {
        ensureActive()
        if (length == 0) return@withContext
        remoteFile.write(_position, buffer, offset, length)
        _position += length
    }

    override suspend fun setLength(newLength: Long) = withContext(dispatcher) {
        ensureActive()
        require(newLength >= 0) { "Negative length: $newLength" }
        remoteFile.setLength(newLength)
    }

    override fun close() {
        remoteFile.close()
    }
}
