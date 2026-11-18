package dev.scout.files.core.vfs

import java.io.Closeable

/**
 * Interface for random-access content enabling seekable streaming for video/audio,
 * large archives, and hex editors.
 */
interface RandomAccessContent : Closeable {
    val length: Long
    val position: Long

    suspend fun seek(position: Long)
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
    suspend fun write(buffer: ByteArray, offset: Int, length: Int)
    suspend fun setLength(newLength: Long)
}
