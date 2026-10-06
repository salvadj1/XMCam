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
import com.xmcam.data.ControlSession
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ajustes de una cámara: acceso a todos los bloques de configuración, hora y reinicio. Con [embedded] se omite la barra superior (se usa como panel de LiveScreen). */
@Composable
fun SettingsScreen(camId: String, nav: (Screen) -> Unit, back: () -> Unit, embedded: Boolean = false) {
    val app = App.instance
    val cam = app.cameras.value.first { it.id == camId }
    val scope = rememberCoroutineScope()
    val session = remember { ControlSession(cam) }
    DisposableEffect(Unit) { onDispose { session.close() } }

    var msg by remember { mutableStateOf("") }
    var confirmReboot by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }
    var monitor by remember { mutableStateOf(cam.monitor) }
    var rtspHash by remember { mutableStateOf(cam.rtspHash) }

    // (título, descripción, bloque de configuración, filtro regex de ajustes visibles)
    val blocks = listOf(
        CfgBlock("Alarma de movimiento", "Qué hace la cámara al detectar movimiento: grabar, enviar email, sonar...", "Detect.MotionDetect", "Enable|EventHandler"),
        CfgBlock("Alarma de persona", "Acciones y sensibilidad cuando detecta una persona.", "Detect.HumanDetection", "Enable|EventHandler|Sensit"),
        CfgBlock("Email (SMTP)", "Servidor y cuenta para que la cámara envíe avisos por correo.", "NetWork.NetEmail", null),
        CfgBlock("FTP", "Servidor donde la cámara sube fotos y vídeos de las alarmas.", "NetWork.NetFTP", null),
        CfgBlock("Codificación de vídeo", "Resolución, calidad y fotogramas del flujo principal y secundario.", "Simplify.Encode", null),
        CfgBlock("Imagen", "Brillo, contraste, espejo, giro y modo infrarrojo.", "Camera.Param", null),
        CfgBlock("Imagen: extras", "WDR, poca luz, estabilizador, modo pasillo y corrección de distorsión.", "Camera.ParamEx", null),
        CfgBlock("Texto en pantalla (OSD)", "Nombre y hora que se dibujan sobre el vídeo.", "AVEnc.VideoWidget", null),
        CfgBlock("Red", "IP, puerta de enlace y puertos de la cámara.", "NetWork.NetCommon", null),
        CfgBlock("WiFi", "Red inalámbrica a la que se conecta la cámara.", "NetWork.Wifi", null),
        CfgBlock("Hora y NTP", "Zona horaria y sincronización automática de la hora.", "NetWork.NetNTP", null),
        CfgBlock("Grabación", "Modo, duración y programación de las grabaciones en la SD.", "Record", null),
        CfgBlock("Almacenamiento (SD)", "Capacidad, espacio libre y formateo de la tarjeta SD.", "StorageInfo", null),
        CfgBlock("Mantenimiento automático", "Reinicio y borrado automático programados.", "General.AutoMaintain", null),
        CfgBlock("Idioma y fecha", "Idioma del menú de la cámara y formato de fecha y hora.", "General.Location", null),
        CfgBlock("PTZ (protocolo)", "Protocolo, dirección y velocidad del motor de giro.", "Uart.PTZ", null)
    )

    Scaffold(
        topBar = { if (!embedded) Bar("Ajustes · ${cam.name}", back) },
        contentWindowInsets = if (embedded) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets
    ) { pad ->
        Column(Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState())) {
            SectionTitle("En esta app")
            SettingRow("Vigilar alarmas de esta cámara", "Recibes avisos de esta cámara cuando la vigilancia en segundo plano está activada.") {
                Switch(monitor, { monitor = it; app.saveCameras(app.cameras.value.map { c -> if (c.id == cam.id) c.copy(monitor = it) else c }) })
            }
            SettingRow("RTSP con hash XM", "Actívalo solo si el vídeo no abre: algunos firmwares piden la contraseña en formato hash.") {
                Switch(rtspHash, { rtspHash = it; app.saveCameras(app.cameras.value.map { c -> if (c.id == cam.id) c.copy(rtspHash = it) else c }) })
            }

            SectionTitle("Configuración de la cámara")
            blocks.forEach { blk ->
                Card(
                    onClick = { nav(Screen.Config(camId, blk.name, blk.filter)) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(blk.title, style = MaterialTheme.typography.titleSmall)
                            Text(blk.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.padding(top = 6.dp)) {
                OutlinedTextField(custom, { custom = it }, label = { Text("Otro bloque (p. ej. Detect.BlindDetect)") },
                    singleLine = true, modifier = Modifier.weight(1f))
                TextButton({ if (custom.isNotBlank()) nav(Screen.Config(camId, custom.trim(), null)) }) { Text("Abrir") }
            }

            SectionTitle("Mantenimiento")
            SettingRow("Sincronizar hora", "Pone el reloj de la cámara en hora con el del móvil.") {
                Button({
                    scope.launch {
                        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                        msg = runCatching { session.exec { it.setTime(now) } }.fold({ "Hora sincronizada: $now" }, { "Error: ${it.message}" })
                    }
                }) { Text("Sincronizar") }
            }
            SettingRow("Reiniciar cámara", "Reinicia el equipo; la configuración se conserva.") {
                OutlinedButton({ confirmReboot = true }) { Text("Reiniciar") }
            }
            if (msg.isNotEmpty()) Text(msg, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
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

/** Un bloque de configuración de la cámara: [title] y [description] se muestran en la lista; [name] y [filter] abren el editor. */
private data class CfgBlock(val title: String, val description: String, val name: String, val filter: String?)
