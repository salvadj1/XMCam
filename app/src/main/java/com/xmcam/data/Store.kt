package com.xmcam.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import java.io.File

/**
 * Persistencia ligera. Cámaras, reglas y ajustes van en SharedPreferences CIFRADAS
 * (las contraseñas no quedan en claro). El historial de eventos va en un JSON aparte.
 */
class Store(ctx: Context) {

    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "xmcam_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Si el Keystore falla en el dispositivo, usamos prefs privadas normales.
        ctx.getSharedPreferences("xmcam_plain", Context.MODE_PRIVATE)
    }

    fun cameras(): List<Camera> = readArray("cameras").map { Camera.fromJson(it) }
    fun saveCameras(l: List<Camera>) = writeArray("cameras", l.map { it.toJson() })

    fun rules(): List<Rule> = readArray("rules").map { Rule.fromJson(it) }
    fun saveRules(l: List<Rule>) = writeArray("rules", l.map { it.toJson() })

    /** Perfiles de alarma (Casa, Fuera, Noche...). Las reglas pueden limitarse a un perfil. */
    var profiles: List<String>
        get() = prefs.getString("profiles", "Casa|Fuera|Noche")!!.split("|").filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("profiles", v.joinToString("|")).apply()

    var activeProfile: String
        get() = prefs.getString("activeProfile", "Casa")!!
        set(v) = prefs.edit().putString("activeProfile", v).apply()

    /** Si la vigilancia en segundo plano está activada. */
    var monitorEnabled: Boolean
        get() = prefs.getBoolean("monitor", false)
        set(v) = prefs.edit().putBoolean("monitor", v).apply()

    /** Nombre del tema elegido (valor de AppThemeId). */
    var themeId: String
        get() = prefs.getString("theme", "NVR_DARK")!!
        set(v) = prefs.edit().putString("theme", v).apply()

    /** Si el tema debe seguir el modo claro/oscuro del sistema. */
    var followSystem: Boolean
        get() = prefs.getBoolean("followSystem", true)
        set(v) = prefs.edit().putBoolean("followSystem", v).apply()

    /** Calidad de vídeo por defecto en directo: 0 = HD, 1 = SD. */
    var defaultStream: Int
        get() = prefs.getInt("defaultStream", 1)
        set(v) = prefs.edit().putInt("defaultStream", v).apply()

    /** Mantener la pantalla encendida mientras se ve el directo. */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keepScreenOn", true)
        set(v) = prefs.edit().putBoolean("keepScreenOn", v).apply()

    /** Distribución del mosaico de la pantalla principal (posición en MosaicLayout; 0 = cuadrícula 2x2). */
    var mosaicLayout: Int
        get() = prefs.getInt("mosaicLayout", 0)
        set(v) = prefs.edit().putInt("mosaicLayout", v).apply()

    /** Si se guarda una foto automática en cada alarma (sirve de miniatura en Grabaciones y Eventos). */
    var autoSnapOnAlarm: Boolean
        get() = prefs.getBoolean("autoSnap", true)
        set(v) = prefs.edit().putBoolean("autoSnap", v).apply()

    /** Si al abrir Grabaciones se generan miniaturas descargando el principio de cada clip sin foto. */
    var autoThumbs: Boolean
        get() = prefs.getBoolean("autoThumbs", true)
        set(v) = prefs.edit().putBoolean("autoThumbs", v).apply()

    fun loadEvents(dir: File): List<EventRec> = try {
        val arr = JSONArray(File(dir, "events.json").readText())
        (0 until arr.length()).map { EventRec.fromJson(arr.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    fun saveEvents(dir: File, l: List<EventRec>) {
        runCatching { File(dir, "events.json").writeText(JSONArray(l.map { it.toJson() }).toString()) }
    }

    private fun readArray(key: String): List<org.json.JSONObject> = try {
        val arr = JSONArray(prefs.getString(key, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }
    } catch (e: Exception) { emptyList() }

    private fun writeArray(key: String, l: List<org.json.JSONObject>) =
        prefs.edit().putString(key, JSONArray(l).toString()).apply()
}
