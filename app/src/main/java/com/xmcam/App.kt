package com.xmcam

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.xmcam.data.Camera
import com.xmcam.data.EventRec
import com.xmcam.data.Net
import com.xmcam.data.Rule
import com.xmcam.data.Store
import com.xmcam.service.AlarmService
import com.xmcam.ui.AppThemeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Estado global de la app (cámaras, reglas, eventos, estado online) expuesto como StateFlow. */
class App : Application() {
    lateinit var store: Store
    val cameras = MutableStateFlow<List<Camera>>(emptyList())
    val rules = MutableStateFlow<List<Rule>>(emptyList())
    val events = MutableStateFlow<List<EventRec>>(emptyList())
    val online = MutableStateFlow<Set<String>>(emptySet())
    /** Resultado de la última comprobación de alcance: id -> accesible (ausente = comprobando). */
    val reach = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val profile = MutableStateFlow("")
    /** Posición de la lista de grabaciones por cámara, para volver al mismo sitio tras reproducir un clip. */
    val playbackPos = mutableMapOf<String, PlaybackPos>()
    /** Última lista de clips cargada por cámara y día (clave "camId|día"): evita parpadeos al volver. */
    val playbackCache = mutableMapOf<String, List<JSONObject>>()
    val themeId = MutableStateFlow(AppThemeId.NVR_DARK)
    val followSystem = MutableStateFlow(true)

    override fun onCreate() {
        super.onCreate()
        instance = this
        Net.bindToWifi(this)
        store = Store(this)
        cameras.value = store.cameras()
        rules.value = store.rules()
        profile.value = store.activeProfile
        themeId.value = runCatching { AppThemeId.valueOf(store.themeId) }.getOrDefault(AppThemeId.NVR_DARK)
        followSystem.value = store.followSystem
        events.value = store.loadEvents(filesDir)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ALARM, "Alarmas", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Vigilancia", NotificationManager.IMPORTANCE_LOW))
    }

    fun saveCameras(l: List<Camera>) {
        store.saveCameras(l); cameras.value = l
        if (store.monitorEnabled) AlarmService.start(this) // reinicia la vigilancia con la nueva lista
    }

    fun saveRules(l: List<Rule>) { store.saveRules(l); rules.value = l }

    fun setTheme(t: AppThemeId) { store.themeId = t.name; themeId.value = t }

    fun setFollowSystem(v: Boolean) { store.followSystem = v; followSystem.value = v }

    fun setProfile(p: String) { store.activeProfile = p; profile.value = p }

    fun setOnline(id: String, on: Boolean) = online.update { if (on) it + id else it - id }

    @Synchronized
    fun addEvent(e: EventRec) {
        val all = listOf(e) + events.value
        events.value = all.take(300)
        // Borra del disco las fotos de los eventos que ya no caben en el historial.
        all.drop(300).forEach { old -> old.snapshot?.let { runCatching { File(it).delete() } } }
        store.saveEvents(filesDir, events.value)
    }

    fun clearEvents() {
        events.value.forEach { old -> old.snapshot?.let { runCatching { File(it).delete() } } }
        events.value = emptyList(); store.saveEvents(filesDir, emptyList())
    }

    /** Archivo de miniatura capturada de un clip ([begin] = "yyyy-MM-dd HH:mm:ss"). Crea la carpeta si no existe. */
    fun thumbFile(camId: String, begin: String): File =
        File(File(filesDir, "thumbs").apply { mkdirs() }, "${camId.take(8)}_${begin.filter { it.isDigit() }}.jpg")

    /** MP4 convertido de un clip en la caché (se borra con "Borrar caché"). */
    fun clipFile(camId: String, begin: String): File =
        File(File(cacheDir, "clips").apply { mkdirs() }, "${camId.take(8)}_${begin.filter { it.isDigit() }}_v2.mp4")

    companion object {
        lateinit var instance: App
        const val CH_ALARM = "alarms"
        const val CH_SERVICE = "service"
    }
}

/** Día seleccionado y posición de scroll (índice y desplazamiento) de la lista de grabaciones. */
data class PlaybackPos(val day: LocalDate, val index: Int, val offset: Int)
