package com.xmcam.protocol

import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Diagnóstico de conexión paso a paso. Indica EN QUÉ CAPA falla:
 *  1. ¿Se alcanza el puerto XM (34567) por TCP?
 *  2. ¿Responde al login? (distingue: sin respuesta / conexión cerrada / respuesta ilegible = protocolo
 *     cifrado / credenciales incorrectas / OK)
 *  3. ¿Están abiertos HTTP, RTSP y ONVIF?
 * Bloqueante: ejecutar en un hilo de fondo. Cada línea de resultado se entrega a [log].
 */
object XmDiagnostics {

    fun run(host: String, port: Int, user: String, password: String, httpPort: Int, rtspPort: Int, log: (String) -> Unit) {
        log("Cámara $host · puerto XM $port · usuario \"$user\"")
        val s = Socket()
        var connected = false
        val t0 = System.currentTimeMillis()
        try {
            s.connect(InetSocketAddress(host, port), 4000)
            connected = true
            log("✅ 1. Puerto $port abierto (${System.currentTimeMillis() - t0} ms)")
        } catch (e: SocketTimeoutException) {
            log("❌ 1. Puerto $port sin respuesta (timeout). Revisa: IP correcta, misma red/subred, aislamiento de clientes en el router, VPN o datos móviles.")
        } catch (e: Exception) {
            val m = e.message ?: ""
            log(when {
                "ECONNREFUSED" in m -> "❌ 1. Puerto $port RECHAZADO: la cámara está pero ese puerto está cerrado (no usa DVRIP ahí, o es solo-nube)."
                "ENETUNREACH" in m || "EHOSTUNREACH" in m || e is NoRouteToHostException -> "❌ 1. Sin ruta hasta $host: red distinta o el móvil usa datos móviles."
                else -> "❌ 1. Error de conexión: ${e.javaClass.simpleName}: $m"
            })
        }
        if (connected) loginStep(s, user, password, log)
        runCatching { s.close() }

        checkPort(host, httpPort, "HTTP, capturas", log)
        rtspProbe(host, rtspPort, log)
        checkPort(host, 8899, "ONVIF", log)
        log("— Fin del diagnóstico —")
    }

    private fun loginStep(s: Socket, user: String, password: String, log: (String) -> Unit) {
        try {
            s.soTimeout = 4000
            val req = JSONObject().put("EncryptType", "MD5").put("LoginType", "DVRIP-Web")
                .put("PassWord", XmCrypto.hashPassword(password)).put("UserName", user)
            s.getOutputStream().apply { write(DvripClient.buildPacket(0, 0, Msg.LOGIN, req)); flush() }

            val inp = s.getInputStream()
            val head = ByteArray(20)
            var got = 0
            try {
                while (got < 20) { val n = inp.read(head, got, 20 - got); if (n < 0) break; got += n }
            } catch (e: SocketTimeoutException) {
                if (got == 0) {
                    log("❌ 2. Conecta pero NO responde al login (timeout). Puede ser otro servicio en ese puerto o firmware con protocolo cifrado.")
                    return
                }
            }
            if (got == 0) {
                log("❌ 2. La cámara CERRÓ la conexión al recibir el login. Posibles causas: protocolo cifrado (firmware reciente), límite de sesiones, o no es DVRIP.")
                return
            }
            if (got < 20 || (head[0].toInt() and 0xFF) != 0xFF) {
                log("❌ 2. Respuesta no reconocible (bytes: ${hex(head, got)}). Típico de firmware reciente con protocolo CIFRADO.")
                return
            }
            val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val msg = b.getShort(14).toInt() and 0xFFFF
            val len = b.getInt(16)
            if (len !in 0..65536) { log("❌ 2. Cabecera XM con longitud absurda ($len)."); return }
            val body = ByteArray(len)
            var r = 0
            try { while (r < len) { val n = inp.read(body, r, len - r); if (n < 0) break; r += n } } catch (e: SocketTimeoutException) { }
            val json = try { JSONObject(String(body, 0, r, Charsets.UTF_8).trim { it <= ' ' }) } catch (e: Exception) { null }
            if (json == null) {
                log("❌ 2. Cabecera XM válida (mensaje $msg) pero cuerpo ilegible (${hex(body, minOf(r, 24))}). Posible protocolo CIFRADO.")
                return
            }
            val ret = json.optInt("Ret", -1)
            if (ret in RetCodes.OK) {
                log("✅ 2. Login correcto · tipo ${json.optString("DeviceType", "?")} · canales ${json.optInt("ChannelNum", 1)} · sesión ${json.optString("SessionID")}")
            } else {
                val hint = if (ret == 106 || ret == 203 || ret == 205) " Revisa usuario y contraseña (los de la app iCSee)." else ""
                log("❌ 2. Login rechazado: ${RetCodes.describe(ret)} (Ret=$ret).$hint")
            }
        } catch (e: IOException) {
            log("❌ 2. Error durante el login: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun checkPort(host: String, port: Int, name: String, log: (String) -> Unit) {
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), 2000) }
            log("✅ 3. Puerto $port ($name) abierto")
        } catch (e: Exception) {
            log("⚪ 3. Puerto $port ($name) cerrado o sin respuesta")
        }
    }

    /** Envía un OPTIONS RTSP y muestra la primera línea de la respuesta. */
    private fun rtspProbe(host: String, port: Int, log: (String) -> Unit) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 2000)
                s.soTimeout = 2500
                s.getOutputStream().apply { write("OPTIONS rtsp://$host:$port/ RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray()); flush() }
                val buf = ByteArray(256)
                val n = s.getInputStream().read(buf)
                val first = if (n > 0) String(buf, 0, n).lineSequence().first().trim() else "(sin datos)"
                log("✅ 3. RTSP $port responde: $first")
            }
        } catch (e: Exception) {
            log("⚪ 3. RTSP $port no responde (${e.javaClass.simpleName}). Actívalo en la cámara si quieres vídeo en directo.")
        }
    }

    private fun hex(b: ByteArray, n: Int) = b.take(n).joinToString(" ") { "%02X".format(it) }
}
