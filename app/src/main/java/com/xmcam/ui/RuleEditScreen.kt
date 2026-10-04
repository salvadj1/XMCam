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
import com.xmcam.data.Action
import com.xmcam.data.ActionType
import com.xmcam.data.Rule
import com.xmcam.protocol.XmEvents

private fun parseMin(s: String): Int {
    val p = s.trim().split(":")
    if (p.size != 2) return -1
    val h = p[0].toIntOrNull() ?: return -1
    val m = p[1].toIntOrNull() ?: return -1
    return h * 60 + m
}
private fun fmtMin(m: Int) = "%02d:%02d".format(m / 60, m % 60)

/** Crear/editar una regla: cuándo se dispara y qué acciones ejecuta. */
@Composable
fun RuleEditScreen(ruleId: String?, back: () -> Unit) {
    val app = App.instance
    val cams = app.cameras.value
    val existing = ruleId?.let { id -> app.rules.value.firstOrNull { it.id == id } }

    var name by remember { mutableStateOf(existing?.name ?: "Nueva regla") }
    var camId by remember { mutableStateOf(existing?.cameraId ?: "") }
    val events = remember { mutableStateListOf<String>().apply { addAll(existing?.events ?: setOf("*")) } }
    var onlyStart by remember { mutableStateOf(existing?.onlyStart ?: true) }
    var from by remember { mutableStateOf(existing?.fromMin?.takeIf { it >= 0 }?.let { fmtMin(it) } ?: "") }
    var to by remember { mutableStateOf(existing?.toMin?.takeIf { it >= 0 }?.let { fmtMin(it) } ?: "") }
    var profile by remember { mutableStateOf(existing?.profile ?: "") }
    var cooldown by remember { mutableStateOf((existing?.cooldownSec ?: 30).toString()) }

    fun has(t: String) = existing?.actions?.any { it.type == t } == true
    fun param(t: String) = existing?.actions?.firstOrNull { it.type == t }?.param ?: ""
    var aNotify by remember { mutableStateOf(existing == null || has(ActionType.NOTIFY)) }
    var aSnap by remember { mutableStateOf(has(ActionType.SNAPSHOT)) }
    var aPtz by remember { mutableStateOf(has(ActionType.PTZ_PRESET)) }
    var ptzN by remember { mutableStateOf(param(ActionType.PTZ_PRESET).ifEmpty { "1" }) }
    var aHttp by remember { mutableStateOf(has(ActionType.HTTP_GET)) }
    var url by remember { mutableStateOf(param(ActionType.HTTP_GET)) }

    fun toggle(e: String) {
        if (e == "*") { events.clear(); events.add("*") }
        else {
            events.remove("*")
            if (e in events) events.remove(e) else events.add(e)
            if (events.isEmpty()) events.add("*")
        }
    }

    @Composable fun check(label: String, v: Boolean, on: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(v, on); Text(label) }
    }

    Scaffold(topBar = {
        Bar(if (existing == null) "Nueva regla" else "Editar regla", back) {
            if (existing != null) TextButton({
                app.saveRules(app.rules.value.filter { it.id != existing.id }); back()
            }) { Text("Borrar") }
        }
    }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState())) {
            Field("Nombre", name, { name = it })

            SectionTitle("Cámara")
            Row(Modifier.fillMaxWidth()) {
                FilterChip(camId == "", { camId = "" }, { Text("Todas") }, Modifier.padding(end = 6.dp))
                cams.forEach { c -> FilterChip(camId == c.id, { camId = c.id }, { Text(c.name) }, Modifier.padding(end = 6.dp)) }
            }

            SectionTitle("Cuando ocurra")
            check("Cualquier evento", "*" in events) { toggle("*") }
            XmEvents.labels.forEach { (k, v) -> check(v, k in events) { toggle(k) } }
            check("Solo al empezar (no al terminar)", onlyStart) { onlyStart = it }

            SectionTitle("Condiciones")
            Row {
                OutlinedTextField(from, { from = it }, label = { Text("Desde (HH:mm)") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(to, { to = it }, label = { Text("Hasta (HH:mm)") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Text("Vacío = a cualquier hora. Admite franjas que cruzan la medianoche.", style = MaterialTheme.typography.bodySmall)
            Text("Solo con el perfil:", Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth()) {
                FilterChip(profile == "", { profile = "" }, { Text("Cualquiera") }, Modifier.padding(end = 6.dp))
                app.store.profiles.forEach { p -> FilterChip(profile == p, { profile = p }, { Text(p) }, Modifier.padding(end = 6.dp)) }
            }
            Field("Anti-spam: segundos mínimos entre ejecuciones", cooldown, { cooldown = it }, number = true)

            SectionTitle("Hacer (en la app)")
            check("Notificación en el móvil", aNotify) { aNotify = it }
            check("Tomar foto", aSnap) { aSnap = it }
            check("Mover PTZ a preset", aPtz) { aPtz = it }
            if (aPtz) Field("Nº de preset", ptzN, { ptzN = it }, number = true)
            check("Llamar a una URL (GET)", aHttp) { aHttp = it }
            if (aHttp) {
                Field("URL", url, { url = it })
                Text("Variables: {camera} {event} {status} {time} {channel}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Grabar, email, FTP, sirena... se configuran en la propia cámara (Ajustes > Alarma: qué hacer).",
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)

            Button(modifier = Modifier.padding(top = 16.dp), onClick = {
                val f = parseMin(from); val t = parseMin(to)
                val actions = buildList {
                    if (aSnap) add(Action(ActionType.SNAPSHOT))
                    if (aPtz) add(Action(ActionType.PTZ_PRESET, ptzN))
                    if (aHttp && url.isNotBlank()) add(Action(ActionType.HTTP_GET, url.trim()))
                    if (aNotify) add(Action(ActionType.NOTIFY))
                }
                val rule = Rule(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    name = name.ifBlank { "Regla" }, enabled = existing?.enabled ?: true,
                    cameraId = camId, events = events.toSet(), onlyStart = onlyStart,
                    fromMin = if (f >= 0 && t >= 0) f else -1, toMin = if (f >= 0 && t >= 0) t else -1,
                    profile = profile, cooldownSec = cooldown.toIntOrNull() ?: 30, actions = actions
                )
                val l = app.rules.value
                app.saveRules(if (existing == null) l + rule else l.map { if (it.id == rule.id) rule else it })
                back()
            }) { Text("Guardar regla") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
