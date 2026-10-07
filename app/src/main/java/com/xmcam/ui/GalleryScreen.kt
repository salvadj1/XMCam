@file:OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)

package com.xmcam.ui

import android.content.ContentValues
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.xmcam.App
import com.xmcam.data.Camera
import com.xmcam.data.ClipRepo
import com.xmcam.data.ControlSession
import com.xmcam.data.EventRec
import com.xmcam.protocol.XmEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Tipo de grabación: sirve para filtrar el listado y para colorear la línea de tiempo. */
private enum class ClipKind(val label: String) { PERSON("Persona"), MOTION("Movimiento"), REC("Grabación") }

private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24 * HOUR_MS

/** Margen (ms) alrededor del clip en el que una alarma se considera suya (para etiquetar su motivo). */
private const val SNAP_MARGIN_MS = 10_000L

/** Colores de la línea de tiempo (fijos: se ven bien en tema claro y oscuro). */
private val COLOR_MOTION = Color(0xFFEF9F27)
private val COLOR_PERSON = Color(0xFFD85A30)

/** Un clip de la tarjeta SD ya interpretado (tiempos en ms, motivo y tipo). */
private data class GClip(
    val cam: Camera, val fileName: String, val begin: String, val end: String,
    val beginMs: Long, val endMs: Long, val reason: String, val kind: ClipKind, val snapshot: String?
) {
    /** Identificador único del clip (cámara + inicio). */
    val key: String get() = "${cam.id}|$begin"
    /** Duración en segundos. */
    val seconds: Long get() = ((endMs - beginMs) / 1000).coerceAtLeast(0)
}

/**
 * Galería unificada: filtro por cámara, vista previa con controles y botones, línea de tiempo de 24 h
 * (un carril por cámara) y listado de clips, todo en la misma pantalla.
 *
 * - Tocar un clip (en la línea de tiempo o en el listado) lo descarga ([ClipRepo.playable]) y lo reproduce arriba.
 * - La línea de tiempo se desplaza arrastrando y se amplía con − / + (1x a 8x); el cursor sigue la reproducción.
 * - Guardar copia el clip a Movies/XMCam; Enviar lo guarda y abre el selector de compartir.
 *
 * @param initialCamId cámara preseleccionada (null = todas).
 * @param nav          navegación de la app (barra inferior).
 * @param back         volver atrás.
 */
@Composable
fun GalleryScreen(initialCamId: String?, nav: (Screen) -> Unit, back: () -> Unit) {
    val app = App.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cams by app.cameras.collectAsState()
    val events by app.events.collectAsState()
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    val zone = remember { ZoneId.systemDefault() }
    val days = remember { (0..6).map { LocalDate.now().minusDays(it.toLong()) } }

    var camFilter by remember { mutableStateOf(initialCamId) }
    var dayIdx by remember { mutableIntStateOf(0) }
    var kindFilter by remember { mutableStateOf<ClipKind?>(null) }
    var allClips by remember { mutableStateOf<List<GClip>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var showInfo by remember { mutableStateOf(false) }
    var thumbTick by remember { mutableIntStateOf(0) }

    val day = days[dayIdx]
    val dayStart = remember(day) { day.atStartOfDay(zone).toInstant().toEpochMilli() }
    val lanes = remember(cams, camFilter) { cams.filter { camFilter == null || it.id == camFilter } }

    // --- Carga de clips del día para las cámaras elegidas (caché al instante, red en paralelo) ---
    LaunchedEffect(camFilter, dayIdx, cams) {
        val targets = cams.filter { camFilter == null || it.id == camFilter }
        allClips = targets.flatMap { c -> app.playbackCache["${c.id}|$day"]?.let { buildClips(c, it, events, fmt) } ?: emptyList() }
        loading = true; msg = ""
        val results = withContext(Dispatchers.IO) {
            targets.map { cam ->
                async {
                    cam to runCatching {
                        ControlSession(cam).use { s -> s.exec { it.queryFiles("$day 00:00:00", "$day 23:59:59", cam.channel) } }
                    }
                }
            }.awaitAll()
        }
        val fresh = mutableListOf<GClip>()
        var failed = 0
        for ((cam, r) in results) {
            val files = r.getOrNull()
            if (files != null) app.playbackCache["${cam.id}|$day"] = files else failed++
            (files ?: app.playbackCache["${cam.id}|$day"])?.let { fresh += buildClips(cam, it, events, fmt) }
        }
        allClips = fresh
        loading = false
        msg = when {
            targets.isNotEmpty() && failed == targets.size -> "No se pudo consultar la SD de las cámaras"
            fresh.isEmpty() -> "Sin grabaciones este día"
            failed > 0 -> "${fresh.size} grabaciones · $failed cámara(s) sin respuesta"
            else -> "${fresh.size} grabaciones"
        }
    }

    val clips = remember(allClips, kindFilter) {
        allClips.filter { kindFilter == null || it.kind == kindFilter }.sortedByDescending { it.beginMs }
    }

    // --- Selección y reproductor ---
    var selKey by remember { mutableStateOf<String?>(null) }
    val sel = allClips.firstOrNull { it.key == selKey }
    var file by remember { mutableStateOf<File?>(null) }
    var bytes by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    val busy = sel != null && file == null && error == null

    val player = remember { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayerError(e: PlaybackException) {
                error = "No se puede reproducir el clip (${e.errorCodeName}). Suele faltar el decodificador H.265."
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    // Posición y duración 4 veces por segundo (alimentan el cursor de la línea de tiempo).
    LaunchedEffect(player) {
        while (true) {
            pos = player.currentPosition
            dur = player.duration.coerceAtLeast(0L)
            delay(250)
        }
    }
    // Descarga/convierte el clip elegido; al cambiar de selección (o reintentar) se reinicia.
    LaunchedEffect(sel?.key, attempt) {
        val c = sel
        player.stop(); player.clearMediaItems()
        file = null; error = null; bytes = 0
        if (c == null) return@LaunchedEffect
        try {
            var last = 0L
            file = ClipRepo.playable(app, c.cam, c.fileName, c.begin, c.end) { n ->
                val now = System.currentTimeMillis()
                if (now - last > 200) { last = now; bytes = n }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }
    LaunchedEffect(file) {
        val f = file ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
        player.prepare(); player.setPlaybackSpeed(speed); player.playWhenReady = true
    }

    // --- Ventana de la línea de tiempo (zoom 1x–8x, desplazable) ---
    var zoom by remember { mutableIntStateOf(1) }
    var winStart by remember { mutableLongStateOf(0L) }
    val winWidth = DAY_MS / zoom
    val playheadMs = sel?.let { it.beginMs + (if (file != null) pos else 0L) }
    val setZoom: (Int) -> Unit = { z ->
        val center = playheadMs?.minus(dayStart) ?: (winStart + winWidth / 2)
        val nw = DAY_MS / z
        zoom = z
        winStart = (center - nw / 2).coerceIn(0L, DAY_MS - nw)
    }
    // Al elegir un clip fuera de la ventana visible, la centra en él.
    LaunchedEffect(sel?.key) {
        val c = sel ?: return@LaunchedEffect
        val off = c.beginMs - dayStart
        if (off < winStart || off > winStart + winWidth) winStart = (off - winWidth / 2).coerceIn(0L, DAY_MS - winWidth)
    }
    // Al cambiar de día o de cámara se deselecciona el clip.
    LaunchedEffect(camFilter, dayIdx) { selKey = null; winStart = 0L; zoom = 1 }

    // --- Miniaturas de los clips sin foto (máx. 12, una a una; se pausa mientras se descarga un clip) ---
    val busyNow by rememberUpdatedState(busy)
    LaunchedEffect(allClips) {
        if (!app.store.autoThumbs) return@LaunchedEffect
        val pending = allClips.filter { it.snapshot == null && !app.thumbFile(it.cam.id, it.begin).exists() }.take(12)
        var fails = 0
        for (c in pending) {
            while (busyNow) delay(500)
            val ok = ClipRepo.thumbnail(app, c.cam, c.fileName, c.begin, c.end, app.thumbFile(c.cam.id, c.begin))
            if (ok) { thumbTick++; fails = 0 } else if (++fails >= 2) break
        }
    }

    /** Selecciona un clip; mientras se descarga otro se ignora (la descarga usa archivos temporales compartidos). */
    val select: (GClip) -> Unit = { c ->
        if (busy) notice = "Descargando el clip actual… espera a que termine"
        else { notice = ""; selKey = c.key }
    }
    /** Salta al clip anterior (older = true) o siguiente en el tiempo. */
    fun step(older: Boolean) {
        val i = clips.indexOfFirst { it.key == selKey }
        val target = if (i < 0) clips.firstOrNull() else clips.getOrNull(if (older) i + 1 else i - 1)
        if (target != null) select(target)
    }
    /** Guarda el clip cargado en Movies/XMCam y, si [share], abre el selector de compartir. */
    fun saveClip(share: Boolean) {
        val f = file
        val c = sel
        if (f == null || c == null) { notice = "Elige un clip y espera a que cargue"; return }
        scope.launch {
            val name = ("xmcam_${c.cam.name}_${c.begin}").filter { it.isLetterOrDigit() || it == '_' } + ".mp4"
            val uri = withContext(Dispatchers.IO) { saveVideoToGallery(ctx, f, name) }
            if (uri == null) { notice = "No se pudo guardar el clip"; return@launch }
            notice = "Clip guardado en Movies/XMCam"
            if (share) runCatching {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(Intent.createChooser(send, "Enviar clip"))
            }
        }
    }

    val dayLabel = when (dayIdx) {
        0 -> "Hoy"
        1 -> "Ayer"
        else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
    }

    Scaffold(
        topBar = { Bar("Galería", back) },
        bottomBar = { MainNavBar(MainTab.GALLERY, nav) }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            // Filtro por cámara
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                FilterChip(camFilter == null, { camFilter = null }, { Text("Todas") }, Modifier.padding(end = 6.dp))
                cams.forEach { c ->
                    FilterChip(camFilter == c.id, { camFilter = c.id }, { Text(c.name, maxLines = 1) }, Modifier.padding(end = 6.dp))
                }
            }

            // Vista previa con información superpuesta
            Box(
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp)).background(Color(0xFF2C2C2A))
            ) {
                val err = error
                if (file != null && err == null) AndroidView(
                    factory = { c -> PlayerView(c).apply { this.player = player; useController = false } },
                    update = { it.player = player },
                    modifier = Modifier.matchParentSize()
                ) else Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        sel == null -> Text(
                            "Elige un clip en la línea de tiempo o en la lista",
                            color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall
                        )
                        err != null -> {
                            Text("No se pudo cargar el clip", color = Color.White, style = MaterialTheme.typography.titleSmall)
                            Text(err, Modifier.padding(vertical = 4.dp), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                            Button({ attempt++ }) { Text("Reintentar") }
                        }
                        else -> {
                            CircularProgressIndicator()
                            Text(
                                "Descargando clip… ${"%.1f".format(bytes / 1_048_576.0)} MB", Modifier.padding(top = 8.dp),
                                color = Color.White, style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                if (sel != null) {
                    PreviewTag(sel.cam.name, Modifier.align(Alignment.TopStart).padding(6.dp))
                    PreviewTag(sel.reason, Modifier.align(Alignment.TopEnd).padding(6.dp))
                    PreviewTag(sel.begin, Modifier.align(Alignment.BottomStart).padding(6.dp))
                    PreviewTag("Tarjeta SD · ${formatDuration(sel.seconds)}", Modifier.align(Alignment.BottomEnd).padding(6.dp))
                }
            }
            LinearProgressIndicator(
                progress = { if (dur > 0 && file != null) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )

            // Controles de reproducción
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TextButton({ step(older = true) }) { Text("⏮") }
                TextButton({ player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }) { Text("−10") }
                FilledTonalButton({
                    if (player.isPlaying) player.pause()
                    else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() }
                }) { Text(if (playing) "Pausa" else "Play") }
                TextButton({ player.seekTo((player.currentPosition + 10_000).coerceAtMost(if (dur > 0) dur else Long.MAX_VALUE)) }) { Text("+10") }
                TextButton({ step(older = false) }) { Text("⏭") }
                OutlinedButton({
                    val all = listOf(1f, 1.5f, 2f, 0.5f)
                    speed = all[(all.indexOf(speed) + 1) % all.size]
                    player.setPlaybackSpeed(speed)
                }) { Text(if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x") }
            }
            // Botones de opciones
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ saveClip(false) }, Modifier.weight(1f)) { Text("Guardar") }
                OutlinedButton({ saveClip(true) }, Modifier.weight(1f)) { Text("Enviar") }
                OutlinedButton({ if (sel != null) showInfo = true else notice = "Elige un clip primero" }, Modifier.weight(1f)) { Text("Info") }
            }
            if (notice.isNotEmpty()) Text(notice, Modifier.padding(horizontal = 12.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall)

            // Línea de tiempo
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ if (dayIdx < days.lastIndex) dayIdx++ }) { Text("◀") }
                        Text(dayLabel, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
                        TextButton({ if (dayIdx > 0) dayIdx-- }) { Text("▶") }
                        TextButton({ setZoom(max(1, zoom / 2)) }) { Text("−") }
                        Text("${zoom}x", style = MaterialTheme.typography.labelMedium)
                        TextButton({ setZoom(min(8, zoom * 2)) }) { Text("+") }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        FilterChip(kindFilter == null, { kindFilter = null }, { Text("Todo") }, Modifier.padding(end = 6.dp))
                        ClipKind.values().forEach { k ->
                            FilterChip(kindFilter == k, { kindFilter = k }, { Text(k.label) }, Modifier.padding(end = 6.dp))
                        }
                    }
                    DayTimeline(
                        lanes, clips, sel?.key, dayStart, winStart, winWidth, playheadMs,
                        onPan = { d -> winStart = (winStart + d).coerceIn(0L, DAY_MS - winWidth) },
                        onPick = select
                    )
                    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        LegendDot(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), "Grabación")
                        LegendDot(COLOR_MOTION, "Movimiento")
                        LegendDot(COLOR_PERSON, "Persona")
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (loading) "Cargando…" else msg, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Listado de archivos
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(clips, key = { it.key }) { c ->
                    val thumb = c.snapshot ?: app.thumbFile(c.cam.id, c.begin).takeIf { thumbTick >= 0 && it.exists() }?.absolutePath
                    ClipRow(c, c.key == selKey, thumb) { select(c) }
                }
            }
        }
    }

    if (showInfo && sel != null) AlertDialog(
        onDismissRequest = { showInfo = false },
        title = { Text("Información del clip") },
        text = {
            Column {
                Text("Cámara: ${sel.cam.name}")
                Text("Inicio: ${sel.begin}")
                Text("Fin: ${sel.end}")
                Text("Duración: ${formatDuration(sel.seconds)}")
                Text("Motivo: ${sel.reason}")
                Text("Origen: tarjeta SD", Modifier.padding(bottom = 4.dp))
                Text(sel.fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton({ showInfo = false }) { Text("Cerrar") } }
    )
}

/**
 * Convierte la respuesta de la cámara (lista de archivos de la SD) en clips interpretados:
 * tiempos en ms, motivo (alarma cercana o letra del nombre de archivo), tipo y foto de la alarma si existe.
 * Descarta los archivos sin nombre o con fechas ilegibles.
 */
private fun buildClips(cam: Camera, files: List<JSONObject>, events: List<EventRec>, fmt: SimpleDateFormat): List<GClip> =
    files.mapNotNull { f ->
        val name = f.optString("FileName")
        val begin = f.optString("BeginTime")
        val end = f.optString("EndTime")
        val b = runCatching { fmt.parse(begin)?.time }.getOrNull()
        val e = runCatching { fmt.parse(end)?.time }.getOrNull()
        if (name.isEmpty() || b == null || e == null) return@mapNotNull null
        val near = events.filter { x -> x.cameraId == cam.id && x.time in (b - SNAP_MARGIN_MS)..(e + SNAP_MARGIN_MS) }
        val ev = near.firstOrNull { x -> x.snapshot?.let { File(it).exists() } == true } ?: near.firstOrNull()
        val reason = ev?.let { XmEvents.label(it.event) } ?: reasonFromFileName(name)
        GClip(cam, name, begin, end, b, e, reason, kindOf(reason), ev?.snapshot?.takeIf { File(it).exists() })
    }

/** Clasifica un motivo de grabación ("Persona", "Movimiento"…) en un [ClipKind]. */
private fun kindOf(reason: String): ClipKind = when {
    reason.contains("ersona", ignoreCase = true) -> ClipKind.PERSON
    reason.contains("ovimiento", ignoreCase = true) || reason.contains("larma", ignoreCase = true) -> ClipKind.MOTION
    else -> ClipKind.REC
}

/** Distancia en ms entre el instante [t] y el clip [c] (0 si cae dentro). */
private fun clipDistance(c: GClip, t: Long): Long =
    if (t in c.beginMs..c.endMs) 0L else min(abs(t - c.beginMs), abs(t - c.endMs))

/** Formatea un desplazamiento [ms] desde las 00:00 como "HH:mm" o "HH:mm:ss". */
private fun clock(ms: Long, seconds: Boolean): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val hh = (s / 3600) % 24
    val mm = (s % 3600) / 60
    return if (seconds) "%02d:%02d:%02d".format(hh, mm, s % 60) else "%02d:%02d".format(hh, mm)
}

/** Copia un MP4 a la galería del móvil (Movies/XMCam). Devuelve su Uri o null si falla. Bloqueante. */
private fun saveVideoToGallery(ctx: android.content.Context, mp4: File, displayName: String): Uri? = runCatching {
    val v = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/XMCam")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: return@runCatching null
    ctx.contentResolver.openOutputStream(uri)?.use { out -> mp4.inputStream().use { it.copyTo(out) } }
    uri
}.getOrNull()

/** Etiqueta pequeña sobre la vista previa (fondo negro translúcido, texto blanco). */
@Composable
private fun PreviewTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1
    )
}

/** Punto de color con su texto, para la leyenda de la línea de tiempo. */
@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Fila del listado: miniatura, hora + motivo, cámara + duración. El clip seleccionado se resalta.
 *
 * @param thumbPath ruta de la imagen de miniatura, o null para mostrar un recuadro con la hora.
 */
@Composable
private fun ClipRow(c: GClip, selected: Boolean, thumbPath: String?, onClick: () -> Unit) {
    val bmp = remember(thumbPath) {
        thumbPath?.let { BitmapFactory.decodeFile(it, BitmapFactory.Options().apply { inSampleSize = 4 }) }
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(58.dp, 38.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2C2C2A)),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            else Text(c.begin.takeLast(8).take(5), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text("${c.begin.takeLast(8).take(5)} · ${c.reason}", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                "${c.cam.name} · ${formatDuration(c.seconds)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
            )
        }
        if (selected) Text("▶", color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Línea de tiempo de un día con un carril por cámara: cada clip es una barra coloreada por tipo
 * (grabación / movimiento / persona), con rejilla horaria adaptable al zoom y un cursor de reproducción.
 * Tocar elige el clip más cercano del carril; arrastrar horizontalmente desplaza la ventana visible.
 * Reutilizable con cualquier lista de clips de las mismas características.
 *
 * @param dayStart   instante (ms epoch) de las 00:00 del día mostrado.
 * @param winStart   inicio de la ventana visible, en ms desde las 00:00.
 * @param winWidth   ancho de la ventana visible en ms (24 h / zoom).
 * @param playheadMs instante (ms epoch) del cursor, o null si no hay clip elegido.
 * @param onPan      desplazamiento solicitado de la ventana (ms, positivo = hacia la derecha).
 * @param onPick     clip elegido al tocar la línea de tiempo.
 */
@Composable
private fun DayTimeline(
    lanes: List<Camera>, clips: List<GClip>, selKey: String?, dayStart: Long,
    winStart: Long, winWidth: Long, playheadMs: Long?,
    onPan: (Long) -> Unit, onPick: (GClip) -> Unit
) {
    val density = LocalDensity.current
    val laneH = 20.dp
    val gap = 6.dp
    val topPad = 14.dp
    val axisH = 16.dp
    val labelW = 54.dp
    val sidePad = 4.dp
    val height = topPad + (laneH + gap) * lanes.size.coerceAtLeast(1) + axisH

    val cRec = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val cLane = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val cGrid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val cText = MaterialTheme.colorScheme.onSurfaceVariant
    val cHead = MaterialTheme.colorScheme.primary
    val cSel = MaterialTheme.colorScheme.onSurface
    val paint = remember(cText, density) {
        android.graphics.Paint().apply { isAntiAlias = true; color = cText.toArgb(); textSize = with(density) { 10.sp.toPx() } }
    }

    // Valores actuales para los detectores de gestos (que no deben reiniciarse en cada cambio).
    val curDay by rememberUpdatedState(dayStart)
    val curStart by rememberUpdatedState(winStart)
    val curWidth by rememberUpdatedState(winWidth)
    val curClips by rememberUpdatedState(clips)
    val curLanes by rememberUpdatedState(lanes)
    val curPick by rememberUpdatedState(onPick)
    val curPan by rememberUpdatedState(onPan)

    Canvas(
        Modifier.fillMaxWidth().height(height)
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val lw = labelW.toPx()
                    val chartW = size.width - lw - sidePad.toPx()
                    if (o.x < lw || chartW <= 0f) return@detectTapGestures
                    val t = curDay + curStart + ((o.x - lw) / chartW * curWidth).toLong()
                    val laneIdx = ((o.y - topPad.toPx()) / (laneH + gap).toPx()).toInt().coerceIn(0, (curLanes.size - 1).coerceAtLeast(0))
                    val laneCam = curLanes.getOrNull(laneIdx)?.id
                    val tol = (curWidth * 0.04).toLong()
                    curClips.filter { laneCam == null || it.cam.id == laneCam }
                        .minByOrNull { clipDistance(it, t) }
                        ?.takeIf { clipDistance(it, t) <= tol }
                        ?.let { curPick(it) }
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dx ->
                    change.consume()
                    val chartW = size.width - labelW.toPx() - sidePad.toPx()
                    if (chartW > 0f) curPan(-(dx / chartW * curWidth).toLong())
                }
            }
    ) {
        val lw = labelW.toPx()
        val right = size.width - sidePad.toPx()
        val chartW = right - lw
        val top = topPad.toPx()
        val lh = laneH.toPx()
        val rowStep = (laneH + gap).toPx()
        val n = lanes.size.coerceAtLeast(1)
        val bottom = top + rowStep * n - gap.toPx()
        fun xOf(msEpoch: Long): Float = lw + (msEpoch - dayStart - winStart).toFloat() / winWidth * chartW

        // Rejilla horaria: el paso se adapta al zoom (3 h, 2 h, 1 h o 30 min).
        val tickMs = when {
            winWidth > 12 * HOUR_MS -> 3 * HOUR_MS
            winWidth > 6 * HOUR_MS -> 2 * HOUR_MS
            winWidth > 3 * HOUR_MS -> HOUR_MS
            else -> HOUR_MS / 2
        }
        var t = ((winStart + tickMs - 1) / tickMs) * tickMs
        while (t <= winStart + winWidth) {
            val x = lw + (t - winStart).toFloat() / winWidth * chartW
            drawLine(cGrid, Offset(x, top - 2f), Offset(x, bottom), 1f)
            val label = clock(t, false)
            drawContext.canvas.nativeCanvas.drawText(label, x - paint.measureText(label) / 2, size.height - 3.dp.toPx(), paint)
            t += tickMs
        }

        // Carriles y clips
        lanes.forEachIndexed { i, cam ->
            val y = top + i * rowStep
            drawRoundRect(cLane, Offset(lw, y), Size(chartW, lh), CornerRadius(4.dp.toPx()))
            drawContext.canvas.nativeCanvas.drawText(cam.name.take(8), 0f, y + lh * 0.7f, paint)
            clips.filter { it.cam.id == cam.id }.forEach { c ->
                val xb = xOf(c.beginMs)
                val xe = xOf(c.endMs)
                if (xe < lw || xb > right) return@forEach
                val x1 = xb.coerceIn(lw, right)
                val x2 = max(xe.coerceIn(lw, right), min(x1 + 3.dp.toPx(), right))
                val col = when (c.kind) { ClipKind.PERSON -> COLOR_PERSON; ClipKind.MOTION -> COLOR_MOTION; ClipKind.REC -> cRec }
                drawRect(col, Offset(x1, y), Size(x2 - x1, lh))
                if (c.key == selKey) drawRect(cSel, Offset(x1, y), Size(x2 - x1, lh), style = Stroke(2.dp.toPx()))
            }
        }

        // Cursor de reproducción con su hora
        if (playheadMs != null) {
            val x = xOf(playheadMs)
            if (x in lw..right) {
                drawLine(cHead, Offset(x, 12.dp.toPx()), Offset(x, bottom), 2.dp.toPx())
                val label = clock(playheadMs - dayStart, true)
                val w = paint.measureText(label)
                drawContext.canvas.nativeCanvas.drawText(label, (x - w / 2).coerceIn(lw, max(lw, right - w)), 10.dp.toPx(), paint)
            }
        }
    }
}
