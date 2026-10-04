@file:OptIn(ExperimentalMaterial3Api::class)

package com.xmcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xmcam.App
import com.xmcam.data.Camera
import com.xmcam.data.Net
import com.xmcam.service.AlarmService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Pantalla principal: interruptor de vigilancia, perfiles y lista de cámaras. */
@Composable
fun HomeScreen(nav: (Screen) -> Unit) {
    val app = App.instance
    val ctx = LocalContext.current
    val cams by app.cameras.collectAsState()
    val online by app.online.collectAsState()
    val reach by app.reach.collectAsState()
    val profile by app.profile.collectAsState()
    var monitor by remember { mutableStateOf(app.store.monitorEnabled) }
    var toDelete by remember { mutableStateOf<Camera?>(null) }

    // Comprueba cada 4 s si cada cámara acepta conexiones (solo mientras esta pantalla está visible).
    LaunchedEffect(cams) {
        while (true) {
            app.reach.value = withContext(Dispatchers.IO) {
                cams.map { c -> async { c.id to Net.probe(c.host, c.port) } }.awaitAll().toMap()
            }
            delay(4000)
        }
    }

    Scaffold(
        topBar = {
            Bar("XMCam") {
                TextButton({ nav(Screen.Events) }) { Text("Eventos") }
                TextButton({ nav(Screen.Rules) }) { Text("Reglas") }
            }
        },
        floatingActionButton = { FloatingActionButton({ nav(Screen.Add) }) { Text("+", style = MaterialTheme.typography.titleLarge) } }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Vigilancia de alarmas en segundo plano", Modifier.weight(1f))
                    Switch(monitor, {
                        monitor = it
                        app.store.monitorEnabled = it
                        if (it) AlarmService.start(ctx) else AlarmService.stop(ctx)
                    })
                }
                Text("Perfil activo", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    app.store.profiles.forEach { p ->
                        FilterChip(selected = p == profile, onClick = { app.setProfile(p) },
                            label = { Text(p) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
            }
            if (cams.isEmpty()) item { Text("No hay cámaras. Pulsa + para añadir una.", Modifier.padding(24.dp)) }
            items(cams, key = { it.id }) { cam ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val up = cam.id in online || reach[cam.id] == true
                            val dot = if (up) Color(0xFF2E7D32) else if (reach[cam.id] == false) Color(0xFFC62828) else Color.Gray
                            Box(Modifier.size(12.dp).background(dot, CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(cam.name, style = MaterialTheme.typography.titleMedium)
                                Text("${cam.host} · ${if (up) "En línea" else if (reach[cam.id] == false) "Sin conexión" else "Comprobando…"}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row {
                            TextButton({ nav(Screen.Live(cam.id)) }) { Text("Directo") }
                            TextButton({ nav(Screen.Playback(cam.id)) }) { Text("Grabaciones") }
                            TextButton({ nav(Screen.Settings(cam.id)) }) { Text("Ajustes") }
                            TextButton({ toDelete = cam }) { Text("Borrar") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
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
