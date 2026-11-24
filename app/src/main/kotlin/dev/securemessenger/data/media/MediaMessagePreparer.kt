package dev.securemessenger.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject

enum class MediaKind {
    PHOTO,
    VIDEO,
    FILE,
    STICKER,
    VOICE,
}

data class MediaInput(
    val fileName: String,
    val mimeType: String,
    val size: Long?,
    val open: () -> InputStream,
    val cleanup: () -> Unit = {},
)

class MediaMessagePreparer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun fromUri(uri: Uri, kind: MediaKind): MediaInput = withContext(Dispatchers.IO) {
        if (kind == MediaKind.STICKER) return@withContext createSticker(uri)
        val metadata = metadata(uri)
        MediaInput(
            fileName = metadata.name,
            mimeType = metadata.mime,
            size = metadata.size,
            open = { requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open selected media" } },
        )
    }

    fun fromRecording(recording: RecordedVoice): MediaInput = MediaInput(
        fileName = recording.file.name,
        mimeType = recording.mimeType,
        size = recording.file.length(),
        open = { FileInputStream(recording.file) },
        cleanup = { recording.file.delete() },
    )

    private fun createSticker(uri: Uri): MediaInput {
        val bitmap = requireNotNull(context.contentResolver.openInputStream(uri)).use {
            BitmapFactory.decodeStream(it)
        } ?: throw IllegalArgumentException("Unsupported sticker image")
        val scale = minOf(1f, 512f / maxOf(bitmap.width, bitmap.height))
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (width != bitmap.width || height != bitmap.height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }
        val file = File(context.noBackupFilesDir, "stickers/${UUID.randomUUID()}.webp")
        file.parentFile?.mkdirs()
        file.outputStream().use { output ->
            require(scaled.compress(Bitmap.CompressFormat.WEBP, 90, output)) { "Cannot encode sticker" }
        }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return MediaInput(
            fileName = "sticker.webp",
            mimeType = "image/webp",
            size = file.length(),
            open = { FileInputStream(file) },
            cleanup = { file.delete() },
        )
    }

    private fun metadata(uri: Uri): MediaMetadata {
        var name = "file"
        var size: Long? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        return MediaMetadata(name, size, context.contentResolver.getType(uri) ?: "application/octet-stream")
    }

    private data class MediaMetadata(val name: String, val size: Long?, val mime: String)
}
