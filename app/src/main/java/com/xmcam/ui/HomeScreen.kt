@file:OptIn(ExperimentalMaterial3Api::class)

package com.xmcam.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xmcam.App
import com.xmcam.data.Camera
import com.xmcam.data.Net
import com.xmcam.protocol.XmEvents
import com.xmcam.service.AlarmService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/**
 * Pantalla principal en tres bloques: resumen (cámaras en línea, eventos de hoy, vigilancia),
 * perfil activo y lista de cámaras con vista previa, estado, último evento y accesos rápidos.
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

    Scaffold(
        topBar = {
            Bar("XMCam") {
                TextButton({ nav(Screen.Events) }) { Text("Eventos") }
                TextButton({ nav(Screen.Rules) }) { Text("Reglas") }
                TextButton({ nav(Screen.AppSettings) }) { Text("Ajustes") }
            }
        },
        floatingActionButton = { ExtendedFloatingActionButton({ nav(Screen.Add) }) { Text("+  Añadir cámara") } }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SummaryCard(onlineCount, cams.size, eventsToday, monitor) {
                    monitor = it
                    app.store.monitorEnabled = it
                    if (it) AlarmService.start(ctx) else AlarmService.stop(ctx)
                }
            }
            item {
                HomeSection("Perfil activo", "Las reglas pueden limitarse a un perfil.")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp)) {
                    app.store.profiles.forEach { p ->
                        FilterChip(selected = p == profile, onClick = { app.setProfile(p) },
                            label = { Text(p) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
            }
            item { HomeSection("Cámaras", if (cams.isEmpty()) "Aún no hay ninguna." else "${cams.size} en total · $onlineCount en línea") }
            if (cams.isEmpty()) item { EmptyCameras { nav(Screen.Add) } }
            items(cams, key = { it.id }) { cam ->
                val up = cam.id in online || reach[cam.id] == true
                val last = events.firstOrNull { it.cameraId == cam.id }
                CameraCard(
                    cam, up, reach[cam.id], previews[cam.id],
                    last?.let { "Último evento: ${XmEvents.label(it.event)} · ${relativeTime(it.time)}" } ?: "Sin eventos recientes",
                    onLive = { nav(Screen.Live(cam.id)) },
                    onRecordings = { nav(Screen.Playback(cam.id)) },
                    onSettings = { nav(Screen.Settings(cam.id)) },
                    onDelete = { toDelete = cam }
                )
            }
            item { Spacer(Modifier.height(72.dp)) }
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

/** Tarjeta de resumen: tres cifras (en línea, eventos de hoy, estado) y el interruptor de vigilancia. */
@Composable
private fun SummaryCard(online: Int, total: Int, eventsToday: Int, monitor: Boolean, onMonitor: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Stat("$online/$total", "En línea", Modifier.weight(1f))
                Stat("$eventsToday", "Eventos hoy", Modifier.weight(1f))
                Stat(if (monitor) "Activa" else "Apagada", "Vigilancia", Modifier.weight(1f))
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Vigilancia en segundo plano", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Recibe avisos de alarma aunque la app esté cerrada.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(monitor, onMonitor)
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

/** Título de bloque con una línea explicativa debajo. */
@Composable
private fun HomeSection(title: String, caption: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
 * Tarjeta de una cámara: vista previa con estado superpuesto, nombre, dirección, último evento
 * y botones Directo / Grabaciones / Ajustes, más Borrar.
 *
 * @param up       true si la cámara está en línea.
 * @param reachable resultado de la última comprobación (null = comprobando).
 * @param preview  última captura de la cámara, o null si aún no hay.
 * @param lastEvent texto ya formateado del último evento.
 */
@Composable
private fun CameraCard(
    cam: Camera, up: Boolean, reachable: Boolean?, preview: ImageBitmap?, lastEvent: String,
    onLive: () -> Unit, onRecordings: () -> Unit, onSettings: () -> Unit, onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.secondaryContainer)) {
            if (preview != null) Image(preview, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            else Text(
                if (reachable == false) "Sin conexión" else "Sin vista previa", Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.bodyMedium
            )
            val dot = if (up) Color(0xFF2E7D32) else if (reachable == false) Color(0xFFC62828) else Color.Gray
            Row(
                Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (up) "En línea" else if (reachable == false) "Sin conexión" else "Comprobando…",
                    color = Color.White, style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(cam.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(cam.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onDelete) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
            }
            Text(lastEvent, Modifier.padding(top = 2.dp, bottom = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val pad = PaddingValues(horizontal = 4.dp)
                FilledTonalButton(onLive, Modifier.weight(1f), contentPadding = pad) { Text("Directo", maxLines = 1) }
                OutlinedButton(onRecordings, Modifier.weight(1f), contentPadding = pad) { Text("Grabaciones", maxLines = 1) }
                OutlinedButton(onSettings, Modifier.weight(1f), contentPadding = pad) { Text("Ajustes", maxLines = 1) }
            }
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
