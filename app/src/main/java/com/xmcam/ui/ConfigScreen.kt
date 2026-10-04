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
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Un ajuste "hoja" (booleano, número o texto) dentro de un bloque de configuración. */
private class Leaf(val path: List<Any>, val orig: Any) {
    var value by mutableStateOf(orig)
}

/** Recorre el JSON y recoge todos los valores simples con su ruta (claves y/o índices). */
private fun flatten(v: Any?, path: List<Any>, out: MutableList<Leaf>) {
    when (v) {
        is JSONObject -> v.keys().forEach { k -> flatten(v.opt(k), path + k, out) }
        is JSONArray -> for (i in 0 until v.length()) flatten(v.opt(i), path + i, out)
        is Boolean, is Number, is String -> out.add(Leaf(path, v))
        else -> {}
    }
}

/** Escribe [value] en la ruta [path] del JSON. */
private fun setAt(root: Any, path: List<Any>, value: Any) {
    var cur: Any = root
    for (p in path.dropLast(1)) cur = if (cur is JSONObject) cur.get(p as String) else (cur as JSONArray).get(p as Int)
    val last = path.last()
    if (cur is JSONObject) cur.put(last as String, value) else (cur as JSONArray).put(last as Int, value)
}

/** Convierte el texto escrito al mismo tipo numérico que tenía el valor original. */
private fun parseLike(orig: Any, t: String): Any? = when (orig) {
    is Int -> t.toIntOrNull()
    is Long -> t.toLongOrNull()
    is Double -> t.toDoubleOrNull()
    else -> t
}

/**
 * Editor genérico: lee un bloque de configuración de la cámara, muestra TODOS sus ajustes
 * (interruptor para booleanos, campo para números y textos) y escribe el bloque completo al guardar.
 * Así se puede configurar cualquier cosa que la cámara exponga, sin programar cada pantalla.
 */
@Composable
fun ConfigScreen(camId: String, name: String, filter: String?, back: () -> Unit) {
    val cam = App.instance.cameras.value.first { it.id == camId }
    val scope = rememberCoroutineScope()
    val session = remember { ControlSession(cam) }
    DisposableEffect(Unit) { onDispose { session.close() } }

    var root by remember { mutableStateOf<Any?>(null) }
    val leaves = remember { mutableStateListOf<Leaf>() }
    var msg by remember { mutableStateOf("Cargando…") }

    LaunchedEffect(Unit) {
        runCatching { session.exec { it.getConfig(name) } }
            .onSuccess { r ->
                root = r
                val all = mutableListOf<Leaf>()
                flatten(r, emptyList(), all)
                val re = filter?.let { Regex(it) }
                leaves.clear()
                leaves.addAll(all.filter { re == null || re.containsMatchIn(it.path.joinToString(".")) })
                msg = if (leaves.isEmpty()) "Sin ajustes en este bloque (¿no lo soporta la cámara?)" else "${leaves.size} ajustes"
            }
            .onFailure { msg = "Error: ${it.message}" }
    }

    Scaffold(
        topBar = { Bar(name, back) },
        bottomBar = {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Button(enabled = root != null, onClick = {
                    scope.launch {
                        runCatching {
                            val r = root!!
                            leaves.forEach { if (it.value != it.orig) setAt(r, it.path, it.value) }
                            session.exec { c -> c.setConfig(name, r) }
                        }.onSuccess { msg = "Guardado" }.onFailure { msg = "Error: ${it.message}" }
                    }
                }) { Text("Guardar") }
            }
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            items(leaves) { l ->
                val label = l.path.joinToString(".")
                if (l.orig is Boolean) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(l.value as Boolean, { l.value = it })
                    }
                } else {
                    var t by remember(l) { mutableStateOf(l.value.toString()) }
                    OutlinedTextField(
                        value = t, label = { Text(label) }, singleLine = true,
                        onValueChange = { s -> t = s; parseLike(l.orig, s)?.let { l.value = it } },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    )
                }
            }
        }
    }
}
