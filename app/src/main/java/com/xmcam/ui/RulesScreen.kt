@file:OptIn(ExperimentalMaterial3Api::class)

package com.xmcam.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xmcam.App
import com.xmcam.data.ActionType
import com.xmcam.protocol.XmEvents

/** Lista de reglas ("cuando pase X, haz Y") y gestión de perfiles. */
@Composable
fun RulesScreen(nav: (Screen) -> Unit, back: () -> Unit) {
    val app = App.instance
    val rules by app.rules.collectAsState()
    val cams by app.cameras.collectAsState()
    var editProfiles by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { Bar("Reglas de alarma", back) { TextButton({ editProfiles = true }) { Text("Perfiles") } } },
        floatingActionButton = { FloatingActionButton({ nav(Screen.RuleEdit(null)) }) { Text("+") } }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rules.isEmpty()) item { Text("No hay reglas. Pulsa + para crear una.", Modifier.padding(24.dp)) }
            items(rules, key = { it.id }) { r ->
                Card(Modifier.fillMaxWidth().clickable { nav(Screen.RuleEdit(r.id)) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.titleMedium)
                            val camName = cams.firstOrNull { it.id == r.cameraId }?.name ?: "Todas las cámaras"
                            val ev = if ("*" in r.events) "Cualquier evento" else r.events.joinToString { XmEvents.label(it) }
                            Text("$camName · $ev", style = MaterialTheme.typography.bodySmall)
                            Text(r.actions.joinToString { actionLabel(it.type) }, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(r.enabled, { on -> app.saveRules(rules.map { if (it.id == r.id) it.copy(enabled = on) else it }) })
                    }
                }
            }
        }
    }

    if (editProfiles) {
        var text by remember { mutableStateOf(app.store.profiles.joinToString(", ")) }
        AlertDialog(
            onDismissRequest = { editProfiles = false },
            title = { Text("Perfiles") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("Separados por comas") }) },
            confirmButton = {
                TextButton({
                    val l = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    if (l.isNotEmpty()) { app.store.profiles = l; if (app.profile.value !in l) app.setProfile(l.first()) }
                    editProfiles = false
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton({ editProfiles = false }) { Text("Cancelar") } }
        )
    }
}

fun actionLabel(type: String) = when (type) {
    ActionType.NOTIFY -> "Notificar"
    ActionType.SNAPSHOT -> "Foto"
    ActionType.HTTP_GET -> "Llamar URL"
    ActionType.PTZ_PRESET -> "Mover PTZ"
    else -> type
}
