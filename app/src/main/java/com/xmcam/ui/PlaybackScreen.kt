@file:OptIn(ExperimentalMaterial3Api::class)

package com.xmcam.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xmcam.App
import com.xmcam.data.ControlSession
import org.json.JSONObject
import java.time.LocalDate

/**
 * Grabaciones de la tarjeta SD: lista de clips de un día (mensaje OPFileQuery).
 * PENDIENTE: reproducir y descargar el clip (ver README, sección "Pendiente").
 */
@Composable
fun PlaybackScreen(camId: String, back: () -> Unit) {
    val cam = App.instance.cameras.value.first { it.id == camId }
    val session = remember { ControlSession(cam) }
    DisposableEffect(Unit) { onDispose { session.close() } }

    var day by remember { mutableStateOf(LocalDate.now()) }
    var files by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var msg by remember { mutableStateOf("Cargando…") }

    LaunchedEffect(day) {
        msg = "Cargando…"
        runCatching { session.exec { it.queryFiles("$day 00:00:00", "$day 23:59:59", cam.channel) } }
            .onSuccess { files = it; msg = if (it.isEmpty()) "Sin grabaciones este día" else "${it.size} grabaciones" }
            .onFailure { files = emptyList(); msg = "Error: ${it.message}" }
    }

    Scaffold(topBar = { Bar("Grabaciones · ${cam.name}", back) }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ day = day.minusDays(1) }) { Text("◀") }
                Text("$day", Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(enabled = day < LocalDate.now(), onClick = { day = day.plusDays(1) }) { Text("▶") }
            }
            Text(msg, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            LazyColumn {
                items(files) { f ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Text("${f.optString("BeginTime")}  →  ${f.optString("EndTime").takeLast(8)}")
                            Text(f.optString("FileName"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
