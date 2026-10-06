@file:OptIn(ExperimentalMaterial3Api::class, UnstableApi::class, ExperimentalFoundationApi::class)

package com.xmcam.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
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
 * Pantalla de una cámara: stream siempre visible arriba, barra de iconos (directo, grabaciones,
 * ajustes, info) debajo y, en el resto de la pantalla, el panel elegido.
 * El panel "directo" contiene HD/SD, audio, foto, PTZ y posiciones guardadas.
 */
@Composable
fun LiveScreen(camId: String, nav: (Screen) -> Unit, back: () -> Unit) {
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

    Scaffold(topBar = { Bar(cam.name, back) }) { pad ->
        Column(Modifier.padding(pad)) {
            Box(Modifier.padding(12.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))) {
                AndroidView(
                    factory = { PlayerView(it).apply { useController = false } },
                    update = { it.player = player },
                    modifier = Modifier.matchParentSize()
                )
                LiveBadge("EN VIVO", Color(0xFFD32F2F), Modifier.align(Alignment.TopStart).padding(8.dp))
                LiveBadge(if (stream == 0) "HD" else "SD", Color.Black.copy(alpha = 0.55f), Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
            if (status.isNotEmpty()) Text(status, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            PanelBar(panel) { panel = it }
            HorizontalDivider()
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

/** Barra de iconos para elegir el panel inferior; el seleccionado se resalta. */
@Composable
private fun PanelBar(selected: Panel, onSelect: (Panel) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf(
            Triple(Panel.LIVE, Icons.Default.PlayArrow, "Directo"),
            Triple(Panel.RECORDINGS, Icons.Default.List, "Grabaciones"),
            Triple(Panel.SETTINGS, Icons.Default.Settings, "Ajustes"),
            Triple(Panel.INFO, Icons.Default.Info, "Info")
        ).forEach { (p, icon, desc) ->
            IconButton(
                { onSelect(p) },
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (p == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) { Icon(icon, contentDescription = desc) }
        }
    }
}

/** Panel "directo": calidad HD/SD, audio, foto, joystick PTZ y posiciones guardadas. */
@Composable
private fun LivePanel(
    stream: Int, onStream: (Int) -> Unit, muted: Boolean, onMute: () -> Unit, onPhoto: () -> Unit,
    ptzCtl: PtzController, onPreset: (String, Int) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(stream == 0, { onStream(0) }, { Text("HD") })
                FilterChip(stream == 1, { onStream(1) }, { Text("SD") })
                FilledTonalButton(onMute) { Text(if (muted) "Sin audio" else "Audio") }
                FilledTonalButton(onPhoto) { Text("Foto") }
            }

            LiveSection("Control de la cámara", "Arrastra el joystick para mover la cámara; al soltar se detiene.")
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PtzJoystick(Modifier.size(230.dp)) { cmd, step -> ptzCtl.set(cmd, step) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                    HoldButton("Z+", { ptzCtl.set(PtzCmd.ZOOM_IN, 5) }, { ptzCtl.set(null) })
                    HoldButton("Z−", { ptzCtl.set(PtzCmd.ZOOM_OUT, 5) }, { ptzCtl.set(null) })
                    HoldButton("F+", { ptzCtl.set(PtzCmd.FOCUS_FAR, 5) }, { ptzCtl.set(null) })
                    HoldButton("F−", { ptzCtl.set(PtzCmd.FOCUS_NEAR, 5) }, { ptzCtl.set(null) })
                }
            }
            }

            LiveSection("Posiciones guardadas", "Toca un número para ir a esa posición; mantén pulsado para guardar la actual.")
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..6).forEach { n ->
                    Box(
                        Modifier.size(46.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                            .combinedClickable(onClick = { onPreset(PtzCmd.GOTO_PRESET, n) }, onLongClick = { onPreset(PtzCmd.SET_PRESET, n) }),
                        contentAlignment = Alignment.Center
                    ) { Text("$n") }
                }
            }
            Spacer(Modifier.height(24.dp))
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

/** Título de sección con una línea explicativa debajo. */
@Composable
private fun LiveSection(title: String, caption: String) {
    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Botón que avisa al pulsar y al soltar (los movimientos PTZ necesitan "start" y "stop"). */
@Composable
private fun HoldButton(label: String, onDown: () -> Unit, onUp: () -> Unit) {
    Surface(
        modifier = Modifier.size(60.dp).pointerInput(Unit) {
            detectTapGestures(onPress = { onDown(); try { awaitRelease() } finally { onUp() } })
        },
        shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer
    ) { Box(contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.titleLarge) } }
}
