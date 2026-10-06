package com.xmcam.data

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import com.xmcam.App
import com.xmcam.protocol.AvcSps
import com.xmcam.protocol.DvripClient
import com.xmcam.protocol.HevcSps
import com.xmcam.protocol.Msg
import com.xmcam.protocol.XmClipParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Descarga de clips de la SD por DVRIP (OPPlayBack). Secuencia: Claim (1424) -> DownloadStart (1420);
 * los datos llegan por 1426 hasta un paquete de longitud 0; DownloadStop (1420) para terminar.
 * La cámara solo atiende UNA descarga a la vez, así que cada llamada abre y cierra su propia sesión.
 */
object ClipDownloader {
    /**
     * Descarga el clip [fileName] de [cam] al archivo [out] (formato propietario XM, sin convertir).
     * Se puede cancelar cancelando la corrutina (se comprueba en cada paquete).
     *
     * @param begin    inicio del clip "yyyy-MM-dd HH:mm:ss".
     * @param end      fin del clip "yyyy-MM-dd HH:mm:ss".
     * @param maxBytes para al llegar a este tamaño (útil para sacar solo el principio, p. ej. una miniatura).
     * @param onProgress recibe los bytes descargados hasta el momento (desde un hilo de fondo).
     * @return bytes escritos en [out].
     */
    suspend fun download(
        cam: Camera, fileName: String, begin: String, end: String, out: File,
        maxBytes: Long = Long.MAX_VALUE, onProgress: (Long) -> Unit = {}
    ): Long = withContext(Dispatchers.IO) {
        val c = DvripClient(cam.host, cam.port, 8000)
        var total = 0L
        try {
            c.login(cam.user, cam.password)
            c.send(Msg.PB_CLAIM, c.playbackBody("Claim", fileName, begin, end))
            // Respuesta del Claim (1425): se espera unos paquetes; si no llega, se sigue igualmente.
            runCatching {
                for (i in 0 until 3) if (c.readPacket().msgId == Msg.PB_CLAIM + 1) break
            }
            c.send(Msg.PB_CTRL, c.playbackBody("DownloadStart", fileName, begin, end))
            c.setReadTimeout(8000)
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { fos ->
                while (total < maxBytes) {
                    ensureActive()
                    val p = try { c.readPacket() } catch (e: SocketTimeoutException) { break }
                    if (p.body.isEmpty()) break                    // fin del clip
                    if (p.body[0] == '{'.code.toByte()) continue   // respuestas JSON de control
                    fos.write(p.body)
                    total += p.body.size
                    onProgress(total)
                }
            }
            runCatching { c.send(Msg.PB_CTRL, c.playbackBody("DownloadStop", fileName, begin, end)) }
        } finally {
            c.close()
        }
        total
    }
}

/** Convierte clips XM descargados a MP4 estándar y extrae fotogramas. Solo vídeo (sin audio). */
object ClipConverter {
    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /**
     * Escribe un MP4 reproducible con el vídeo (y el audio, si el clip lo lleva) de [raw].
     *
     * @param durationUs duración real del clip; reparte los fotogramas en ese tiempo para que se vea a velocidad
     *                   normal. Si es null se asume [fps].
     * @param dropLastFrame descarta el último fotograma (útil con descargas parciales, cuyo final está cortado).
     * @param withAudio  extrae también el audio A-law y lo convierte a AAC (no hace falta para miniaturas).
     */
    fun toMp4(
        raw: ByteArray, out: File, durationUs: Long?, dropLastFrame: Boolean = false, fps: Int = 12, withAudio: Boolean = true
    ) {
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()
        val top = XmClipParser.splitAnnexB(raw)
        val parsed = XmClipParser.parse(raw, top)
        val aac = if (withAudio) ClipAudio.fromClip(top) else null
        when (parsed) {
            is XmClipParser.Parsed.Hevc -> {
                val t = parsed.track
                val (w, h) = HevcSps.dimensions(t.sps)
                require(w > 0 && h > 0) { "Dimensiones HEVC no válidas ${w}x$h" }
                val format = MediaFormat.createVideoFormat("video/hevc", w, h).apply {
                    val csd = ByteArrayOutputStream()
                    listOfNotNull(t.vps, t.sps, t.pps).forEach { csd.write(START_CODE); csd.write(it) }
                    setByteBuffer("csd-0", ByteBuffer.wrap(csd.toByteArray()))
                }
                mux(format, t.frames, out, durationUs, dropLastFrame, fps, aac) { ((it[0].toInt() shr 1) and 0x3F) in 16..21 }
            }
            is XmClipParser.Parsed.Avc -> {
                val t = parsed.track
                val (w, h) = AvcSps.dimensions(t.sps)
                require(w > 0 && h > 0) { "Dimensiones H.264 no válidas ${w}x$h" }
                val format = MediaFormat.createVideoFormat("video/avc", w, h).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + t.sps))
                    t.pps?.let { setByteBuffer("csd-1", ByteBuffer.wrap(START_CODE + it)) }
                }
                mux(format, t.frames, out, durationUs, dropLastFrame, fps, aac) { (it[0].toInt() and 0x1F) == 5 }
            }
        }
    }

    /** Escribe vídeo y audio intercalados por tiempo en un MP4 (el audio es opcional). */
    private fun mux(
        format: MediaFormat, allFrames: List<ByteArray>, out: File, durationUs: Long?,
        dropLast: Boolean, fps: Int, aac: AacTrack?, isKey: (ByteArray) -> Boolean
    ) {
        // Empieza en el primer fotograma clave: sin él el decodificador no puede arrancar.
        val start = allFrames.indexOfFirst(isKey)
        require(start >= 0) { "El clip no contiene ningún fotograma clave" }
        var frames = allFrames.subList(start, allFrames.size)
        if (dropLast && frames.size > 1) frames = frames.dropLast(1)
        val frameUs = if (durationUs != null && durationUs > 0) (durationUs / frames.size).coerceAtLeast(1_000L)
        else 1_000_000L / fps.coerceAtLeast(1)

        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val vTrack = muxer.addTrack(format)
            val aTrack = aac?.let { muxer.addTrack(ClipAudio.muxerFormat(it.csd)) }
            muxer.start()
            val info = MediaCodec.BufferInfo()
            var vi = 0
            var ai = 0
            val aCount = if (aTrack != null) aac!!.samples.size else 0
            while (vi < frames.size || ai < aCount) {
                val vPts = if (vi < frames.size) vi * frameUs else Long.MAX_VALUE
                val aPts = if (ai < aCount) aac!!.ptsUs[ai] else Long.MAX_VALUE
                if (vPts <= aPts) {
                    val nal = frames[vi]
                    val sample = START_CODE + nal
                    info.set(0, sample.size, vPts, if (isKey(nal)) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(vTrack, ByteBuffer.wrap(sample), info)
                    vi++
                } else {
                    val s = aac!!.samples[ai]
                    info.set(0, s.size, aPts, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(aTrack!!, ByteBuffer.wrap(s), info)
                    ai++
                }
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
        }
    }

    /** Primer fotograma de [mp4] reducido a [width] px de ancho (o null si el móvil no puede decodificarlo). */
    fun firstFrame(mp4: File, width: Int = 480): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(mp4.absolutePath)
            r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let {
                Bitmap.createScaledBitmap(it, width, (it.height * width.toFloat() / it.width).toInt().coerceAtLeast(1), true)
            }
        } finally {
            runCatching { r.release() }
        }
    }

    /** Guarda [bmp] como JPEG en [out]. */
    fun saveJpeg(bmp: Bitmap, out: File) {
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }
}

/** Operaciones de alto nivel con clips: obtener un MP4 reproducible y generar miniaturas. */
object ClipRepo {
    /** Bytes del principio del clip que se descargan para la miniatura (suele bastar para SPS + primer IDR). */
    private const val THUMB_PREFIX_BYTES = 768_000L
    /** Cuántos clips convertidos se conservan en la caché. */
    private const val KEEP_CLIPS = 5

    /**
     * Devuelve un MP4 reproducible del clip: usa la caché si ya está, y si no lo descarga y convierte.
     * Al terminar guarda también su miniatura si aún no existía.
     */
    suspend fun playable(
        app: App, cam: Camera, fileName: String, begin: String, end: String, onProgress: (Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val mp4 = app.clipFile(cam.id, begin)
        if (!(mp4.exists() && mp4.length() > 0)) {
            val raw = File(app.cacheDir, "clip_raw.bin")
            try {
                val n = ClipDownloader.download(cam, fileName, begin, end, raw, onProgress = onProgress)
                require(n > 0) { "La cámara no envió datos del clip" }
                try {
                    ClipConverter.toMp4(raw.readBytes(), mp4, durationUs(begin, end))
                } catch (e: Throwable) { mp4.delete(); throw e }
                pruneClips(mp4.parentFile)
            } finally { raw.delete() }
        }
        val thumb = app.thumbFile(cam.id, begin)
        if (!thumb.exists()) runCatching { ClipConverter.firstFrame(mp4)?.let { ClipConverter.saveJpeg(it, thumb) } }
        mp4
    }

    /**
     * Genera la miniatura de un clip descargando solo su principio y sacando el primer fotograma.
     * @return true si se guardó la miniatura en [out]; false si falló (la cancelación sí se propaga).
     */
    suspend fun thumbnail(app: App, cam: Camera, fileName: String, begin: String, end: String, out: File): Boolean =
        withContext(Dispatchers.IO) {
            val raw = File(app.cacheDir, "thumb_raw.bin")
            val tmp = File(app.cacheDir, "thumb_tmp.mp4")
            try {
                val n = ClipDownloader.download(cam, fileName, begin, end, raw, maxBytes = THUMB_PREFIX_BYTES)
                if (n <= 0) return@withContext false
                ClipConverter.toMp4(raw.readBytes(), tmp, null, dropLastFrame = true, withAudio = false)
                val bmp = ClipConverter.firstFrame(tmp) ?: return@withContext false
                ClipConverter.saveJpeg(bmp, out)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            } finally {
                raw.delete(); tmp.delete()
            }
        }

    /** Duración real del clip en microsegundos a partir de sus fechas, o null si no es creíble. */
    fun durationUs(begin: String, end: String): Long? = runCatching {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val ms = f.parse(end.trim())!!.time - f.parse(begin.trim())!!.time
        if (ms in 1_000..86_400_000) ms * 1_000L else null
    }.getOrNull()

    /** Borra los MP4 más antiguos de [dir] dejando solo los [KEEP_CLIPS] más recientes. */
    private fun pruneClips(dir: File?) {
        dir?.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP_CLIPS)?.forEach { it.delete() }
    }
}
