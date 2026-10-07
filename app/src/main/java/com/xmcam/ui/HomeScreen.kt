@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, UnstableApi::class)

package com.xmcam.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import com.xmcam.App
import com.xmcam.data.Camera
import com.xmcam.data.Net
import com.xmcam.protocol.XmEvents
import com.xmcam.service.AlarmService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/**
 * Distribuciones del mosaico. [span] es el ancho de una casilla normal sobre una rejilla de 6 columnas
 * (3 = dos por fila, 6 = una por fila, 2 = tres por fila). [liveSecondary] indica si las casillas
 * secundarias reproducen vídeo o solo muestran la captura (ahorra batería y conexiones).
 */
private enum class MosaicLayout(val label: String, val span: Int, val liveSecondary: Boolean) {
    GRID2("Cuadrícula 2x2", 3, true),
    LIST("Lista (1 columna)", 6, true),
    FEATURED("Principal + miniaturas", 2, false),
    GRID3("Cuadrícula compacta (3 col.)", 2, false)
}

/**
 * Pantalla principal en mosaico con 4 distribuciones elegibles (menú "Vista", se recuerda la última): resumen compacto (en línea, eventos de hoy, vigilancia),
 * perfil activo y una vista previa por cámara con su estado. Tocar una cámara abre su directo;
 * mantener pulsado ofrece borrarla. La última casilla del mosaico añade una cámara nueva.
 */
@Composable
fun HomeScreen(nav: (Screen) -> Unit) {
    val app = App.instance
    val ctx = LocalContext.current
    val cams by app.cameras.collectAsState()
    val online by app.online.collectAsState()
    val reach by app.reach.collectAsState()
    val profile by app.profile.collectAsState()
    val events by app.events.collectAsState()
    var monitor by remember { mutableStateOf(app.store.monitorEnabled) }
    var toDelete by remember { mutableStateOf<Camera?>(null) }
    var layout by remember { mutableStateOf(MosaicLayout.values().getOrElse(app.store.mosaicLayout) { MosaicLayout.GRID2 }) }
    var layoutMenu by remember { mutableStateOf(false) }
    var featuredId by remember { mutableStateOf<String?>(null) }   // cámara principal en la vista "Principal + miniaturas"
    val previews = remember { mutableStateMapOf<String, ImageBitmap>() }

    // Comprueba cada 4 s si cada cámara acepta conexiones (solo mientras esta pantalla está visible).
    LaunchedEffect(cams) {
        while (true) {
            app.reach.value = withContext(Dispatchers.IO) {
                cams.map { c -> async { c.id to Net.probe(c.host, c.port) } }.awaitAll().toMap()
            }
            delay(4000)
        }
    }
    // Vista previa de cada cámara accesible, refrescada cada 30 s (el archivo temporal se borra al leerlo).
    LaunchedEffect(cams) {
        delay(1500)
        while (true) {
            cams.forEach { cam ->
                if (app.reach.value[cam.id] == true) {
                    val bmp = withContext(Dispatchers.IO) {
                        Net.snapshot(cam, File(ctx.cacheDir, "home"))?.let { f ->
                            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 }).also { f.delete() }
                        }
                    }
                    if (bmp != null) previews[cam.id] = bmp.asImageBitmap()
                }
            }
            delay(30_000)
        }
    }

    val startOfDay = remember {
        Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    }
    val onlineCount = cams.count { it.id in online || reach[it.id] == true }
    val eventsToday = events.count { it.time >= startOfDay }

    // Cámara principal (solo en FEATURED) y orden de las casillas: la principal primero.
    val main = cams.firstOrNull { it.id == featuredId } ?: cams.firstOrNull()
    val ordered = if (layout == MosaicLayout.FEATURED && main != null) listOf(main) + cams.filter { it.id != main.id } else cams
    // Ancho de cada casilla sobre la rejilla de 6 columnas.
    fun spanOf(cam: Camera?): Int = if (layout == MosaicLayout.FEATURED && cam?.id == main?.id && cam != null) 6 else layout.span

    Scaffold(
        topBar = {
            Bar("XMCam") {
                Box {
                    TextButton({ layoutMenu = true }) { Text("Vista") }
                    DropdownMenu(layoutMenu, { layoutMenu = false }) {
                        MosaicLayout.values().forEach { l ->
                            DropdownMenuItem(
                                { Text((if (l == layout) "✓ " else "    ") + l.label) },
                                { layout = l; app.store.mosaicLayout = l.ordinal; layoutMenu = false }
                            )
                        }
                    }
                }
                TextButton({ nav(Screen.Rules) }) { Text("Reglas") }
            }
        },
        bottomBar = { MainNavBar(MainTab.MOSAIC, nav) }
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(6), modifier = Modifier.padding(pad),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SummaryCard(onlineCount, cams.size, eventsToday, monitor) {
                    monitor = it
                    app.store.monitorEnabled = it
                    if (it) AlarmService.start(ctx) else AlarmService.stop(ctx)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    app.store.profiles.forEach { p ->
                        FilterChip(selected = p == profile, onClick = { app.setProfile(p) },
                            label = { Text(p) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
            }
            if (cams.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyCameras { nav(Screen.Add) } }
            items(ordered, key = { it.id }, span = { GridItemSpan(spanOf(it)) }) { cam ->
                val up = cam.id in online || reach[cam.id] == true
                val isMain = layout == MosaicLayout.FEATURED && cam.id == main?.id
                val secondary = layout == MosaicLayout.FEATURED && !isMain
                CameraTile(
                    cam, up, reach[cam.id], previews[cam.id],
                    lastEvent = events.firstOrNull { it.cameraId == cam.id }
                        ?.let { "${XmEvents.label(it.event)} · ${relativeTime(it.time)}" },
                    // En la vista principal+miniaturas, tocar una miniatura la sube a principal.
                    onOpen = { if (secondary) featuredId = cam.id else nav(Screen.Live(cam.id)) },
                    onLongPress = { toDelete = cam },
                    live = isMain || layout.liveSecondary,
                    compact = layout.span == 2 && !isMain
                )
            }
            item(span = { GridItemSpan(if (layout == MosaicLayout.FEATURED) 2 else layout.span) }) { AddTile { nav(Screen.Add) } }
            if (cams.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (layout == MosaicLayout.FEATURED) "Toca la principal para abrir el directo, una miniatura para ponerla de principal · mantén pulsado para borrar."
                    else "Toca una cámara para abrir el directo · mantén pulsado para borrarla.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    toDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Borrar cámara") },
            text = { Text("¿Borrar \"${c.name}\"?") },
            confirmButton = { TextButton({ app.saveCameras(cams.filter { it.id != c.id }); toDelete = null }) { Text("Borrar") } },
            dismissButton = { TextButton({ toDelete = null }) { Text("Cancelar") } }
        )
    }
}

/** Tarjeta de resumen compacta: en línea, eventos de hoy y el interruptor de vigilancia en segundo plano. */
@Composable
private fun SummaryCard(online: Int, total: Int, eventsToday: Int, monitor: Boolean, onMonitor: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Stat("$online/$total", "En línea", Modifier.weight(1f))
            Stat("$eventsToday", "Eventos hoy", Modifier.weight(1f))
            Column(Modifier.weight(1.2f), horizontalAlignment = Alignment.CenterHorizontally) {
                Switch(monitor, onMonitor)
                Text("Vigilancia", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Una cifra grande con su etiqueta debajo. */
@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Mensaje de lista vacía con botón para añadir la primera cámara. */
@Composable
private fun EmptyCameras(onAdd: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Añade tu primera cámara", style = MaterialTheme.typography.titleMedium)
            Text(
                "La app busca cámaras en tu red WiFi o puedes añadirlas con su IP.",
                Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onAdd) { Text("Añadir cámara") }
        }
    }
}

/**
 * Casilla del mosaico para una cámara: vista previa 16:9 con el estado arriba a la izquierda y,
 * abajo, el nombre y el último evento. Tocar = abrir directo; mantener pulsado = [onLongPress].
 *
 * @param up        true si la cámara está en línea.
 * @param reachable resultado de la última comprobación (null = comprobando).
 * @param preview   última captura de la cámara, o null si aún no hay.
 * @param lastEvent último evento ya formateado, o null si no hay.
 * @param live      true = vídeo en directo silenciado (si está en línea); false = solo la captura.
 * @param compact   true = casilla pequeña: nombre más pequeño y sin último evento.
 */
@Composable
private fun CameraTile(
    cam: Camera, up: Boolean, reachable: Boolean?, preview: ImageBitmap?, lastEvent: String?,
    onOpen: () -> Unit, onLongPress: () -> Unit, live: Boolean = true, compact: Boolean = false
) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
    ) {
        // Directo silenciado (stream SD) cuando la cámara está en línea; la captura se muestra hasta el primer fotograma.
        var ready by remember(cam.id, up) { mutableStateOf(false) }
        val showVideo = up && live
        if (showVideo) MutedLive(cam, { ready = true }, Modifier.matchParentSize())
        if (!showVideo || !ready) {
            if (preview != null) Image(preview, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            else Text(
                if (reachable == false) "Sin conexión" else "Sin vista previa", Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.bodySmall
            )
        }
        val dot = if (up) Color(0xFF2E7D32) else if (reachable == false) Color(0xFFC62828) else Color.Gray
        Box(
            Modifier.align(Alignment.TopStart).padding(6.dp).size(12.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape).padding(3.dp)
        ) { Box(Modifier.fillMaxSize().background(dot, CircleShape)) }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                cam.name, color = Color.White,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (lastEvent != null && !compact) Text(
                lastEvent, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Vídeo RTSP en directo y SIN audio (stream secundario, ligero) para una casilla del mosaico.
 * Reconecta solo cada 3 s si el flujo se cae y libera el reproductor al salir de la pantalla.
 * Reutilizable en cualquier miniatura en directo.
 *
 * @param onFirstFrame se llama al pintarse el primer fotograma (para retirar la captura de relleno).
 */
@Composable
private fun MutedLive(cam: Camera, onFirstFrame: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember(cam.id) {
        ExoPlayer.Builder(ctx)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1000, 3000, 250, 500).build())
            .build().apply {
                volume = 0f
                setMediaSource(
                    RtspMediaSource.Factory().setForceUseRtpTcp(true)
                        .createMediaSource(MediaItem.fromUri(cam.rtspUrl(1)))
                )
                prepare(); playWhenReady = true
            }
    }
    DisposableEffect(player) {
        var released = false
        val l = object : Player.Listener {
            override fun onRenderedFirstFrame() { onFirstFrame() }
            override fun onPlayerError(error: PlaybackException) {
                scope.launch { delay(3000); if (!released) player.prepare() }
            }
        }
        player.addListener(l)
        onDispose { released = true; player.removeListener(l); player.release() }
    }
    AndroidView(
        factory = { PlayerView(it).apply { useController = false } },
        update = { it.player = player },
        modifier = modifier
    )
}

/** Última casilla del mosaico: acceso rápido para añadir una cámara. */
@Composable
private fun AddTile(onAdd: () -> Unit) {
    Card(onClick = onAdd, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("+", style = MaterialTheme.typography.headlineMedium)
            Text("Añadir cámara", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Tiempo transcurrido desde [ms] en texto corto: "ahora", "hace 5 min", "hace 3 h", "hace 2 d". */
private fun relativeTime(ms: Long): String {
    val min = (System.currentTimeMillis() - ms) / 60_000
    return when {
        min < 1 -> "ahora"
        min < 60 -> "hace $min min"
        min < 60 * 24 -> "hace ${min / 60} h"
        else -> "hace ${min / (60 * 24)} d"
    }
}
