package com.xmcam.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember

/** Única actividad: contiene toda la navegación (pila de [Screen]). */
class MainActivity : ComponentActivity() {
    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { XmTheme { Root() } }
    }
}

@Composable
private fun Root() {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    // Home vuelve a la raíz de la pila; Galería sobre Galería sustituye (cambio de filtro) en vez de apilar.
    val nav: (Screen) -> Unit = { s ->
        when {
            s == Screen.Home -> while (stack.size > 1) stack.removeAt(stack.lastIndex)
            s is Screen.Gallery && stack.last() is Screen.Gallery -> stack[stack.lastIndex] = s
            else -> stack.add(s)
        }
    }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(stack.size > 1) { back() }

    when (val s = stack.last()) {
        is Screen.Home -> HomeScreen(nav)
        is Screen.Add -> AddScreen(back)
        is Screen.Events -> EventsScreen(back)
        is Screen.Rules -> RulesScreen(nav, back)
        is Screen.AppSettings -> AppSettingsScreen(back)
        // key(camId): al cambiar de cámara desde el carrusel se recrea el estado (reproductor, sesión, PTZ).
        is Screen.Live -> key(s.camId) {
            LiveScreen(s.camId, nav, back, onSwitch = { id -> stack[stack.lastIndex] = Screen.Live(id) })
        }
        is Screen.Gallery -> GalleryScreen(s.camId, nav, back)
        is Screen.Playback -> PlaybackScreen(s.camId, nav, back)
        is Screen.Clip -> ClipPlayerScreen(s.camId, s.fileName, s.begin, s.end, back)
        is Screen.Settings -> SettingsScreen(s.camId, nav, back)
        is Screen.Config -> ConfigScreen(s.camId, s.name, s.filter, back)
        is Screen.RuleEdit -> RuleEditScreen(s.ruleId, back)
    }
}
