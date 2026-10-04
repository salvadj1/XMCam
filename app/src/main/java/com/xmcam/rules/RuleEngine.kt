package com.xmcam.rules

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.BitmapFactory
import com.xmcam.App
import com.xmcam.data.ActionType
import com.xmcam.data.Camera
import com.xmcam.data.EventRec
import com.xmcam.data.Net
import com.xmcam.data.Rule
import com.xmcam.protocol.AlarmEvent
import com.xmcam.protocol.DvripClient
import com.xmcam.protocol.PtzCmd
import com.xmcam.protocol.XmEvents
import com.xmcam.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * Motor de reglas. Recibe cada alarma, busca las reglas que encajan (cámara, evento,
 * inicio/fin, horario, perfil, anti-spam) y ejecuta sus acciones en este orden fijo:
 * SNAPSHOT -> PTZ_PRESET -> HTTP_GET -> NOTIFY.
 * Todos los eventos "Start" se guardan en el historial, haya o no reglas.
 */
object RuleEngine {
    private val lastRun = ConcurrentHashMap<String, Long>()

    /** ¿La regla se aplica a este evento ahora mismo? (sin tener en cuenta el anti-spam) */
    fun matches(r: Rule, cam: Camera, ev: AlarmEvent, profile: String, cal: Calendar = Calendar.getInstance()): Boolean {
        if (!r.enabled) return false
        if (r.cameraId.isNotEmpty() && r.cameraId != cam.id) return false
        if ("*" !in r.events && ev.event !in r.events) return false
        if (r.onlyStart && ev.status != "Start") return false
        if (r.profile.isNotEmpty() && r.profile != profile) return false
        if (r.fromMin >= 0 && r.toMin >= 0) {
            val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            // Si la franja cruza la medianoche (22:00-07:00) se usa "o" en lugar de "y".
            val inside = if (r.fromMin <= r.toMin) m in r.fromMin until r.toMin else (m >= r.fromMin || m < r.toMin)
            if (!inside) return false
        }
        return true
    }

    /** Procesa una alarma de [cam]. Llamar desde cualquier corrutina. */
    suspend fun handle(cam: Camera, ev: AlarmEvent) = withContext(Dispatchers.IO) {
        val app = App.instance
        val now = System.currentTimeMillis()

        val active = app.rules.value
            .filter { matches(it, cam, ev, app.profile.value) }
            .filter { r ->
                val key = "${r.id}|${cam.id}"
                val ok = now - (lastRun[key] ?: 0L) >= r.cooldownSec * 1000L
                if (ok) lastRun[key] = now
                ok
            }
        val actions = active.flatMap { it.actions }

        var snap: String? = null
        if (actions.any { it.type == ActionType.SNAPSHOT }) {
            snap = Net.snapshot(cam, File(app.filesDir, "snaps"))?.absolutePath
        }
        actions.filter { it.type == ActionType.PTZ_PRESET }.forEach { a ->
            runCatching {
                DvripClient(cam.host, cam.port).use { c ->
                    c.login(cam.user, cam.password)
                    c.ptz(PtzCmd.GOTO_PRESET, cam.channel, preset = a.param.toIntOrNull() ?: 1)
                }
            }
        }
        actions.filter { it.type == ActionType.HTTP_GET }.forEach { a ->
            runCatching { Net.httpGet(expand(a.param, cam, ev)) }
        }
        if (actions.any { it.type == ActionType.NOTIFY }) notify(cam, ev, snap)

        if (ev.status == "Start") {
            app.addEvent(EventRec(now, cam.id, cam.name, ev.event, ev.status, snap))
        }
        Unit
    }

    /** Sustituye {camera} {event} {status} {time} {channel} en la URL (con URL-encoding). */
    private fun expand(url: String, cam: Camera, ev: AlarmEvent): String {
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        return url.replace("{camera}", e(cam.name)).replace("{event}", e(ev.event))
            .replace("{status}", e(ev.status)).replace("{time}", e(ev.time))
            .replace("{channel}", ev.channel.toString())
    }

    private fun notify(cam: Camera, ev: AlarmEvent, snapPath: String?) {
        val app = App.instance
        val pi = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(app, App.CH_ALARM)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("${cam.name}: ${XmEvents.label(ev.event)}")
            .setContentText(ev.time)
            .setContentIntent(pi)
            .setAutoCancel(true)
        if (snapPath != null) {
            BitmapFactory.decodeFile(snapPath)?.let { b.setStyle(Notification.BigPictureStyle().bigPicture(it)) }
        }
        app.getSystemService(NotificationManager::class.java).notify((System.nanoTime() and 0x7FFFFFFF).toInt(), b.build())
    }
}
