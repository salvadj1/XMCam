@file:OptIn(ExperimentalMaterial3Api::class)

package com.xmcam.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xmcam.App
import com.xmcam.data.ControlSession
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ajustes de una cámara: información, acceso a todos los bloques de configuración, hora y reinicio. */
@Composable
fun SettingsScreen(camId: String, nav: (Screen) -> Unit, back: () -> Unit) {
    val app = App.instance
    val cam = app.cameras.value.first { it.id == camId }
    val scope = rememberCoroutineScope()
    val session = remember { ControlSession(cam) }
    DisposableEffect(Unit) { onDispose { session.close() } }

    var info by remember { mutableStateOf("Cargando…") }
    var msg by remember { mutableStateOf("") }
    var confirmReboot by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }
    var monitor by remember { mutableStateOf(cam.monitor) }
    var rtspHash by remember { mutableStateOf(cam.rtspHash) }

    LaunchedEffect(Unit) {
        info = runCatching {
            session.exec { c ->
                val si = c.systemInfo()
                val t = runCatching { c.getTime() }.getOrDefault("?")
                si.keys().asSequence().joinToString("\n") { "$it: ${si.opt(it)}" } + "\nHora de la cámara: $t"
            }
        }.getOrElse { "Error: ${it.message}" }
    }

    // (título, bloque de configuración, filtro regex de ajustes visibles)
    val blocks = listOf(
        Triple("Alarma de movimiento: qué hacer", "Detect.MotionDetect", "Enable|EventHandler"),
        Triple("Alarma de persona: qué hacer", "Detect.HumanDetection", "Enable|EventHandler|Sensit"),
        Triple("Email (SMTP)", "NetWork.NetEmail", null),
        Triple("FTP", "NetWork.NetFTP", null),
        Triple("Codificación de vídeo", "Simplify.Encode", null),
        Triple("Imagen (brillo, espejo, IR...)", "Camera.Param", null),
        Triple("Texto en pantalla (OSD)", "AVEnc.VideoWidget", null),
        Triple("Red", "NetWork.NetCommon", null),
        Triple("WiFi", "NetWork.Wifi", null),
        Triple("Hora y NTP", "NetWork.NetNTP", null),
        Triple("Grabación", "Record", null),
        Triple("Almacenamiento (SD)", "StorageInfo", null),
        Triple("Mantenimiento automático", "General.AutoMaintain", null),
        Triple("Idioma y formato de fecha", "General.Location", null),
        Triple("PTZ (protocolo)", "Uart.PTZ", null)
    )

    Scaffold(topBar = { Bar("Ajustes · ${cam.name}", back) }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState())) {
            SectionTitle("Información")
            Text(info, style = MaterialTheme.typography.bodySmall)

            SectionTitle("En esta app")
            Row {
                Text("Vigilar alarmas de esta cámara", Modifier.weight(1f))
                Switch(monitor, { monitor = it; app.saveCameras(app.cameras.value.map { c -> if (c.id == cam.id) c.copy(monitor = it) else c }) })
            }
            Row {
                Text("RTSP con hash XM (si el vídeo no abre)", Modifier.weight(1f))
                Switch(rtspHash, { rtspHash = it; app.saveCameras(app.cameras.value.map { c -> if (c.id == cam.id) c.copy(rtspHash = it) else c }) })
            }

            SectionTitle("Configuración de la cámara")
            blocks.forEach { (title, name, filter) ->
                OutlinedButton({ nav(Screen.Config(camId, name, filter)) }, Modifier.fillMaxWidth().padding(vertical = 2.dp)) { Text(title) }
            }
            Row(Modifier.padding(top = 6.dp)) {
                OutlinedTextField(custom, { custom = it }, label = { Text("Otro bloque (p. ej. Detect.BlindDetect)") },
                    singleLine = true, modifier = Modifier.weight(1f))
                TextButton({ if (custom.isNotBlank()) nav(Screen.Config(camId, custom.trim(), null)) }) { Text("Abrir") }
            }

            SectionTitle("Mantenimiento")
            Button({
                scope.launch {
                    val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                    msg = runCatching { session.exec { it.setTime(now) } }.fold({ "Hora sincronizada: $now" }, { "Error: ${it.message}" })
                }
            }) { Text("Sincronizar hora con el móvil") }
            OutlinedButton({ confirmReboot = true }, Modifier.padding(top = 6.dp)) { Text("Reiniciar cámara") }
            if (msg.isNotEmpty()) Text(msg, Modifier.padding(top = 8.dp))
        }
    }

    if (confirmReboot) AlertDialog(
        onDismissRequest = { confirmReboot = false },
        title = { Text("Reiniciar") }, text = { Text("¿Reiniciar la cámara ahora?") },
        confirmButton = {
            TextButton({
                confirmReboot = false
                scope.launch { runCatching { session.exec { it.reboot() } }; msg = "Reinicio enviado" }
            }) { Text("Reiniciar") }
        },
        dismissButton = { TextButton({ confirmReboot = false }) { Text("Cancelar") } }
    )
}
