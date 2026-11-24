@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package dev.securemessenger.data.media

import android.net.Uri
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.File

class EncryptedMediaDataSource(
    private val file: File,
    private val key: ByteArray,
    private val mediaUri: Uri,
) : BaseDataSource(false) {
    private var reader: SeekableMediaReader? = null
    private var position = 0L

    override fun open(dataSpec: DataSpec): Long {
        val opened = SeekableMediaReader(file, key)
        reader = opened
        position = dataSpec.position
        return (opened.plaintextBytes - position).coerceAtLeast(0)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val current = reader ?: return -1
        val read = current.read(position, buffer, offset, length)
        if (read > 0) position += read
        return read
    }

    override fun getUri(): Uri = mediaUri

    override fun close() {
        reader?.close()
        reader = null
    }
}

class EncryptedMediaDataSourceFactory(
    private val file: File,
    private val key: ByteArray,
) : androidx.media3.datasource.DataSource.Factory {
    override fun createDataSource(): androidx.media3.datasource.DataSource =
        EncryptedMediaDataSource(file, key, Uri.parse("encrypted://media/${file.nameWithoutExtension}"))
}
