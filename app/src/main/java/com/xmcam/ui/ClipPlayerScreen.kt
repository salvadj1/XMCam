@file:OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)

package com.xmcam.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import androidx.media3.ui.PlayerView
import com.xmcam.App
import com.xmcam.data.ClipRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File

/**
 * Reproductor de un clip de la SD. Descarga el clip por DVRIP, lo convierte a MP4 ([ClipRepo.playable])
 * y lo reproduce con controles propios siempre visibles: play/pausa, barra de tiempo con arrastre, ±10 s y velocidad.
 * Los clips ya vistos quedan en caché y se abren al instante.
 *
 * @param camId    id de la cámara.
 * @param fileName nombre del archivo en la SD (de la lista de grabaciones).
 * @param begin    inicio del clip, "yyyy-MM-dd HH:mm:ss".
 * @param end      fin del clip, "yyyy-MM-dd HH:mm:ss".
 * @param back     vuelve a la lista de grabaciones.
 */
@Composable
fun ClipPlayerScreen(camId: String, fileName: String, begin: String, end: String, back: () -> Unit) {
    val app = App.instance
    val cam = app.cameras.value.first { it.id == camId }
    var file by remember { mutableStateOf<File?>(null) }
    var bytes by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        error = null; file = null; bytes = 0
        try {
            var last = 0L
            file = ClipRepo.playable(app, cam, fileName, begin, end) { n ->
                val now = System.currentTimeMillis()
                if (now - last > 200) { last = now; bytes = n }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    Scaffold(topBar = { Bar("${cam.name} · ${begin.takeLast(8)}", back) }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp)) {
            val f = file
            if (f != null) ClipVideo(f)
            else Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    if (error == null) {
                        CircularProgressIndicator()
                        Text(
                            "Descargando clip… ${"%.1f".format(bytes / 1_048_576.0)} MB", Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "La cámara envía el clip a unos 5 Mbit/s; los largos tardan.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text("No se pudo cargar el clip", style = MaterialTheme.typography.titleSmall)
                        Text(error ?: "", Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                        Button({ attempt++ }) { Text("Reintentar") }
                    }
                }
            }
            Text(
                "Clip: $begin → ${end.takeLast(8)}", Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Vídeo con controles propios siempre visibles: play/pausa, barra de tiempo con arrastre, ±10 s y velocidad.
 * Reutilizable con cualquier archivo de vídeo local.
 * @param file archivo de vídeo (MP4) ya descargado.
 */
@Composable
private fun ClipVideo(file: File) {
    val ctx = LocalContext.current
    var playError by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var playing by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val player = remember(file) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            volume = 1f
            prepare(); playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playError = "Este móvil no puede reproducir el vídeo del clip (${error.errorCodeName}). Suele faltar el decodificador H.265."
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    // Actualiza la posición y la duración 4 veces por segundo (salvo mientras se arrastra la barra).
    LaunchedEffect(player) {
        while (true) {
            if (!dragging) pos = player.currentPosition
            if (dur <= 0L) dur = player.duration.coerceAtLeast(0L)
            delay(250)
        }
    }

    AndroidView(
        factory = { c -> PlayerView(c).apply { this.player = player; useController = false } },
        update = { it.player = player },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))
    )

    val fraction = if (dur > 0) (if (dragging) dragFraction else (pos.toFloat() / dur).coerceIn(0f, 1f)) else 0f
    Slider(
        value = fraction,
        onValueChange = { dragging = true; dragFraction = it },
        onValueChangeFinished = {
            val target = (dragFraction * dur).toLong()
            player.seekTo(target); pos = target; dragging = false
        },
        enabled = dur > 0, modifier = Modifier.padding(top = 4.dp)
    )
    Row(Modifier.fillMaxWidth()) {
        Text(formatMs(if (dragging) (dragFraction * dur).toLong() else pos), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.weight(1f))
        Text(formatMs(dur), style = MaterialTheme.typography.bodySmall)
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton({ player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }, Modifier.weight(1f)) { Text("−10 s", maxLines = 1) }
        Button({
            if (player.isPlaying) player.pause()
            else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() }
        }, Modifier.weight(1.4f)) { Text(if (playing) "Pausa" else "Reproducir", maxLines = 1) }
        OutlinedButton({ player.seekTo((player.currentPosition + 10_000).coerceAtMost(if (dur > 0) dur else Long.MAX_VALUE)) }, Modifier.weight(1f)) { Text("+10 s", maxLines = 1) }
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Velocidad", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodySmall)
        listOf(0.5f, 1f, 1.5f, 2f).forEach { s ->
            FilterChip(
                selected = speed == s, onClick = { speed = s; player.setPlaybackSpeed(s) },
                label = { Text(if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x") },
                modifier = Modifier.padding(end = 6.dp)
            )
        }
    }
    playError?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

/** Formatea [ms] milisegundos como "m:ss" (o "h:mm:ss" si pasa de una hora). */
private fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
