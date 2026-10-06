@file:OptIn(ExperimentalMaterial3Api::class)

package com.xmcam.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xmcam.App
import com.xmcam.data.Camera
import com.xmcam.data.ControlSession

/**
 * Panel con la información de una cámara (datos guardados en la app + datos que devuelve la cámara)
 * y la opción de cambiarle el nombre. Se muestra dentro de [LiveScreen], bajo el stream.
 *
 * @param cam cámara a mostrar (se actualiza sola si cambia, p. ej. tras renombrarla).
 */
@Composable
fun CameraInfoPanel(cam: Camera) {
    val app = App.instance
    val session = remember(cam.id) { ControlSession(cam) }
    DisposableEffect(cam.id) { onDispose { session.close() } }

    var info by remember { mutableStateOf("Cargando…") }
    var renaming by remember { mutableStateOf(false) }

    // Pide a la cámara su información de sistema y su hora.
    LaunchedEffect(cam.id) {
        info = runCatching {
            session.exec { c ->
                val si = c.systemInfo()
                val t = runCatching { c.getTime() }.getOrDefault("?")
                si.keys().asSequence().joinToString("\n") { "$it: ${si.opt(it)}" } + "\nHora de la cámara: $t"
            }
        }.getOrElse { "Error: ${it.message}" }
    }

    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Nombre", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(cam.name, style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton({ renaming = true }) { Text("Cambiar nombre") }
        }
        SectionTitle("Conexión")
        Card(Modifier.fillMaxWidth()) {
            Text(
                "Dirección: ${cam.host}\nPuerto: ${cam.port} · RTSP: ${cam.rtspPort} · HTTP: ${cam.httpPort}\nUsuario: ${cam.user}\nCanal: ${cam.channel + 1}",
                Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall
            )
        }
        SectionTitle("Información de la cámara")
        Card(Modifier.fillMaxWidth()) { Text(info, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
    }

    if (renaming) RenameDialog(cam.name, { renaming = false }) { newName ->
        app.saveCameras(app.cameras.value.map { if (it.id == cam.id) it.copy(name = newName) else it })
        renaming = false
    }
}

/**
 * Diálogo para escribir un nombre nuevo. Reutilizable en cualquier pantalla.
 *
 * @param current   nombre actual (valor inicial del campo).
 * @param onDismiss se llama al cancelar.
 * @param onConfirm se llama con el nombre ya recortado; el botón queda desactivado si está vacío.
 */
@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cambiar nombre") },
        text = { Field("Nombre de la cámara", text, { text = it }) },
        confirmButton = { TextButton({ onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("Guardar") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } }
    )
}
