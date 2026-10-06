@file:OptIn(ExperimentalMaterial3Api::class)

package com.xmcam.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xmcam.App

/**
 * Ajustes de la propia app (no de una cámara).
 * Secciones: Temas, Notificaciones e idioma, Vídeo, Almacenamiento y Acerca de.
 */
@Composable
fun AppSettingsScreen(back: () -> Unit) {
    val app = App.instance
    val ctx = LocalContext.current
    val selected by app.themeId.collectAsState()
    val follow by app.followSystem.collectAsState()
    var defaultStream by remember { mutableIntStateOf(app.store.defaultStream) }
    var keepOn by remember { mutableStateOf(app.store.keepScreenOn) }
    var cacheBytes by remember { mutableLongStateOf(cacheSize(ctx.cacheDir) + cacheSize(java.io.File(ctx.filesDir, "thumbs"))) }
    var confirmClear by remember { mutableStateOf(false) }
    var autoThumbs by remember { mutableStateOf(app.store.autoThumbs) }
    var autoSnap by remember { mutableStateOf(app.store.autoSnapOnAlarm) }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }

    Scaffold(topBar = { Bar("Ajustes de la app", back) }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState())) {

            SectionTitle("Temas")
            SettingRow(
                "Seguir el modo del sistema",
                "Cambia a claro u oscuro con el sistema. Si el tema elegido no encaja, usa NVR oscuro o Material You claro."
            ) { Switch(follow, { app.setFollowSystem(it) }) }
            Spacer(Modifier.height(6.dp))
            AppThemeId.entries.forEach { id ->
                ThemeCard(id, selected == id) { app.setTheme(id) }
                Spacer(Modifier.height(8.dp))
            }

            SectionTitle("Notificaciones e idioma")
            SettingRow("Notificaciones", "Elige cómo y cuándo te avisa la app de las alarmas.") {
                TextButton({
                    ctx.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    )
                }) { Text("Abrir") }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                SettingRow("Idioma de la app", "Cambia el idioma solo para esta app, sin tocar el del móvil.") {
                    TextButton({
                        ctx.startActivity(
                            Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                        )
                    }) { Text("Abrir") }
                }
            }

            SectionTitle("Vídeo")
            SettingRow("Calidad por defecto", "HD gasta más datos y batería; SD abre más rápido y es más fluido en WiFi flojo.") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(defaultStream == 0, { defaultStream = 0; app.store.defaultStream = 0 }, { Text("HD") })
                    FilterChip(defaultStream == 1, { defaultStream = 1; app.store.defaultStream = 1 }, { Text("SD") })
                }
            }
            SettingRow("Mantener pantalla encendida", "Evita que el móvil se apague mientras ves una cámara en directo.") {
                Switch(keepOn, { keepOn = it; app.store.keepScreenOn = it })
            }

            SectionTitle("Grabaciones")
            SettingRow("Foto automática en cada alarma", "Guarda una foto al saltar una alarma; se usa como miniatura en Grabaciones y Eventos.") {
                Switch(autoSnap, { autoSnap = it; app.store.autoSnapOnAlarm = it })
            }
            SettingRow("Generar miniaturas de los clips", "Al abrir Grabaciones, descarga el principio de cada clip sin foto para crear su miniatura. Usa la cámara unos segundos por clip.") {
                Switch(autoThumbs, { autoThumbs = it; app.store.autoThumbs = it })
            }

            SectionTitle("Almacenamiento")
            SettingRow("Borrar caché", "Elimina fotos temporales y miniaturas. Ocupa ${formatBytes(cacheBytes)}.") {
                TextButton({
                    ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                    java.io.File(ctx.filesDir, "thumbs").deleteRecursively()
                    cacheBytes = cacheSize(ctx.cacheDir) + cacheSize(java.io.File(ctx.filesDir, "thumbs"))
                }) { Text("Borrar") }
            }
            SettingRow("Borrar historial de eventos", "Elimina la lista de alarmas guardadas en la app. No toca las grabaciones de la cámara.") {
                TextButton({ confirmClear = true }) { Text("Borrar") }
            }

            SectionTitle("Acerca de")
            SettingRow("XMCam", "Versión $version. Control local de cámaras iCSee/XMEye, sin nube.")
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Borrar historial") },
        text = { Text("¿Borrar todos los eventos guardados en la app?") },
        confirmButton = { TextButton({ app.clearEvents(); confirmClear = false }) { Text("Borrar") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancelar") } }
    )
}

/**
 * Fila de ajuste reutilizable: título, descripción y un control opcional a la derecha.
 *
 * @param title       nombre del ajuste.
 * @param description explicación corta de qué hace.
 * @param trailing    control (Switch, botón, chips...) mostrado a la derecha; opcional.
 */
@Composable
fun SettingRow(title: String, description: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

/**
 * Tarjeta de selección de tema con vista previa de color (fondo, tarjeta y acento).
 *
 * @param id       tema que representa la tarjeta.
 * @param selected true si es el tema activo (se resalta con borde).
 * @param onClick  se llama al tocar la tarjeta.
 */
@Composable
fun ThemeCard(id: AppThemeId, selected: Boolean, onClick: () -> Unit) {
    val cs = id.colorScheme()
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                listOf(cs.background, cs.surfaceVariant, cs.primary).forEach { c ->
                    Box(Modifier.size(28.dp).background(c, CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(id.label, style = MaterialTheme.typography.titleMedium)
                Text(id.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected, onClick)
        }
    }
}

/** Suma el tamaño en bytes de todos los archivos bajo [dir]. */
private fun cacheSize(dir: java.io.File): Long =
    dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

/** Formatea [b] bytes como "x KB" o "x,y MB" para mostrarlo al usuario. */
private fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    else -> "%.1f MB".format(b / 1048576.0)
}
