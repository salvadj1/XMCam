@file:OptIn(UnstableApi::class)

package com.xmcam.ui

import android.content.Context
import android.graphics.Bitmap
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream

/**
 * Genera la miniatura de un vídeo RTSP: lo reproduce en silencio unos instantes sobre [texture],
 * captura un fotograma y lo guarda como JPEG en [out]. Reutilizable con cualquier URL RTSP.
 *
 * Debe llamarse desde una corrutina; el reproductor se crea y se libera siempre aquí dentro.
 * [texture] tiene que estar visible en pantalla (puede ser diminuta) para que reciba imagen.
 *
 * @param ctx       contexto para crear el reproductor.
 * @param url       dirección RTSP del vídeo.
 * @param texture   superficie donde se dibuja el vídeo y de la que se lee el fotograma.
 * @param out       archivo JPEG de salida.
 * @param timeoutMs tiempo máximo esperando a que el vídeo empiece (ms).
 * @return true si se guardó una miniatura válida; false si falló o el fotograma era negro vacío.
 */
suspend fun captureRtspThumbnail(ctx: Context, url: String, texture: TextureView, out: File, timeoutMs: Long = 9000): Boolean =
    withContext(Dispatchers.Main) {
        val player = ExoPlayer.Builder(ctx).build()
        try {
            val ready = CompletableDeferred<Boolean>()
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) ready.complete(true) }
                override fun onPlayerError(error: PlaybackException) { ready.complete(false) }
            })
            player.setVideoTextureView(texture)
            player.setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(MediaItem.fromUri(url)))
            player.volume = 0f
            player.prepare()
            player.playWhenReady = true
            if (withTimeoutOrNull(timeoutMs) { ready.await() } != true) return@withContext false
            delay(1200) // deja que llegue un fotograma completo (el primero suele ser parcial)
            val bmp = texture.getBitmap(480, 270) ?: return@withContext false
            if (isBlank(bmp)) return@withContext false
            withContext(Dispatchers.IO) {
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            }
            true
        } finally {
            player.release()
        }
    }

/** true si todos los píxeles muestreados son negro puro (la superficie aún no tenía imagen). */
private fun isBlank(bmp: Bitmap): Boolean {
    for (i in 1..4) for (j in 1..4) {
        if (bmp.getPixel(bmp.width * i / 5, bmp.height * j / 5) and 0xFFFFFF != 0) return false
    }
    return true
}
