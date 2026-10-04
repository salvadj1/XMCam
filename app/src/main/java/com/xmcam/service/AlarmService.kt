package com.xmcam.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.xmcam.App
import com.xmcam.data.Camera
import com.xmcam.protocol.DvripClient
import com.xmcam.rules.RuleEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Servicio en primer plano que vigila las alarmas de todas las cámaras con "monitor" activado.
 *
 * Dos vías de entrada (según firmware):
 *  1. Una sesión XM abierta por cámara (puerto 34567) suscrita a alarmas (mensaje 1500 -> 1504).
 *  2. Un servidor TCP en el puerto 15002 que recibe las alarmas que la propia cámara envía
 *     si en su configuración se fija la IP del móvil como "servidor de alarmas".
 * Ambas llaman a [RuleEngine.handle].
 */
class AlarmService : Service() {
    private var scope: CoroutineScope? = null
    private var server: ServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notification.Builder(this, App.CH_SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("XMCam")
            .setContentText("Vigilando cámaras")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)

        stopWork()
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        App.instance.cameras.value.filter { it.monitor }.forEach { cam -> sc.launch { watch(cam) } }
        sc.launch { listenPushes() }
        return START_STICKY
    }

    override fun onDestroy() {
        stopWork()
        super.onDestroy()
    }

    private fun stopWork() {
        runCatching { server?.close() }
        server = null
        scope?.cancel()
        scope = null
        App.instance.online.value = emptySet()
    }

    /** Mantiene una sesión por cámara, reconectando con espera creciente (2 s ... 60 s). */
    private suspend fun watch(cam: Camera) {
        var backoff = 2000L
        while (currentCoroutineContext().isActive) {
            try {
                DvripClient(cam.host, cam.port).use { c ->
                    c.login(cam.user, cam.password)
                    c.subscribeAlarms()
                    c.setReadTimeout(5000)
                    App.instance.setOnline(cam.id, true)
                    backoff = 2000L
                    var lastKeep = System.currentTimeMillis()
                    val keepEvery = maxOf(5, c.aliveIntervalSec / 2) * 1000L
                    while (currentCoroutineContext().isActive) {
                        val p = try { c.readPacket() } catch (e: SocketTimeoutException) { null }
                        if (p != null && p.msgId == 1504) {
                            val ev = p.json()?.let { DvripClient.parseAlarm(it) }
                            if (ev != null) RuleEngine.handle(cam, ev)
                        }
                        if (System.currentTimeMillis() - lastKeep >= keepEvery) { c.keepAlive(); lastKeep = System.currentTimeMillis() }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                App.instance.setOnline(cam.id, false)
                delay(backoff)
                backoff = minOf(backoff * 2, 60_000L)
            }
        }
    }

    /** Servidor de alarmas (puerto 15002): la cámara abre la conexión y envía cabecera XM + JSON. */
    private suspend fun listenPushes() {
        try {
            val ss = ServerSocket(15002)
            server = ss
            while (currentCoroutineContext().isActive) {
                val s = ss.accept()
                scope?.launch { handlePush(s) }
            }
        } catch (e: Exception) { /* puerto ocupado o servicio detenido */ }
    }

    private suspend fun handlePush(s: Socket) {
        s.use { sock ->
            sock.soTimeout = 10_000
            val inp = DataInputStream(sock.getInputStream())
            val ip = sock.inetAddress.hostAddress
            try {
                while (true) {
                    val p = DvripClient.readFrom(inp)
                    val ev = p.json()?.let { DvripClient.parseAlarm(it) } ?: continue
                    val cam = App.instance.cameras.value.firstOrNull { it.host == ip } ?: continue
                    RuleEngine.handle(cam, ev)
                }
            } catch (e: IOException) { /* fin de conexión */ }
        }
    }

    companion object {
        /** Arranca (o reinicia) la vigilancia. */
        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, AlarmService::class.java))
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, AlarmService::class.java)) }
    }
}
