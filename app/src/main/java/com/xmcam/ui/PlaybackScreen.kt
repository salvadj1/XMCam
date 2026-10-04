@file:OptIn(ExperimentalMaterial3Api::class)

package com.xmcam.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import android.view.TextureView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.xmcam.data.EventRec
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.xmcam.App
import com.xmcam.PlaybackPos
import com.xmcam.data.ControlSession
import com.xmcam.protocol.XmEvents
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Locale

/**
 * Galería de grabaciones de la tarjeta SD: cuadrícula de miniaturas por día (mensaje OPFileQuery).
 *
 * Miniatura (por orden): foto de una alarma dentro del clip, fotograma generado reproduciendo
 * unos instantes el clip ([captureRtspThumbnail]) o, si eso falla, una tarjeta con la hora. Bajo cada clip se muestra el motivo de la grabación.
 * Al volver desde [ClipPlayerScreen] se restauran el día y la posición de la lista.
 */
@Composable
fun PlaybackScreen(camId: String, nav: (Screen) -> Unit, back: () -> Unit) {
    val app = App.instance
    val cam = app.cameras.value.first { it.id == camId }
    val session = remember { ControlSession(cam) }
    DisposableEffect(Unit) { onDispose { session.close() } }

    val events by app.events.collectAsState()
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    val days = remember { (0..6).map { LocalDate.now().minusDays(it.toLong()) } }

    // Posición guardada al abrir un clip (se consume: al entrar desde Inicio se empieza limpio).
    val restore = remember { app.playbackPos.remove(camId)?.takeIf { it.day in days } }
    var day by remember { mutableStateOf(restore?.day ?: days.first()) }
    var files by remember { mutableStateOf(app.playbackCache["$camId|$day"] ?: emptyList()) }
    var msg by remember { mutableStateOf(if (files.isEmpty()) "Cargando…" else "${files.size} grabaciones") }
    val gridState = rememberLazyGridState(restore?.index ?: 0, restore?.offset ?: 0)
    var pendingRestore by remember { mutableStateOf(restore != null) }

    // Generación de miniaturas: estado de progreso y superficie diminuta donde se reproduce cada clip.
    val ctx = LocalContext.current
    var thumbTick by remember { mutableIntStateOf(0) }
    var genTexture by remember { mutableStateOf<TextureView?>(null) }
    var generating by remember { mutableStateOf(false) }
    var genDone by remember { mutableIntStateOf(0) }
    var genTotal by remember { mutableIntStateOf(0) }
    var genFailed by remember { mutableStateOf(false) }
    var genTrigger by remember { mutableIntStateOf(0) }
    // Alarma de esta cámara que caiga dentro del clip [f] (da foto y motivo reales).
    val eventFor: (JSONObject) -> EventRec? = { f ->
        val b = runCatching { fmt.parse(f.optString("BeginTime"))?.time }.getOrNull()
        val e = runCatching { fmt.parse(f.optString("EndTime"))?.time }.getOrNull()
        if (b != null && e != null) events.firstOrNull { x -> x.cameraId == cam.id && x.time in b..e } else null
    }

    LaunchedEffect(day) {
        files = app.playbackCache["$camId|$day"] ?: emptyList()
        if (files.isEmpty()) msg = "Cargando…"
        runCatching { session.exec { it.queryFiles("$day 00:00:00", "$day 23:59:59", cam.channel) } }
            .onSuccess {
                files = it.sortedByDescending { f -> f.optString("BeginTime") }
                app.playbackCache["$camId|$day"] = files
                msg = if (it.isEmpty()) "Sin grabaciones este día" else "${it.size} grabaciones"
            }
            .onFailure { if (files.isEmpty()) msg = "Error: ${it.message}" }
    }
    // Si la lista llega después de abrir la pantalla, recoloca el scroll donde estaba.
    LaunchedEffect(files) {
        if (pendingRestore && files.isNotEmpty() && restore != null) {
            gridState.scrollToItem(restore.index, restore.offset)
            pendingRestore = false
        }
    }

    // Genera, uno a uno, las miniaturas de los clips que no tienen foto de alarma ni fotograma guardado.
    LaunchedEffect(files, genTrigger) {
        if (!app.store.autoThumbs || files.isEmpty()) return@LaunchedEffect
        val pending = files.filter { f ->
            eventFor(f)?.snapshot == null && !app.thumbFile(camId, f.optString("BeginTime")).exists()
        }.take(30)
        if (pending.isEmpty()) return@LaunchedEffect
        genTexture = null
        generating = true; genFailed = false; genDone = 0; genTotal = pending.size
        try {
            var fails = 0
            for (f in pending) {
                var tv = genTexture
                var waited = 0
                while (tv == null && waited < 20) { delay(100); tv = genTexture; waited++ }
                if (tv == null) break
                val begin = f.optString("BeginTime")
                val url = cam.playbackUrl(app.store.playbackTemplate, begin, f.optString("EndTime"))
                val ok = captureRtspThumbnail(ctx, url, tv, app.thumbFile(camId, begin))
                genDone++
                if (ok) { thumbTick++; fails = 0 } else if (++fails >= 2) { genFailed = true; break }
            }
        } finally {
            generating = false
        }
    }

    Scaffold(topBar = { Bar("Grabaciones · ${cam.name}", back) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                days.forEachIndexed { i, d ->
                    val label = when (i) { 0 -> "Hoy"; 1 -> "Ayer"; else -> "%02d/%02d".format(d.dayOfMonth, d.monthValue) }
                    FilterChip(selected = d == day, onClick = { day = d }, label = { Text(label) }, modifier = Modifier.padding(end = 6.dp))
                }
            }
            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (generating) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AndroidView(
                    factory = { TextureView(it).also { v -> genTexture = v } },
                    modifier = Modifier.size(48.dp, 27.dp).clip(RoundedCornerShape(6.dp))
                )
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text("Generando miniaturas $genDone/$genTotal…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { if (genTotal == 0) 0f else genDone / genTotal.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
            if (genFailed) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "No se pudieron generar miniaturas. Revisa la dirección de reproducción en Ajustes de la app.",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                )
                TextButton({ genTrigger++ }) { Text("Reintentar") }
            }
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2), state = gridState,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(files) { f ->
                    val begin = f.optString("BeginTime")
                    val end = f.optString("EndTime")
                    val b = runCatching { fmt.parse(begin)?.time }.getOrNull()
                    val e = runCatching { fmt.parse(end)?.time }.getOrNull()
                    val tick = thumbTick // al leerlo, la miniatura se actualiza en cuanto se genera
                    val ev = eventFor(f)
                    val thumb = ev?.snapshot ?: app.thumbFile(camId, begin).takeIf { tick >= 0 && it.exists() }?.absolutePath
                    val seconds = if (b != null && e != null) ((e - b) / 1000).coerceAtLeast(0) else 0L
                    val reason = ev?.let { XmEvents.label(it.event) } ?: reasonFromFileName(f.optString("FileName"))
                    ClipTile(begin.takeLast(8).take(5), formatDuration(seconds), reason, thumb) {
                        app.playbackPos[camId] = PlaybackPos(day, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                        nav(Screen.Clip(camId, begin, end))
                    }
                }
            }
        }
    }
}

/**
 * Miniatura de un clip: imagen (o tarjeta con la hora), duración abajo a la derecha y,
 * debajo, la hora y el motivo de la grabación. Reutilizable para cualquier lista de vídeos.
 *
 * @param timeLabel    hora de inicio mostrada bajo la miniatura.
 * @param duration     duración ya formateada (p. ej. "1:10").
 * @param reason       motivo de la grabación (p. ej. "Movimiento").
 * @param snapshotPath ruta de una imagen para la miniatura, o null para usar la tarjeta con la hora.
 * @param onClick      acción al tocar la miniatura.
 */
@Composable
fun ClipTile(timeLabel: String, duration: String, reason: String, snapshotPath: String?, onClick: () -> Unit) {
    val bmp = remember(snapshotPath) {
        snapshotPath?.let { BitmapFactory.decodeFile(it, BitmapFactory.Options().apply { inSampleSize = 4 }) }
    }
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            else Text(timeLabel, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.55f), modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)) {
                Text("▶", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                duration, Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                color = Color.White, style = MaterialTheme.typography.labelSmall
            )
        }
        Text(timeLabel, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
        Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Deduce el motivo de la grabación por la letra entre corchetes del nombre de archivo
 * (p. ej. "...[M]..."). Mapeo habitual en firmwares XM, sin verificar con todas las cámaras:
 * M = movimiento, A = alarma, H = manual, R = continua.
 */
private fun reasonFromFileName(name: String): String {
    val tag = Regex("\\[([A-Za-z])\\]").find(name)?.groupValues?.get(1)?.uppercase()
    return when (tag) {
        "M" -> "Movimiento"
        "A" -> "Alarma"
        "H" -> "Manual"
        "R" -> "Grabación continua"
        else -> "Grabación"
    }
}

/** Convierte [totalSeconds] a "m:ss" o "h:mm:ss". */
private fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
