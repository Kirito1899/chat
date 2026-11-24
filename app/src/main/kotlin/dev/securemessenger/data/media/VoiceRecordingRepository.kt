package dev.securemessenger.data.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class RecordedVoice(
    val file: File,
    val mimeType: String,
)

@Singleton
class VoiceRecordingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var mimeType: String? = null

    val isRecording: Boolean
        get() = recorder != null

    fun start() {
        check(recorder == null) { "Voice recording is already active" }
        val opus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val file = File(context.cacheDir, "voice/${UUID.randomUUID()}.${if (opus) "ogg" else "m4a"}")
        file.parentFile?.mkdirs()
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        try {
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                if (opus) {
                    setOutputFormat(MediaRecorder.OutputFormat.OGG)
                    setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                } else {
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }
                setOutputFile(file)
                prepare()
                start()
            }
            output = file
            mimeType = if (opus) "audio/ogg" else "audio/mp4"
            recorder = mediaRecorder
        } catch (error: Throwable) {
            mediaRecorder.release()
            file.delete()
            throw error
        }
    }

    fun stop(): RecordedVoice {
        val current = recorder ?: error("No active voice recording")
        val file = output ?: error("Voice output is missing")
        val mime = mimeType ?: error("Voice MIME type is missing")
        try {
            current.stop()
        } catch (error: Throwable) {
            file.delete()
            throw error
        } finally {
            current.release()
            recorder = null
            output = null
            mimeType = null
        }
        return RecordedVoice(file, mime)
    }

    fun cancel() {
        val current = recorder
        val file = output
        try {
            current?.stop()
        } catch (_: Exception) {
        } finally {
            current?.release()
            recorder = null
            output = null
            mimeType = null
        }
        file?.delete()
    }
}
