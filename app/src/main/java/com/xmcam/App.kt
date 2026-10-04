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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

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

    override fun onCreate() {
        super.onCreate()
        instance = this
        Net.bindToWifi(this)
        store = Store(this)
        cameras.value = store.cameras()
        rules.value = store.rules()
        profile.value = store.activeProfile
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

    fun setProfile(p: String) { store.activeProfile = p; profile.value = p }

    fun setOnline(id: String, on: Boolean) = online.update { if (on) it + id else it - id }

    @Synchronized
    fun addEvent(e: EventRec) {
        events.value = (listOf(e) + events.value).take(300)
        store.saveEvents(filesDir, events.value)
    }

    fun clearEvents() { events.value = emptyList(); store.saveEvents(filesDir, emptyList()) }

    companion object {
        lateinit var instance: App
        const val CH_ALARM = "alarms"
        const val CH_SERVICE = "service"
    }
}
