@file:OptIn(ExperimentalMaterial3Api::class)

package com.xmcam.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Pantallas de la app. La navegación es una pila simple (ver MainActivity). */
sealed interface Screen {
    data object Home : Screen
    data object Add : Screen
    data object Events : Screen
    data object Rules : Screen
    data class Live(val camId: String) : Screen
    data class Playback(val camId: String) : Screen
    data class Settings(val camId: String) : Screen
    /** Editor genérico de un bloque de configuración; [filter] es una regex sobre la ruta de cada ajuste. */
    data class Config(val camId: String, val name: String, val filter: String?) : Screen
    data class RuleEdit(val ruleId: String?) : Screen
}

@Composable
fun XmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
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
