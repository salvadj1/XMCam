package com.xmcam.data

import com.xmcam.protocol.DvripClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Control PTZ fluido para un joystick. Diferencias con [ControlSession]:
 *  - Los comandos se envían SIN esperar la respuesta (no se bloquea nunca esperando a la cámara).
 *  - Si llegan órdenes más rápido de lo que se pueden enviar, solo cuenta la ÚLTIMA (canal "conflated"),
 *    con un máximo de ~16 mensajes por segundo: la cámara no se satura y el vídeo no se resiente.
 *  - Una corrutina descarta las respuestas entrantes y otra manda keepalives para que la cámara no cierre la sesión.
 *  - Al soltar se paran TODOS los comandos usados en el gesto, dos veces por si un paquete se pierde.
 *  - Usa su propia conexión: no compite con las peticiones de presets, capturas o ajustes.
 */
class PtzController(private val cam: Camera) : Closeable {
    private data class Target(val cmd: String?, val step: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val targets = Channel<Target>(Channel.CONFLATED)
    private val used = LinkedHashSet<String>()   // comandos enviados desde la última parada
    private var current = Target(null, 0)
    private var client: DvripClient? = null
    @Volatile private var alive = false

    private val worker = scope.launch {
        for (t in targets) {
            runCatching { apply(t) }
            delay(MIN_GAP_MS)
        }
    }

    /** Abre la conexión por adelantado para que el primer movimiento sea inmediato. */
    fun warmUp() { scope.launch { runCatching { ensure() } } }

    /** Movimiento deseado: [cmd] = comando PTZ o null para parar; [step] = velocidad 1..8. */
    fun set(cmd: String?, step: Int = 5) { targets.trySend(Target(cmd, step.coerceIn(1, 8))) }

    @Synchronized
    private fun apply(t: Target) {
        try { send(t) } catch (e: IOException) { drop(); send(t) }   // reconecta una vez
    }

    private fun send(t: Target) {
        val c = ensure()
        if (t.cmd == null) {
            if (used.isNotEmpty()) {
                repeat(2) { i ->
                    used.forEach { c.ptzSend(it, cam.channel, stop = true) }
                    if (i == 0) Thread.sleep(80)
                }
                used.clear()
            }
        } else if (t != current) {
            // Cambiar de dirección o de velocidad = enviar el nuevo comando (sin parar entre medias: sin tirones).
            c.ptzSend(t.cmd, cam.channel, step = t.step)
            used.add(t.cmd)
        }
        current = t
    }

    @Synchronized
    private fun ensure(): DvripClient {
        val existing = client
        if (existing != null && alive) return existing
        drop()
        val c = DvripClient(cam.host, cam.port)
        try { c.login(cam.user, cam.password) } catch (e: Exception) { c.close(); throw e }
        c.setReadTimeout(2000)
        client = c
        alive = true
        scope.launch { drain(c) }
        scope.launch { keepAliveLoop(c) }
        return c
    }

    /** Lee y descarta lo que envíe la cámara (respuestas 1401...) para que su buffer no se llene. */
    private fun drain(c: DvripClient) {
        try {
            while (client === c) {
                try { c.readPacket() } catch (e: SocketTimeoutException) { /* sin datos: seguimos */ }
            }
        } catch (e: Exception) {
            if (client === c) alive = false
        }
    }

    private suspend fun keepAliveLoop(c: DvripClient) {
        while (client === c && alive) {
            delay(maxOf(5, c.aliveIntervalSec / 2) * 1000L)
            try { c.keepAlive() } catch (e: Exception) { if (client === c) alive = false }
        }
    }

    private fun drop() {
        runCatching { client?.close() }
        client = null
        alive = false
    }

    /** Para cualquier movimiento pendiente y cierra la conexión. */
    override fun close() {
        targets.trySend(Target(null, 0))
        targets.close()
        scope.launch {
            worker.join()
            runCatching { apply(Target(null, 0)) }
            drop()
            scope.cancel()
        }
    }

    private companion object { const val MIN_GAP_MS = 60L }
}
