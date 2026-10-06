@file:OptIn(ExperimentalMaterial3Api::class)

package com.xmcam.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.xmcam.App
import com.xmcam.data.ControlSession
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

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

/** Valor normalizado para comparar antes/después (los números se comparan como decimales). */
private fun norm(v: Any): String = if (v is Number) v.toDouble().toString() else v.toString()

/** Convierte el texto escrito al mismo tipo numérico que tenía el valor original (null si no es válido). */
private fun parseLike(orig: Any, t: String): Any? = when (orig) {
    is Int -> t.toIntOrNull()
    is Long -> t.toLongOrNull()
    is Double -> t.toDoubleOrNull()
    else -> t
}

/** Interpreta como activado/desactivado un booleano, un número (≠0) o un hexadecimal "0x…". */
private fun toBool(v: Any?): Boolean = when (v) {
    is Boolean -> v
    is Number -> v.toDouble() != 0.0
    is String -> v.removePrefix("0x").toLongOrNull(16)?.let { it != 0L } ?: v.equals("true", true)
    else -> false
}

/** Escribe un interruptor con el mismo formato que tenía el valor original (booleano, 0/1 o "0x0000000N"). */
private fun boolLike(orig: Any?, on: Boolean): Any = when (orig) {
    is Boolean -> on
    is Number -> if (on) 1 else 0
    is String -> if (orig.startsWith("0x")) "0x%08X".format(if (on) 1 else 0) else on.toString()
    else -> on
}

/** Nombre legible de una clave técnica: usa el diccionario o separa las mayúsculas ("RecordEnable" → "Record Enable"). */
private fun friendly(last: Any): String {
    val k = last.toString()
    return FRIENDLY_NAMES[k] ?: k.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
}

/**
 * Pantalla de un bloque de configuración de la cámara.
 *
 * Si hay un esquema ([configSchema]) muestra formularios claros: título, explicación y el control adecuado
 * (interruptor, deslizador, opciones, número, texto). Solo aparecen los ajustes que la cámara devuelve.
 * El resto de valores del bloque quedan en "Avanzado" con su nombre técnico. Al guardar, la app vuelve a leer
 * la cámara y confirma si los cambios se aplicaron de verdad.
 *
 * @param camId  id de la cámara.
 * @param name   nombre técnico del bloque (p. ej. "Detect.MotionDetect").
 * @param filter expresión regular para limitar los ajustes mostrados en bloques SIN esquema; null = todos.
 * @param back   vuelve a la pantalla anterior.
 */
@Composable
fun ConfigScreen(camId: String, name: String, filter: String?, back: () -> Unit) {
    val cam = App.instance.cameras.value.first { it.id == camId }
    val scope = rememberCoroutineScope()
    val session = remember { ControlSession(cam) }
    DisposableEffect(Unit) { onDispose { session.close() } }
    val schema = remember(name) { configSchema(name) }

    var root by remember { mutableStateOf<Any?>(null) }
    var base by remember { mutableStateOf<JSONObject?>(null) }
    var prefix by remember { mutableStateOf<List<Any>>(emptyList()) }
    val advanced = remember { mutableStateListOf<Leaf>() }
    var snapshot by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var tick by remember { mutableIntStateOf(0) }
    var loadVersion by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf("Cargando…") }
    var busy by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(schema == null || schema.sections.isEmpty()) }

    LaunchedEffect(loadVersion) {
        msg = "Cargando…"
        runCatching { session.exec { it.getConfig(name) } }
            .onSuccess { r ->
                root = r
                var b: JSONObject? = null
                var pre: List<Any> = emptyList()
                when (r) {
                    is JSONObject -> b = r
                    is JSONArray -> {
                        val i = if (cam.channel in 0 until r.length()) cam.channel else 0
                        b = r.optJSONObject(i); pre = listOf(i)
                    }
                }
                base = b; prefix = pre
                val all = mutableListOf<Leaf>()
                flatten(r, emptyList(), all)
                snapshot = all.associate { it.path.joinToString(".") to norm(it.orig) }
                val covered = schema?.let { coveredPaths(it, pre) } ?: emptySet()
                val re = if (schema == null) filter?.let { Regex(it) } else null
                advanced.clear()
                advanced.addAll(all.filter { l ->
                    val p = l.path.joinToString(".")
                    p !in covered && (re == null || re.containsMatchIn(p))
                })
                tick++
                msg = if (all.isEmpty()) "La cámara no devolvió ajustes en este bloque (puede que no lo soporte)." else ""
            }
            .onFailure { msg = "Error: ${it.message}" }
    }

    // Título de cada ajuste del esquema por ruta completa (para nombrar los que la cámara rechace).
    val titles = remember(schema, prefix) {
        schema?.sections?.flatMap { it.fields }?.associate { (prefix + pathOf(it.key)).joinToString(".") to it.title } ?: emptyMap()
    }

    /** Guarda: aplica los cambios de Avanzado, escribe el bloque, lo vuelve a leer y compara. */
    fun save() {
        val r = root ?: return
        scope.launch {
            busy = true
            msg = "Guardando…"
            runCatching {
                advanced.forEach { if (it.value != it.orig) setAt(r, it.path, it.value) }
                val cur = mutableListOf<Leaf>(); flatten(r, emptyList(), cur)
                val now = cur.associate { it.path.joinToString(".") to norm(it.orig) }
                val changed = now.filter { (k, v) -> snapshot[k] != v }.keys
                if (changed.isEmpty()) return@runCatching "No hay cambios que guardar."
                session.exec { c -> c.setConfig(name, r) }
                val after = session.exec { c -> c.getConfig(name) }
                val aLeaves = mutableListOf<Leaf>(); flatten(after, emptyList(), aLeaves)
                val aMap = aLeaves.associate { it.path.joinToString(".") to norm(it.orig) }
                val rejected = changed.filter { aMap[it] != now[it] }
                if (rejected.isEmpty()) {
                    snapshot = now
                    "Aplicado ✓ La cámara ha confirmado ${changed.size} cambio(s)."
                } else {
                    "La cámara no aceptó: " + rejected.joinToString { titles[it] ?: it } + ". Pulsa Recargar para ver su valor real."
                }
            }.onSuccess { msg = it }.onFailure { msg = "Error: ${it.message}" }
            busy = false
        }
    }
    val risky = name == "NetWork.NetCommon" || name == "NetWork.Wifi"

    Scaffold(
        topBar = { Bar(schema?.title ?: name, back) },
        bottomBar = {
            Column(Modifier.padding(12.dp)) {
                if (msg.isNotEmpty()) Text(msg, Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({ loadVersion++ }, Modifier.weight(1f), enabled = !busy) { Text("Recargar") }
                    Button({ if (risky) confirmSave = true else save() }, Modifier.weight(1f), enabled = root != null && !busy) { Text("Guardar") }
                }
            }
        }
    ) { pad ->
        val b = base
        val visibleSections = if (schema != null && b != null)
            schema.sections.map { sec -> sec to sec.fields.filter { getPath(b, it.key) != null } }.filter { it.second.isNotEmpty() }
        else emptyList()

        LazyColumn(
            Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (schema != null) item {
                Card(Modifier.fillMaxWidth()) { Text(schema.intro, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
            }
            if (schema != null && root != null && schema.sections.isNotEmpty() && visibleSections.isEmpty()) item {
                Text(
                    "Esta cámara no expone las opciones habituales de esta sección. Mira «Avanzado» por si tiene otras.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            visibleSections.forEach { (sec, fields) ->
                item {
                    Column {
                        sec.title?.let { Text(it, Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.titleMedium) }
                        sec.note?.let { Text(it, Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodySmall) }
                        Card(Modifier.fillMaxWidth()) {
                            Column {
                                fields.forEachIndexed { i, f ->
                                    if (i > 0) HorizontalDivider()
                                    FieldRow(f, b!!, tick, loadVersion) { tick++ }
                                }
                            }
                        }
                    }
                }
            }
            if (advanced.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Avanzado (${advanced.size})", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Resto de valores del bloque con su nombre técnico. Cambia solo lo que entiendas.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(if (showAdvanced) "▲" else "▼")
                        }
                    }
                }
                if (showAdvanced) items(advanced.take(300)) { l -> AdvancedRow(l, loadVersion) }
                if (showAdvanced && advanced.size > 300) item {
                    Text("Se muestran los primeros 300 valores.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (confirmSave) AlertDialog(
        onDismissRequest = { confirmSave = false },
        title = { Text("¿Guardar cambios de red?") },
        text = { Text("Si algún dato es incorrecto, la cámara puede quedar sin conexión y tendrás que volver a encontrarla en la red.") },
        confirmButton = { TextButton({ confirmSave = false; save() }) { Text("Guardar") } },
        dismissButton = { TextButton({ confirmSave = false }) { Text("Cancelar") } }
    )
}

/** Texto de ayuda pequeño bajo el título de un ajuste. */
@Composable
private fun Help(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Fila de un ajuste con el control que corresponde a su [Field.kind]. Escribe directamente en [base].
 *
 * @param tick        contador que fuerza a releer el valor tras cada cambio.
 * @param loadVersion cambia al recargar; reinicia los campos de texto.
 * @param onChange    se llama tras escribir un valor.
 */
@Composable
private fun FieldRow(f: Field, base: JSONObject, tick: Int, loadVersion: Int, onChange: () -> Unit) {
    val cur = getPath(base, f.key)
    val path = remember(f.key) { pathOf(f.key) }
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        when (val k = f.kind) {
            Kind.Toggle -> Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(f.title, style = MaterialTheme.typography.bodyLarge)
                    Help(f.help)
                }
                Switch(toBool(cur), { setAt(base, path, boolLike(cur, it)); onChange() })
            }
            is Kind.Slider -> {
                val v = (cur as? Number)?.toInt() ?: cur.toString().toIntOrNull() ?: k.min
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(k.label(v), style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = v.toFloat().coerceIn(k.min.toFloat(), k.max.toFloat()),
                    onValueChange = { nv ->
                        val n = nv.roundToInt()
                        setAt(base, path, if (cur is String) n.toString() else n); onChange()
                    },
                    valueRange = k.min.toFloat()..k.max.toFloat(),
                    steps = (k.max - k.min - 1).coerceAtLeast(0)
                )
                Help(f.help)
            }
            is Kind.Choice -> {
                Text(f.title, style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                    k.options.forEach { (value, label) ->
                        FilterChip(
                            selected = cur?.toString() == value,
                            onClick = { setAt(base, path, if (cur is Number) (value.toIntOrNull() ?: value) else value); onChange() },
                            label = { Text(label) }, modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
                Help(f.help)
            }
            is Kind.Num -> {
                var t by remember(f.key, loadVersion) { mutableStateOf(cur?.toString() ?: "") }
                OutlinedTextField(
                    value = t, singleLine = true, label = { Text(f.title) },
                    suffix = { if (k.unit.isNotEmpty()) Text(k.unit) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    onValueChange = { s -> t = s; cur?.let { o -> parseLike(o, s) }?.let { setAt(base, path, it); onChange() } },
                    supportingText = { Text(f.help) }, modifier = Modifier.fillMaxWidth()
                )
            }
            Kind.Str, Kind.Pass -> {
                var t by remember(f.key, loadVersion) { mutableStateOf(cur?.toString() ?: "") }
                OutlinedTextField(
                    value = t, singleLine = true, label = { Text(f.title) },
                    visualTransformation = if (k == Kind.Pass) PasswordVisualTransformation() else VisualTransformation.None,
                    onValueChange = { s -> t = s; setAt(base, path, s); onChange() },
                    supportingText = { Text(f.help) }, modifier = Modifier.fillMaxWidth()
                )
            }
            Kind.ReadOnly -> Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(f.title, style = MaterialTheme.typography.bodyLarge)
                    Help(f.help)
                }
                Text(cur?.toString() ?: "—", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Fila de un valor de la lista "Avanzado": nombre legible, ruta técnica debajo e interruptor o campo. */
@Composable
private fun AdvancedRow(l: Leaf, loadVersion: Int) {
    val label = friendly(l.path.last())
    val raw = l.path.joinToString(".")
    if (l.orig is Boolean) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Help(raw)
            }
            Switch(l.value as Boolean, { l.value = it })
        }
    } else {
        var t by remember(l, loadVersion) { mutableStateOf(l.value.toString()) }
        OutlinedTextField(
            value = t, label = { Text(label) }, singleLine = true,
            onValueChange = { s -> t = s; parseLike(l.orig, s)?.let { l.value = it } },
            supportingText = { Text(raw) }, modifier = Modifier.fillMaxWidth()
        )
    }
}
