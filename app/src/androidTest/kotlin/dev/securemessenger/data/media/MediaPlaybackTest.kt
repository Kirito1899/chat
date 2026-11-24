package dev.securemessenger.data.media

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaPlaybackTest {
    @Test
    fun exoplayer_plays_locally_decrypted_audio() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val plaintext = wavBytes()
        val encrypted = Files.createTempFile("playback-", ".ciphertext").toFile()
        val key = MediaCipher.newKey()
        plaintext.inputStream().use { input ->
            encrypted.outputStream().use { output -> MediaCipher.encrypt(input, output, key, plaintext.size.toLong()) }
        }
        val ready = CountDownLatch(1)
        val failure = AtomicReference<PlaybackException?>()
        var player: ExoPlayer? = null
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                player = ExoPlayer.Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(EncryptedMediaDataSourceFactory(encrypted, key)))
                    .build()
                player!!.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) ready.countDown()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        failure.set(error)
                        ready.countDown()
                    }
                })
                player!!.setMediaItem(MediaItem.fromUri("encrypted://voice"))
                player!!.prepare()
            }

            assertTrue(ready.await(15, TimeUnit.SECONDS), "ExoPlayer did not become ready")
            assertNull(failure.get())
            assertNotNull(player)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertTrue(player!!.duration > 0)
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { player?.release() }
            encrypted.delete()
        }
    }

    private fun wavBytes(): ByteArray {
        val sampleRate = 8_000
        val samples = 2_000
        val pcm = ByteArrayOutputStream(samples * 2)
        repeat(samples) { index ->
            val value = (sin(index * 0.05) * 4000).toInt().toShort()
            pcm.write(value.toInt() and 0xFF)
            pcm.write(value.toInt() shr 8 and 0xFF)
        }
        val data = pcm.toByteArray()
        val output = ByteArrayOutputStream(44 + data.size)
        output.write("RIFF".toByteArray())
        output.writeLeInt(36 + data.size)
        output.write("WAVEfmt ".toByteArray())
        output.writeLeInt(16)
        output.writeLeShort(1)
        output.writeLeShort(1)
        output.writeLeInt(sampleRate)
        output.writeLeInt(sampleRate * 2)
        output.writeLeShort(2)
        output.writeLeShort(16)
        output.write("data".toByteArray())
        output.writeLeInt(data.size)
        output.write(data)
        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLeInt(value: Int) {
        repeat(4) { write((value shr (it * 8)) and 0xFF) }
    }

    private fun ByteArrayOutputStream.writeLeShort(value: Int) {
        repeat(2) { write((value shr (it * 8)) and 0xFF) }
    }
}
