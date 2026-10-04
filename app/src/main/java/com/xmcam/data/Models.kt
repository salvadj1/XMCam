package com.xmcam.data

import com.xmcam.protocol.XmCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

/** Cámara guardada por el usuario. */
data class Camera(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 34567,
    val user: String = "admin",
    val password: String = "",
    val rtspPort: Int = 554,
    val httpPort: Int = 80,
    val channel: Int = 0,
    /** Si true, el servicio en segundo plano escucha las alarmas de esta cámara. */
    val monitor: Boolean = true,
    /** Algunos firmwares exigen el hash XM en la URL RTSP en lugar de la contraseña. */
    val rtspHash: Boolean = false
) {
    /** URL RTSP. stream: 0 = principal, 1 = secundario. */
    fun rtspUrl(stream: Int): String {
        val pw = if (rtspHash) XmCrypto.hashPassword(password) else password
        return "rtsp://$host:$rtspPort/user=$user&password=$pw&channel=${channel + 1}&stream=$stream.sdp?real_stream"
    }

    /**
     * Construye la URL RTSP de reproducción de un clip a partir de [template].
     * Variables: {host} {port} (RTSP) {user} {pass} {channel} (empieza en 1) {start} {end}.
     * [begin] y [end] llegan como "yyyy-MM-dd HH:mm:ss" y se sustituyen como "yyyy_MM_dd_HH_mm_ss".
     */
    fun playbackUrl(template: String, begin: String, end: String): String {
        val pw = if (rtspHash) XmCrypto.hashPassword(password) else password
        fun t(s: String) = s.replace("-", "_").replace(":", "_").replace(" ", "_")
        return template.replace("{host}", host).replace("{port}", rtspPort.toString())
            .replace("{user}", user).replace("{pass}", pw).replace("{channel}", (channel + 1).toString())
            .replace("{start}", t(begin)).replace("{end}", t(end))
    }

    /** URL de captura JPEG por HTTP. */
    fun snapshotUrl(): String {
        val u = URLEncoder.encode(user, "UTF-8")
        val p = URLEncoder.encode(XmCrypto.hashPassword(password), "UTF-8")
        return "http://$host:$httpPort/webcapture.jpg?command=snap&channel=${channel + 1}&user=$u&password=$p"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("host", host).put("port", port)
        .put("user", user).put("password", password).put("rtspPort", rtspPort)
        .put("httpPort", httpPort).put("channel", channel)
        .put("monitor", monitor).put("rtspHash", rtspHash)

    companion object {
        fun fromJson(o: JSONObject) = Camera(
            id = o.optString("id"), name = o.optString("name"), host = o.optString("host"),
            port = o.optInt("port", 34567), user = o.optString("user", "admin"),
            password = o.optString("password"), rtspPort = o.optInt("rtspPort", 554),
            httpPort = o.optInt("httpPort", 80), channel = o.optInt("channel", 0),
            monitor = o.optBoolean("monitor", true), rtspHash = o.optBoolean("rtspHash", false)
        )
    }
}

/** Tipos de acción que puede ejecutar una regla en la app. */
object ActionType {
    const val NOTIFY = "NOTIFY"          // notificación en el móvil
    const val SNAPSHOT = "SNAPSHOT"      // foto de la cámara (se adjunta a notificación/historial)
    const val HTTP_GET = "HTTP_GET"      // llamar a una URL (param = URL con {camera},{event},{time}...)
    const val PTZ_PRESET = "PTZ_PRESET"  // mover la cámara a un preset (param = nº)
}

data class Action(val type: String, val param: String = "")

/**
 * Regla: "cuando ocurra [events] en [cameraId] bajo estas condiciones, ejecuta [actions]".
 * Campos vacíos = sin restricción (cameraId "" = todas, profile "" = cualquiera, fromMin < 0 = siempre).
 */
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    val cameraId: String = "",
    val events: Set<String> = setOf("*"),
    val onlyStart: Boolean = true,
    val fromMin: Int = -1,
    val toMin: Int = -1,
    val profile: String = "",
    val cooldownSec: Int = 30,
    val actions: List<Action> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("enabled", enabled).put("cameraId", cameraId)
        .put("events", JSONArray(events.toList())).put("onlyStart", onlyStart)
        .put("fromMin", fromMin).put("toMin", toMin).put("profile", profile)
        .put("cooldownSec", cooldownSec)
        .put("actions", JSONArray(actions.map { JSONObject().put("type", it.type).put("param", it.param) }))

    companion object {
        fun fromJson(o: JSONObject): Rule {
            val ev = o.optJSONArray("events")
            val ac = o.optJSONArray("actions")
            return Rule(
                id = o.optString("id"), name = o.optString("name"), enabled = o.optBoolean("enabled", true),
                cameraId = o.optString("cameraId"),
                events = if (ev == null) setOf("*") else (0 until ev.length()).map { ev.getString(it) }.toSet(),
                onlyStart = o.optBoolean("onlyStart", true), fromMin = o.optInt("fromMin", -1),
                toMin = o.optInt("toMin", -1), profile = o.optString("profile"),
                cooldownSec = o.optInt("cooldownSec", 30),
                actions = if (ac == null) emptyList() else (0 until ac.length()).map {
                    val a = ac.getJSONObject(it); Action(a.optString("type"), a.optString("param"))
                }
            )
        }
    }
}

/** Entrada del historial de eventos. */
data class EventRec(
    val time: Long, val cameraId: String, val cameraName: String,
    val event: String, val status: String, val snapshot: String?
) {
    fun toJson(): JSONObject = JSONObject()
        .put("time", time).put("cameraId", cameraId).put("cameraName", cameraName)
        .put("event", event).put("status", status).put("snapshot", snapshot ?: "")

    companion object {
        fun fromJson(o: JSONObject) = EventRec(
            o.optLong("time"), o.optString("cameraId"), o.optString("cameraName"),
            o.optString("event"), o.optString("status"), o.optString("snapshot").ifEmpty { null }
        )
    }
}
