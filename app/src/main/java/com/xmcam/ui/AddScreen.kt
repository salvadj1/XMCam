@file:OptIn(ExperimentalMaterial3Api::class)

package com.xmcam.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xmcam.App
import com.xmcam.data.Camera
import com.xmcam.data.Net
import com.xmcam.protocol.DvripClient
import com.xmcam.protocol.XmDiagnostics
import com.xmcam.protocol.XmDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Añadir cámara: búsqueda automática en la LAN (UDP 34569) o datos manuales. */
@Composable
fun AddScreen(back: () -> Unit) {
    val app = App.instance
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("34567") }
    var user by remember { mutableStateOf("admin") }
    var pass by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<XmDiscovery.Found>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val diag = remember { mutableStateListOf<String>() }
    var diagRunning by remember { mutableStateOf(false) }

    fun build() = Camera(
        name = name.ifBlank { host }, host = host.trim(), port = port.toIntOrNull() ?: 34567,
        user = user, password = pass
    )

    Scaffold(topBar = { Bar("Añadir cámara", back) }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState())) {
            Button(enabled = !scanning, onClick = {
                scope.launch {
                    scanning = true; msg = ""
                    val r = withContext(Dispatchers.IO) { runCatching { Net.withMulticastLock(ctx) { XmDiscovery.scan() } } }
                    found = r.getOrDefault(emptyList())
                    scanning = false
                    msg = r.exceptionOrNull()?.let { "Error al buscar: ${it.javaClass.simpleName}: ${it.message}" }
                        ?: if (found.isEmpty()) "No se encontró ninguna. Introduce la IP y pulsa Diagnóstico." else ""
                }
            }) { Text(if (scanning) "Buscando…" else "Buscar en la red") }

            found.forEach { f ->
                OutlinedButton(onClick = {
                    host = f.ip; port = f.tcpPort.toString(); if (name.isBlank()) name = f.name
                }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("${f.name}  ·  ${f.ip}") }
            }

            SectionTitle("Datos de la cámara")
            Field("Nombre", name, { name = it })
            Field("IP", host, { host = it })
            Field("Puerto", port, { port = it }, number = true)
            Field("Usuario", user, { user = it })
            Field("Contraseña", pass, { pass = it }, password = true)

            Button(modifier = Modifier.padding(top = 8.dp), enabled = host.isNotBlank(), onClick = {
                scope.launch {
                    msg = "Probando conexión…"
                    val cam = build()
                    val r = withContext(Dispatchers.IO) {
                        runCatching { DvripClient(cam.host, cam.port).use { it.login(cam.user, cam.password); it.channelCount } }
                    }
                    r.onSuccess { app.saveCameras(app.cameras.value + cam); back() }
                        .onFailure { msg = "No se pudo conectar: ${it.javaClass.simpleName}: ${it.message}. Pulsa Diagnóstico." }
                }
            }) { Text("Probar y guardar") }

            TextButton(enabled = host.isNotBlank(), onClick = { app.saveCameras(app.cameras.value + build()); back() }) {
                Text("Guardar sin probar")
            }
            OutlinedButton(modifier = Modifier.padding(top = 4.dp), enabled = host.isNotBlank() && !diagRunning, onClick = {
                scope.launch {
                    diagRunning = true; diag.clear()
                    diag.add("Red de la app: ${Net.describeNetwork(ctx)}")
                    val cam = build()
                    withContext(Dispatchers.IO) {
                        XmDiagnostics.run(cam.host, cam.port, cam.user, cam.password, cam.httpPort, cam.rtspPort) { line ->
                            scope.launch { diag.add(line) }
                        }
                    }
                    diagRunning = false
                }
            }) { Text(if (diagRunning) "Diagnosticando…" else "Diagnóstico de conexión") }

            if (msg.isNotEmpty()) Text(msg, Modifier.padding(top = 8.dp))
            if (diag.isNotEmpty()) {
                SectionTitle("Diagnóstico")
                Text(diag.joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                TextButton({ clip.setText(AnnotatedString(diag.joinToString("\n"))) }) { Text("Copiar resultado") }
            }
        }
    }
}
