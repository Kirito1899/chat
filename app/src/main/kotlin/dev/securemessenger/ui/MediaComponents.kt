package dev.securemessenger.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import dev.securemessenger.data.e2ee.MediaDescriptor
import dev.securemessenger.data.media.EncryptedMediaDataSourceFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@Composable
fun MediaMessageBubble(mediaJson: String, caption: String) {
    val descriptor = remember(mediaJson) {
        runCatching { Json.decodeFromString(MediaDescriptor.serializer(), mediaJson) }.getOrNull()
    }
    if (descriptor == null) {
        Text("Вложение недоступно", color = MaterialTheme.colorScheme.error)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            descriptor.mimeType.startsWith("video/") -> EncryptedVideoPlayer(descriptor)
            descriptor.mimeType.startsWith("audio/") -> EncryptedAudioPlayer(descriptor)
            descriptor.mimeType.startsWith("image/") -> EncryptedImage(descriptor)
            else -> EncryptedFileExport(descriptor)
        }
        if (caption.isNotBlank()) Text(caption)
    }
}

@Composable
private fun EncryptedImage(descriptor: MediaDescriptor) {
    val viewModel = hiltViewModel<MediaViewModel>()
    val loaded = produceState<MediaLoad<ByteArray>>(MediaLoad(), descriptor.mediaId) {
        value = try {
            MediaLoad(value = viewModel.readBytes(descriptor), loading = false)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            MediaLoad(loading = false, failed = true)
        }
    }.value
    when {
        loaded.loading -> MediaLoading()
        loaded.failed || loaded.value == null -> MediaLoadError()
        else -> {
            val bitmap = remember(loaded.value) {
                BitmapFactory.decodeByteArray(loaded.value, 0, loaded.value.size)
            }
            if (bitmap == null) {
                MediaLoadError()
            } else {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = descriptor.fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("media_image"),
                )
            }
        }
    }
}

@Composable
private fun EncryptedVideoPlayer(descriptor: MediaDescriptor) {
    val factory = rememberPlaybackFactory(descriptor) ?: return
    val context = LocalContext.current
    val player = remember(factory) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(factory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri("encrypted://${descriptor.mediaId}"))
                prepare()
            }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        modifier = Modifier.fillMaxWidth().height(260.dp).testTag("media_video"),
    )
}

@Composable
private fun EncryptedAudioPlayer(descriptor: MediaDescriptor) {
    val factory = rememberPlaybackFactory(descriptor) ?: return
    val context = LocalContext.current
    val player = remember(factory) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(factory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri("encrypted://${descriptor.mediaId}"))
                prepare()
            }
    }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { player.playWhenReady = !player.playWhenReady },
            modifier = Modifier.testTag("media_audio"),
        ) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (playing) "Пауза" else "Воспроизвести")
        }
        Text(descriptor.fileName, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EncryptedFileExport(descriptor: MediaDescriptor) {
    val viewModel = hiltViewModel<MediaViewModel>()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(descriptor.mimeType)) { uri ->
        if (uri != null) {
            scope.launch {
                state = try {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output ->
                        viewModel.export(descriptor, output)
                    }
                    "Файл сохранён"
                } catch (_: Exception) {
                    "Не удалось сохранить файл"
                }
            }
        }
    }
    Column {
        Text(descriptor.fileName, style = MaterialTheme.typography.bodyMedium)
        Text("${descriptor.plaintextBytes} байт", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { launcher.launch(descriptor.fileName) }, modifier = Modifier.testTag("save_media")) {
            Text("Сохранить файл")
        }
        state?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun rememberPlaybackFactory(descriptor: MediaDescriptor): EncryptedMediaDataSourceFactory? {
    val viewModel = hiltViewModel<MediaViewModel>()
    val loaded = produceState<MediaLoad<EncryptedMediaDataSourceFactory>>(MediaLoad(), descriptor.mediaId) {
        value = try {
            MediaLoad(value = viewModel.playbackFactory(descriptor), loading = false)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            MediaLoad(loading = false, failed = true)
        }
    }.value
    if (loaded.loading) return null
    if (loaded.failed) {
        MediaLoadError()
        return null
    }
    return loaded.value
}

@Composable
private fun MediaLoading() {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun MediaLoadError() {
    Text("Не удалось расшифровать вложение", color = MaterialTheme.colorScheme.error)
}

private data class MediaLoad<T>(
    val loading: Boolean = true,
    val value: T? = null,
    val failed: Boolean = false,
)


@Composable
fun AttachmentControls(onPick: (dev.securemessenger.data.media.MediaKind) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("attachment_button")) {
            Icon(Icons.Default.AttachFile, contentDescription = "Добавить вложение")
        }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Фото") },
                onClick = {
                    expanded = false
                    onPick(dev.securemessenger.data.media.MediaKind.PHOTO)
                },
                modifier = Modifier.testTag("pick_photo"),
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Видео") },
                onClick = {
                    expanded = false
                    onPick(dev.securemessenger.data.media.MediaKind.VIDEO)
                },
                modifier = Modifier.testTag("pick_video"),
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Файл") },
                onClick = {
                    expanded = false
                    onPick(dev.securemessenger.data.media.MediaKind.FILE)
                },
                modifier = Modifier.testTag("pick_file"),
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Стикер") },
                onClick = {
                    expanded = false
                    onPick(dev.securemessenger.data.media.MediaKind.STICKER)
                },
                modifier = Modifier.testTag("pick_sticker"),
            )
        }
    }
}
