package dev.securemessenger.data.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceRecordingRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: VoiceRecordingRepository

    @BeforeTest
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = VoiceRecordingRepository(context)
    }

    @AfterTest
    fun tearDown() {
        repository.cancel()
    }

    @Test
    fun short_voice_recording_is_created() {
        repository.start()
        Thread.sleep(350)
        val recording = repository.stop()

        assertTrue(repository.isRecording.not())
        assertTrue(recording.file.isFile)
        assertTrue(recording.file.length() > 0)
        assertTrue(recording.mimeType == "audio/ogg" || recording.mimeType == "audio/mp4")
        recording.file.delete()
    }
}
