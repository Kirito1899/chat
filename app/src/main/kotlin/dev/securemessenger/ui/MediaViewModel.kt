package dev.securemessenger.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.securemessenger.data.e2ee.MediaDescriptor
import dev.securemessenger.data.media.EncryptedMediaDataSourceFactory
import dev.securemessenger.data.media.MediaService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.Base64
import javax.inject.Inject

@HiltViewModel
class MediaViewModel @Inject constructor(
    private val media: MediaService,
) : ViewModel() {
    suspend fun readBytes(descriptor: MediaDescriptor): ByteArray = withContext(Dispatchers.IO) {
        media.readBytes(descriptor)
    }

    suspend fun playbackFactory(descriptor: MediaDescriptor): EncryptedMediaDataSourceFactory = withContext(Dispatchers.IO) {
        val file = media.existingCiphertext(descriptor) ?: media.download(descriptor)
        EncryptedMediaDataSourceFactory(file, Base64.getDecoder().decode(descriptor.key))
    }

    suspend fun export(descriptor: MediaDescriptor, output: OutputStream) = withContext(Dispatchers.IO) {
        media.decryptTo(descriptor, output)
    }
}
