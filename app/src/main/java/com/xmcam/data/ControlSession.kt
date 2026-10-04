package com.xmcam.data

import com.xmcam.protocol.DvripClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException

/**
 * Sesión de control reutilizable con una cámara (PTZ, ajustes...). Mantiene UNA conexión
 * abierta, serializa las órdenes y reconecta una vez si la conexión se ha caído.
 * Cerrar con [close] al salir de la pantalla.
 */
class ControlSession(private val cam: Camera) : Closeable {
    private var client: DvripClient? = null
    private val mutex = Mutex()

    private fun ensure(): DvripClient {
        client?.let { return it }
        val c = DvripClient(cam.host, cam.port)
        try { c.login(cam.user, cam.password) } catch (e: Exception) { c.close(); throw e }
        client = c
        return c
    }

    /** Ejecuta [block] en un hilo de fondo con el cliente ya autenticado. */
    suspend fun <T> exec(block: (DvripClient) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            try { block(ensure()) } catch (e: IOException) { drop(); block(ensure()) }
        }
    }

    private fun drop() { runCatching { client?.close() }; client = null }
    override fun close() = drop()
}
