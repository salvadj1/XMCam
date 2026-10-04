@file:OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)

package com.xmcam.ui

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.xmcam.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

/**
 * Reproductor EXPERIMENTAL de un clip de la SD por RTSP.
 * La URL sale de la plantilla de Ajustes de la app ([com.xmcam.data.Store.playbackTemplate]).
 * Al empezar a verse, guarda un fotograma como miniatura del clip (lo usa [PlaybackScreen]).
 *
 * @param camId id de la cámara.
 * @param begin inicio del clip, "yyyy-MM-dd HH:mm:ss".
 * @param end   fin del clip, "yyyy-MM-dd HH:mm:ss".
 * @param back  vuelve a la lista de grabaciones.
 */
@Composable
fun ClipPlayerScreen(camId: String, begin: String, end: String, back: () -> Unit) {
    val app = App.instance
    val cam = app.cameras.value.first { it.id == camId }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Conectando…") }
    var paused by remember { mutableStateOf(false) }
    var texture by remember { mutableStateOf<TextureView?>(null) }

    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            val url = cam.playbackUrl(app.store.playbackTemplate, begin, end)
            val src = RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(MediaItem.fromUri(url))
            setMediaSource(src); prepare(); playWhenReady = true
        }
    }
    DisposableEffect(player) {
        var captured = false
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                status = "No se pudo reproducir (${error.errorCodeName}). Revisa la dirección de reproducción en Ajustes de la app."
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_READY) return
                status = ""
                if (captured) return
                captured = true
                // Espera a que haya imagen y guarda un fotograma reducido como miniatura del clip.
                scope.launch {
                    delay(1500)
                    val bmp = texture?.bitmap ?: return@launch
                    withContext(Dispatchers.IO) {
                        val scaled = Bitmap.createScaledBitmap(bmp, 480, 270, true)
                        FileOutputStream(app.thumbFile(camId, begin)).use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                    }
                }
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }

    Scaffold(topBar = { Bar("${cam.name} · ${begin.takeLast(8)}", back) }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp)) {
            AndroidView(
                factory = { TextureView(it).also { v -> texture = v } },
                update = { player.setVideoTextureView(it) },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))
            )
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({ paused = !paused; if (paused) player.pause() else player.play() }) {
                    Text(if (paused) "Reproducir" else "Pausa")
                }
                OutlinedButton({ player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }) { Text("−10 s") }
                OutlinedButton({ player.seekTo(player.currentPosition + 10_000) }) { Text("+10 s") }
            }
            if (status.isNotEmpty()) Text(status, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall)
            Text(
                "Clip: $begin → ${end.takeLast(8)}", Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
