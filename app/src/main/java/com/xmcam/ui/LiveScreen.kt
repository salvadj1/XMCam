@file:OptIn(ExperimentalMaterial3Api::class, UnstableApi::class, ExperimentalFoundationApi::class)

package com.xmcam.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import com.xmcam.data.ControlSession
import com.xmcam.data.Net
import com.xmcam.data.PtzController
import com.xmcam.protocol.PtzCmd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Paneles que se pueden mostrar bajo el stream. */
private enum class Panel { LIVE, RECORDINGS, SETTINGS, INFO }

/** Panel a restaurar al volver (p. ej. tras abrir un clip o un bloque de configuración), por cámara. */
private val pendingPanel = mutableMapOf<String, Panel>()

/**
 * Pantalla de una cámara (estilo "directo primero"): carrusel de cámaras arriba, stream siempre visible,
 * el panel elegido en el centro y la barra de paneles (directo, grabaciones, ajustes, info) abajo.
 * El panel "directo" contiene HD/SD, audio, foto, joystick PTZ, zoom/enfoque y 6 posiciones guardadas.
 *
 * @param onSwitch cambia a otra cámara desde el carrusel (la navegación sustituye esta pantalla).
 */
@Composable
fun LiveScreen(camId: String, nav: (Screen) -> Unit, back: () -> Unit, onSwitch: (String) -> Unit = {}) {
    val cams by App.instance.cameras.collectAsState()
    val cam = cams.first { it.id == camId }
    var panel by remember { mutableStateOf(pendingPanel.remove(camId) ?: Panel.LIVE) }
    // Al abrir otra pantalla desde un panel se recuerda cuál era, para volver a él.
    val navKeep: (Screen) -> Unit = { pendingPanel[camId] = panel; nav(it) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { ControlSession(cam) }
    DisposableEffect(Unit) { onDispose { session.close() } }

    var stream by remember { mutableIntStateOf(App.instance.store.defaultStream) }   // 0 = principal (HD), 1 = secundario (ligero)
    var status by remember { mutableStateOf("") }

    // Mantiene la pantalla encendida mientras el directo está abierto (si el usuario lo activó).
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = App.instance.store.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    var muted by remember { mutableStateOf(false) }

    val player = remember(stream) {
        // Buffers cortos: tras un microcorte el vídeo se reanuda en ~0,5 s en vez de esperar 5 s (valor por defecto).
        ExoPlayer.Builder(ctx)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1000, 3000, 250, 500).build())
            .build().apply {
            val src = RtspMediaSource.Factory().setForceUseRtpTcp(true)
                .createMediaSource(MediaItem.fromUri(cam.rtspUrl(stream)))
            setMediaSource(src); prepare(); playWhenReady = true
        }
    }
    DisposableEffect(player) {
        var released = false
        val l = object : Player.Listener {
            // Si el flujo se cae, se reintenta solo cada 1,5 s mientras la pantalla siga abierta.
            override fun onPlayerError(error: PlaybackException) {
                status = "Reconectando vídeo… (${error.errorCodeName})"
                scope.launch { delay(1500); if (!released) player.prepare() }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && status.startsWith("Reconectando")) status = ""
            }
        }
        player.addListener(l)
        onDispose { released = true; player.removeListener(l); player.release() }
    }
    LaunchedEffect(muted, player) { player.volume = if (muted) 0f else 1f }

    // PTZ continuo (joystick, zoom, enfoque): conexión propia, sin esperar respuestas, al soltar se para.
    val ptzCtl = remember { PtzController(cam) }
    DisposableEffect(Unit) { ptzCtl.warmUp(); onDispose { ptzCtl.close() } }
    fun preset(cmd: String, n: Int) {
        scope.launch {
            runCatching { session.exec { it.ptz(cmd, cam.channel, preset = n) } }
                .onSuccess { status = if (cmd == PtzCmd.SET_PRESET) "Preset $n guardado" else "Preset $n" }
                .onFailure { status = "PTZ: ${it.message}" }
        }
    }

    Scaffold(
        topBar = { Bar(cam.name, back) { TextButton({ nav(Screen.Gallery(camId)) }) { Text("Galería") } } },
        bottomBar = { PanelBar(panel) { panel = it } }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            CameraCarousel(cams.map { it.id to it.name }, camId, onSwitch)
            Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))) {
                AndroidView(
                    factory = { PlayerView(it).apply { useController = false } },
                    update = { it.player = player },
                    modifier = Modifier.matchParentSize()
                )
                LiveBadge("EN VIVO", Color(0xFFD32F2F), Modifier.align(Alignment.TopStart).padding(8.dp))
                LiveBadge(if (stream == 0) "HD" else "SD", Color.Black.copy(alpha = 0.55f), Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
            if (status.isNotEmpty()) Text(status, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (panel) {
                    Panel.LIVE -> LivePanel(
                        stream, { stream = it }, muted, { muted = !muted },
                        onPhoto = {
                            scope.launch {
                                status = "Capturando…"
                                val ok = withContext(Dispatchers.IO) { Net.snapshot(cam, ctx.cacheDir)?.let { Net.saveToGallery(ctx, it) } ?: false }
                                status = if (ok) "Foto guardada en Pictures/XMCam" else "No se pudo capturar"
                            }
                        },
                        ptzCtl = ptzCtl, onPreset = ::preset
                    )
                    Panel.RECORDINGS -> PlaybackScreen(camId, navKeep, back, embedded = true)
                    Panel.SETTINGS -> SettingsScreen(camId, navKeep, back, embedded = true)
                    Panel.INFO -> CameraInfoPanel(cam)
                }
            }
        }
    }
}

/**
 * Carrusel horizontal de cámaras (chips). La activa se resalta; tocar otra llama a [onSwitch].
 * Reutilizable con cualquier lista de pares (id, nombre).
 */
@Composable
private fun CameraCarousel(items: List<Pair<String, String>>, selectedId: String, onSwitch: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        items.forEach { (id, name) ->
            FilterChip(id == selectedId, { if (id != selectedId) onSwitch(id) }, { Text(name, maxLines = 1) }, Modifier.padding(end = 6.dp))
        }
    }
}

/** Barra inferior para elegir el panel (Directo, Grabaciones, Ajustes, Info); el seleccionado se resalta. */
@Composable
private fun PanelBar(selected: Panel, onSelect: (Panel) -> Unit) {
    NavigationBar {
        listOf(
            Triple(Panel.LIVE, Icons.Default.PlayArrow, "Directo"),
            Triple(Panel.RECORDINGS, Icons.Default.List, "Grabaciones"),
            Triple(Panel.SETTINGS, Icons.Default.Settings, "Ajustes"),
            Triple(Panel.INFO, Icons.Default.Info, "Info")
        ).forEach { (p, icon, label) ->
            NavigationBarItem(p == selected, { onSelect(p) }, { Icon(icon, contentDescription = label) }, label = { Text(label) })
        }
    }
}

/**
 * Panel "directo" compacto: fila HD/SD + audio + foto; joystick PTZ a la izquierda con zoom y enfoque
 * (2x2) a la derecha; y las 6 posiciones guardadas (toque = ir, pulsación larga = guardar la actual).
 */
@Composable
private fun LivePanel(
    stream: Int, onStream: (Int) -> Unit, muted: Boolean, onMute: () -> Unit, onPhoto: () -> Unit,
    ptzCtl: PtzController, onPreset: (String, Int) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(stream == 0, { onStream(0) }, { Text("HD") })
            FilterChip(stream == 1, { onStream(1) }, { Text("SD") })
            FilledTonalButton(onMute) { Text(if (muted) "Sin audio" else "Audio") }
            FilledTonalButton(onPhoto) { Text("Foto") }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            PtzJoystick(Modifier.size(160.dp)) { cmd, step -> ptzCtl.set(cmd, step) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HoldButton("Z+", { ptzCtl.set(PtzCmd.ZOOM_IN, 5) }, { ptzCtl.set(null) }, 52.dp)
                    HoldButton("Z−", { ptzCtl.set(PtzCmd.ZOOM_OUT, 5) }, { ptzCtl.set(null) }, 52.dp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HoldButton("F+", { ptzCtl.set(PtzCmd.FOCUS_FAR, 5) }, { ptzCtl.set(null) }, 52.dp)
                    HoldButton("F−", { ptzCtl.set(PtzCmd.FOCUS_NEAR, 5) }, { ptzCtl.set(null) }, 52.dp)
                }
            }
        }
        Text(
            "Posiciones: toca para ir · mantén pulsado para guardar la actual",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            (1..6).forEach { n ->
                Box(
                    Modifier.size(46.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                        .combinedClickable(onClick = { onPreset(PtzCmd.GOTO_PRESET, n) }, onLongClick = { onPreset(PtzCmd.SET_PRESET, n) }),
                    contentAlignment = Alignment.Center
                ) { Text("$n") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Etiqueta pequeña sobre el vídeo (EN VIVO, HD/SD). [color] es el fondo; el texto va en blanco. */
@Composable
private fun LiveBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text, modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        color = Color.White, style = MaterialTheme.typography.labelSmall
    )
}

/** Botón que avisa al pulsar y al soltar (los movimientos PTZ necesitan "start" y "stop"). */
@Composable
private fun HoldButton(label: String, onDown: () -> Unit, onUp: () -> Unit, size: androidx.compose.ui.unit.Dp = 60.dp) {
    Surface(
        modifier = Modifier.size(size).pointerInput(Unit) {
            detectTapGestures(onPress = { onDown(); try { awaitRelease() } finally { onUp() } })
        },
        shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer
    ) { Box(contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.titleLarge) } }
}
