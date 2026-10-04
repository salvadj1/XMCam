package com.xmcam.data

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.provider.MediaStore
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.HttpURLConnection
import java.net.URL

/** Utilidades HTTP: capturas JPEG de la cámara y llamadas a enlaces (webhooks). */
object Net {
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Fuerza que TODO el tráfico de la app salga por la WiFi, aunque esa WiFi no tenga internet.
     * Sin esto Android puede enviar los paquetes por datos móviles y no llegar nunca a la IP local.
     * Efecto secundario: si la WiFi no tiene internet, las URL externas de las reglas fallarán.
     */
    fun bindToWifi(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        if (wifiCallback != null) return
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { cm.bindProcessToNetwork(network) }
            override fun onLost(network: Network) { cm.bindProcessToNetwork(null) }
        }
        wifiCallback = cb
        runCatching { cm.registerNetworkCallback(req, cb) }
    }

    /** Ejecuta [block] con el MulticastLock de WiFi (algunos móviles filtran el broadcast UDP sin él). */
    fun <T> withMulticastLock(ctx: Context, block: () -> T): T {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching { wm?.createMulticastLock("xmcam")?.also { it.setReferenceCounted(false); it.acquire() } }.getOrNull()
        try { return block() } finally { runCatching { lock?.release() } }
    }

    /** Texto con la red que usa la app (para el diagnóstico). */
    fun describeNetwork(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return "desconocida"
        val bound = cm.boundNetworkForProcess
        val n = bound ?: cm.activeNetwork ?: return "SIN RED"
        val c = cm.getNetworkCapabilities(n) ?: return "desconocida"
        val t = when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "DATOS MÓVILES (la cámara no se alcanzará)"
            c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN (puede bloquear la red local)"
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "otra"
        }
        val inet = if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "con internet" else "sin internet validado"
        return "$t${if (bound != null) " (forzada)" else ""}, $inet"
    }


    /** ¿Acepta la cámara conexiones TCP en [port]? Comprobación rápida de que está encendida y accesible. */
    fun probe(host: String, port: Int, timeoutMs: Int = 1500): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (e: Exception) { false }

    /** GET simple a una URL. Devuelve el código HTTP. Bloqueante. */
    fun httpGet(url: String, timeoutMs: Int = 5000): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        return try { c.responseCode } finally { c.disconnect() }
    }

    /** Descarga una captura de la cámara a [dir]. Devuelve null si falla. Bloqueante. */
    fun snapshot(cam: Camera, dir: File): File? = runCatching {
        val c = URL(cam.snapshotUrl()).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 6000
        val bytes = try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
        // Un JPEG empieza por FF D8; si no, la cámara devolvió una página de error.
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return@runCatching null
        dir.mkdirs()
        File(dir, "${cam.id.take(8)}_${System.currentTimeMillis()}.jpg").also { it.writeBytes(bytes) }
    }.getOrNull()

    /** Copia un JPEG a la galería (Pictures/XMCam). */
    fun saveToGallery(ctx: Context, jpg: File): Boolean = runCatching {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "xmcam_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/XMCam")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return@runCatching false
        ctx.contentResolver.openOutputStream(uri)?.use { out -> jpg.inputStream().use { it.copyTo(out) } }
        true
    }.getOrDefault(false)
}
