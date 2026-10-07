@file:OptIn(ExperimentalMaterial3Api::class)

package com.xmcam.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.xmcam.App
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Pantallas de la app. La navegación es una pila simple (ver MainActivity). */
sealed interface Screen {
    data object Home : Screen
    data object Add : Screen
    data object Events : Screen
    data object Rules : Screen
    data object AppSettings : Screen
    data class Live(val camId: String) : Screen
    data class Playback(val camId: String) : Screen
    /** Galería unificada (vista previa + línea de tiempo + listado); [camId] preselecciona una cámara, null = todas. */
    data class Gallery(val camId: String? = null) : Screen
    /** Reproductor de un clip de la SD; [begin] y [end] en formato "yyyy-MM-dd HH:mm:ss". */
    data class Clip(val camId: String, val fileName: String, val begin: String, val end: String) : Screen
    data class Settings(val camId: String) : Screen
    /** Editor genérico de un bloque de configuración; [filter] es una regex sobre la ruta de cada ajuste. */
    data class Config(val camId: String, val name: String, val filter: String?) : Screen
    data class RuleEdit(val ruleId: String?) : Screen
}

@Composable
fun XmTheme(content: @Composable () -> Unit) {
    val app = App.instance
    val selected by app.themeId.collectAsState()
    val follow by app.followSystem.collectAsState()
    val active = resolveTheme(selected, follow, isSystemInDarkTheme())
    MaterialTheme(colorScheme = active.colorScheme()) {
        Surface(Modifier.fillMaxSize()) { content() }
    }
}

/** Barra superior con botón "atrás" opcional y acciones. */
@Composable
fun Bar(title: String, back: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { if (back != null) TextButton(onClick = back) { Text("←") } },
        actions = actions
    )
}

/** Campo de texto estándar. */
@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, number: Boolean = false, password: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Number else if (password) KeyboardType.Password else KeyboardType.Text
        )
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
}

/** Pestañas de la barra inferior principal. */
enum class MainTab { MOSAIC, GALLERY, EVENTS, SETTINGS }

/**
 * Barra de navegación inferior común (Mosaico, Galería, Eventos, Ajustes).
 * Reutilizable en cualquier pantalla principal.
 *
 * @param selected pestaña activa (se resalta y no navega al pulsarla).
 * @param nav      función de navegación de la app.
 */
@Composable
fun MainNavBar(selected: MainTab, nav: (Screen) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected == MainTab.MOSAIC, { if (selected != MainTab.MOSAIC) nav(Screen.Home) },
            { Icon(Icons.Default.Home, null) }, label = { Text("Mosaico") }
        )
        NavigationBarItem(
            selected == MainTab.GALLERY, { if (selected != MainTab.GALLERY) nav(Screen.Gallery()) },
            { Icon(Icons.Default.DateRange, null) }, label = { Text("Galería") }
        )
        NavigationBarItem(
            selected == MainTab.EVENTS, { if (selected != MainTab.EVENTS) nav(Screen.Events) },
            { Icon(Icons.Default.Notifications, null) }, label = { Text("Eventos") }
        )
        NavigationBarItem(
            selected == MainTab.SETTINGS, { if (selected != MainTab.SETTINGS) nav(Screen.AppSettings) },
            { Icon(Icons.Default.Settings, null) }, label = { Text("Ajustes") }
        )
    }
}
