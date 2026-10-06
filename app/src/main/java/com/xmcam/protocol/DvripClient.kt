package com.xmcam.protocol

import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cliente TCP del protocolo XM (puerto 34567). Es BLOQUEANTE: llamar siempre desde
 * un hilo de fondo (Dispatchers.IO). Una instancia = una sesión de la cámara.
 *
 * Formato de cada mensaje: cabecera de 20 bytes (little-endian) + cuerpo JSON terminado en "\n\0".
 *
 * Uso típico:
 * ```
 * DvripClient("192.168.1.10").use { c ->
 *     c.login("admin", "")
 *     c.ptzStart(PtzCmd.LEFT); c.ptzStop(PtzCmd.LEFT)
 * }
 * ```
 */
class DvripClient(
    private val host: String,
    private val port: Int = 34567,
    private val timeoutMs: Int = 5000
) : Closeable {

    /** Paquete recibido: ID de mensaje, sesión y cuerpo en bruto. */
    class Packet(val msgId: Int, val session: Int, val body: ByteArray) {
        /** Cuerpo interpretado como JSON (null si no lo es). Se descartan "\n" y "\0" finales. */
        fun json(): JSONObject? = try {
            JSONObject(String(body, Charsets.UTF_8).trim { it <= ' ' })
        } catch (e: Exception) { null }
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var seq = 0

    /** ID de sesión asignado por la cámara tras el login. */
    var session: Int = 0
        private set
    /** Segundos entre keepalives que pide la cámara. */
    var aliveIntervalSec: Int = 20
        private set
    /** Número de canales (1 en cámaras, varios en NVR/DVR). */
    var channelCount: Int = 1
        private set

    private val sessionStr: String get() = "0x%08X".format(session)

    // ---------------------------------------------------------------- transporte

    /** Abre el socket TCP. */
    fun connect() {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        s.tcpNoDelay = true
        socket = s
        input = DataInputStream(s.getInputStream())
    }

    /** Cambia el timeout de lectura (útil en el bucle de escucha de alarmas). */
    fun setReadTimeout(ms: Int) { socket?.soTimeout = ms }

    /** Envía un mensaje (cabecera + JSON). */
    @Synchronized
    fun send(msgId: Int, body: JSONObject) {
        val packet = buildPacket(session, seq++, msgId, body)
        val out = socket?.getOutputStream() ?: throw IOException("No conectado")
        out.write(packet)
        out.flush()
    }

    /** Lee el siguiente paquete (bloquea hasta el timeout de lectura). */
    fun readPacket(): Packet = readFrom(input ?: throw IOException("No conectado"))

    /**
     * Envía una petición y espera la respuesta [expect] (por defecto msgId + 1).
     * Ignora otros paquetes que lleguen en medio (alarmas, keepalives...).
     */
    fun request(msgId: Int, body: JSONObject, expect: Int = msgId + 1): JSONObject {
        send(msgId, body)
        val deadline = System.currentTimeMillis() + timeoutMs * 2L
        while (System.currentTimeMillis() < deadline) {
            val p = readPacket()
            if (p.msgId == expect) return p.json() ?: throw IOException("Respuesta no JSON")
        }
        throw IOException("Sin respuesta al mensaje $msgId")
    }

    override fun close() { runCatching { socket?.close() } }

    // ---------------------------------------------------------------- sesión

    /** Conecta y hace login. Lanza [XmException] si la cámara rechaza las credenciales. */
    fun login(user: String, password: String) {
        connect()
        val req = JSONObject()
            .put("EncryptType", "MD5")
            .put("LoginType", "DVRIP-Web")
            .put("PassWord", XmCrypto.hashPassword(password))
            .put("UserName", user)
        val r = request(Msg.LOGIN, req)
        checkRet(r)
        session = runCatching { r.optString("SessionID", "0x0").removePrefix("0x").toLong(16).toInt() }.getOrDefault(0)
        aliveIntervalSec = r.optInt("AliveInterval", 20)
        channelCount = r.optInt("ChannelNum", 1)
    }

    /** Mantiene viva la sesión. No espera respuesta. */
    fun keepAlive() = send(Msg.KEEPALIVE, base("KeepAlive"))

    private fun base(name: String) = JSONObject().put("Name", name).put("SessionID", sessionStr)

    private fun checkRet(r: JSONObject) {
        if (r.has("Ret")) {
            val ret = r.optInt("Ret")
            if (ret !in RetCodes.OK) throw XmException(RetCodes.describe(ret), ret)
        }
    }

    // ---------------------------------------------------------------- información y configuración

    /** Información del sistema (serie, versión, canales...). */
    fun systemInfo(): JSONObject =
        request(Msg.SYSINFO, base("SystemInfo")).optJSONObject("SystemInfo") ?: JSONObject()

    /** Capacidades del equipo (qué alarmas, PTZ, audio... soporta). */
    fun capabilities(): JSONObject =
        request(Msg.ABILITY, base("SystemFunction")).optJSONObject("SystemFunction") ?: JSONObject()

    /** Lee un bloque de configuración (p. ej. "Detect.MotionDetect"). Devuelve JSONObject o JSONArray. */
    fun getConfig(name: String): Any? {
        val r = request(Msg.CONFIG_GET, base(name))
        checkRet(r)
        return r.opt(name)
    }

    /** Escribe un bloque de configuración completo (JSONObject o JSONArray). */
    fun setConfig(name: String, value: Any) {
        val r = request(Msg.CONFIG_SET, base(name).put(name, value))
        checkRet(r)
    }

    fun getTime(): String = request(Msg.TIME_QUERY, base("OPTimeQuery")).optString("OPTimeQuery")

    /** Fija la hora de la cámara. Formato "yyyy-MM-dd HH:mm:ss". */
    fun setTime(time: String) {
        checkRet(request(Msg.TIME_SET, base("OPTimeSetting").put("OPTimeSetting", time)))
    }

    /** Reinicia la cámara (la conexión se cortará). */
    fun reboot() {
        send(Msg.MACHINE, base("OPMachine").put("OPMachine", JSONObject().put("Action", "Reboot")))
    }

    // ---------------------------------------------------------------- PTZ

    /**
     * Mensaje PTZ en bruto. Para MOVER una dirección/zoom/enfoque: `preset` = [PTZ_START] (65535).
     * Para PARAR ese movimiento: el mismo comando con `preset` = [PTZ_STOP] (-1). OJO: es al revés de lo intuitivo.
     * Para presets: comando GotoPreset/SetPreset/ClearPreset y `preset` = nº de preset.
     */
    fun ptz(command: String, channel: Int = 0, step: Int = 5, preset: Int = -1) {
        request(Msg.PTZ, ptzMessage(command, channel, step, preset))
    }

    /**
     * Como [ptz] pero SIN esperar la respuesta (no bloquea). Pensado para joystick: `stop = true` para parar.
     * Quien lo use debe descartar las respuestas que lleguen (ver PtzController).
     */
    fun ptzSend(command: String, channel: Int = 0, step: Int = 5, stop: Boolean = false) {
        send(Msg.PTZ, ptzMessage(command, channel, step, if (stop) PTZ_STOP else PTZ_START))
    }

    private fun ptzMessage(command: String, channel: Int, step: Int, preset: Int): JSONObject {
        val p = JSONObject()
            .put("AUX", JSONObject().put("Number", 0).put("Status", "On"))
            .put("Channel", channel)
            .put("MenuOpts", "Enter")
            .put("POINT", JSONObject().put("bottom", 0).put("left", 0).put("right", 0).put("top", 0))
            .put("Pattern", "SetBegin")
            .put("Preset", preset)
            .put("Step", step)
            .put("Tour", 0)
        return base("OPPTZControl").put("OPPTZControl", JSONObject().put("Command", command).put("Parameter", p))
    }

    /** Empieza un movimiento continuo (espera respuesta). Parar con [ptzStop] y el mismo comando. */
    fun ptzStart(command: String, channel: Int = 0, step: Int = 5) = ptz(command, channel, step, PTZ_START)

    /** Para un movimiento PTZ: mismo comando con Preset = -1. */
    fun ptzStop(command: String, channel: Int = 0) = ptz(command, channel, preset = PTZ_STOP)

    // ---------------------------------------------------------------- alarmas y grabaciones

    /** Se suscribe a las alarmas: después la cámara enviará paquetes 1504 por esta sesión. */
    fun subscribeAlarms() { request(Msg.ALARM_SUB, base("")) }

    /** Busca grabaciones en la SD. Fechas "yyyy-MM-dd HH:mm:ss". */
    fun queryFiles(begin: String, end: String, channel: Int = 0, type: String = "h264"): List<JSONObject> {
        val q = JSONObject()
            .put("BeginTime", begin).put("EndTime", end).put("Channel", channel)
            .put("DriverTypeMask", "0x0000FFFF").put("Event", "*")
            .put("StreamType", "0x00000000").put("Type", type)
        val r = request(Msg.FILE_QUERY, base("OPFileQuery").put("OPFileQuery", q))
        val arr = r.optJSONArray("OPFileQuery") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /**
     * Cuerpo JSON del mensaje OPPlayBack. [action]: "Claim", "DownloadStart" o "DownloadStop".
     * [fileName], [begin] y [end] salen de la lista de [queryFiles] (fechas "yyyy-MM-dd HH:mm:ss").
     */
    fun playbackBody(action: String, fileName: String, begin: String, end: String): JSONObject =
        base("OPPlayBack").put(
            "OPPlayBack", JSONObject()
                .put("Action", action).put("StartTime", begin).put("EndTime", end)
                .put(
                    "Parameter", JSONObject()
                        .put("PlayMode", "ByName").put("FileName", fileName)
                        .put("StreamType", 0).put("Value", 0).put("TransMode", "TCP")
                )
        )

    companion object {
        /** Valor de `Preset` que INICIA un movimiento continuo (verificado en capturas de tráfico y python-dvr). */
        const val PTZ_START = 65535
        /** Valor de `Preset` que PARA un movimiento continuo. */
        const val PTZ_STOP = -1

        /** Construye un paquete completo: cabecera de 20 bytes (little-endian) + JSON terminado en "\n\0". */
        fun buildPacket(session: Int, seq: Int, msgId: Int, body: JSONObject): ByteArray {
            val payload = (body.toString() + "\n").toByteArray(Charsets.UTF_8) + 0.toByte()
            val buf = ByteBuffer.allocate(20 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(0xFF.toByte())          // 0     marca
            buf.put(0.toByte())             // 1     versión
            buf.putShort(0.toShort())       // 2-3   reservado
            buf.putInt(session)             // 4-7   sesión
            buf.putInt(seq)                 // 8-11  nº de secuencia
            buf.put(0.toByte())             // 12    total de paquetes
            buf.put(0.toByte())             // 13    paquete actual
            buf.putShort(msgId.toShort())   // 14-15 ID de mensaje
            buf.putInt(payload.size)        // 16-19 longitud del cuerpo
            buf.put(payload)
            return buf.array()
        }

        /** Lee un paquete de cualquier flujo (también sirve para alarmas empujadas al puerto 15002). */
        fun readFrom(inp: DataInputStream): Packet {
            val h = ByteArray(20)
            inp.readFully(h)
            if ((h[0].toInt() and 0xFF) != 0xFF) throw IOException("Cabecera XM inválida")
            val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            val session = b.getInt(4)
            val msg = b.getShort(14).toInt() and 0xFFFF
            val len = b.getInt(16)
            if (len < 0 || len > 8_000_000) throw IOException("Longitud inválida: $len")
            val body = ByteArray(len)
            inp.readFully(body)
            return Packet(msg, session, body)
        }

        /** Interpreta una alarma (1504 por sesión, o JSON empujado al servidor de alarmas). */
        fun parseAlarm(json: JSONObject): AlarmEvent? {
            val a = json.optJSONObject("AlarmInfo") ?: json
            val ev = a.optString("Event")
            if (ev.isEmpty()) return null
            return AlarmEvent(ev, a.optString("Status", "Start"), a.optInt("Channel", 0), a.optString("StartTime"))
        }
    }
}
