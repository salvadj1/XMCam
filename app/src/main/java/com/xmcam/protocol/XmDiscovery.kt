package com.xmcam.protocol

import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Descubrimiento de cámaras XM en la red local (broadcast UDP al puerto 34569, mensaje 1530). */
object XmDiscovery {

    private const val PORT = 34569

    data class Found(
        val ip: String, val name: String, val mac: String,
        val serial: String, val tcpPort: Int, val httpPort: Int
    )

    /**
     * Escanea la LAN durante [timeoutMs] ms. Bloqueante.
     *
     * IMPORTANTE: las cámaras responden al puerto UDP 34569 (no al puerto de origen del envío),
     * por eso el socket se enlaza a ese puerto ANTES de enviar. La petición se repite cada segundo
     * porque UDP puede perderse. Lanza excepción si no se puede abrir el socket o enviar.
     */
    fun scan(timeoutMs: Int = 4000): List<Found> {
        val result = LinkedHashMap<String, Found>()
        val s = DatagramSocket(null)
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(PORT))
            s.broadcast = true
            s.soTimeout = 300

            val req = request()
            val targets = broadcastTargets()
            val buf = ByteArray(4096)
            val start = System.currentTimeMillis()
            var lastSend = 0L
            var sentOk = false
            var lastError: Exception? = null

            while (System.currentTimeMillis() - start < timeoutMs) {
                if (System.currentTimeMillis() - lastSend >= 1000) {
                    for (t in targets) {
                        try { s.send(DatagramPacket(req, req.size, t, PORT)); sentOk = true } catch (e: Exception) { lastError = e }
                    }
                    if (!sentOk) throw IOException("No se pudo enviar el broadcast: ${lastError?.message}")
                    lastSend = System.currentTimeMillis()
                }
                try {
                    val d = DatagramPacket(buf, buf.size)
                    s.receive(d)
                    if (d.length <= 20) continue // nuestro propio broadcast u otro paquete sin cuerpo
                    val json = JSONObject(String(d.data, 20, d.length - 20, Charsets.UTF_8).trim { it <= ' ' })
                    val nc = json.optJSONObject("NetWork.NetCommon") ?: continue
                    // HostIP viene como hex little-endian: "0x0A01A8C0" -> 192.168.1.10
                    val v = nc.optString("HostIP").removePrefix("0x").toLongOrNull(16) ?: continue
                    val ip = "${v and 0xFF}.${(v shr 8) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 24) and 0xFF}"
                    result[ip] = Found(
                        ip, nc.optString("HostName", "Cámara"), nc.optString("MAC"),
                        nc.optString("SN", nc.optString("SerialNo")),
                        nc.optInt("TCPPort", 34567), nc.optInt("HttpPort", 80)
                    )
                } catch (e: SocketTimeoutException) {
                    // nada recibido en este intervalo: seguimos esperando
                } catch (e: org.json.JSONException) {
                    // paquete ajeno al protocolo XM: se ignora
                }
            }
        } finally {
            s.close()
        }
        return result.values.toList()
    }

    /** Petición de descubrimiento: cabecera XM con mensaje 1530 y sin cuerpo (ff00...fa05...). */
    private fun request(): ByteArray {
        val h = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        h.put(0xFF.toByte()); h.put(0.toByte()); h.putShort(0.toShort())
        h.putInt(0); h.putInt(0); h.put(0.toByte()); h.put(0.toByte())
        h.putShort(Msg.DISCOVER.toShort()); h.putInt(0)
        return h.array()
    }

    /** Broadcast global + broadcast de cada subred local (algunos móviles descartan el 255.255.255.255). */
    private fun broadcastTargets(): List<InetAddress> {
        val list = mutableListOf(InetAddress.getByName("255.255.255.255"))
        try {
            for (ni in java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { if (it !in list) list.add(it) }
            }
        } catch (e: Exception) { /* sin acceso a interfaces: nos quedamos con el global */ }
        return list
    }
}
