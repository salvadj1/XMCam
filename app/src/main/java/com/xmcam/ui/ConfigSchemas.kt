package com.xmcam.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * Descripción "humana" de los bloques de configuración de la cámara: qué ajustes mostrar, con qué título,
 * qué explicación y qué tipo de control. [ConfigScreen] solo enseña los ajustes que la cámara devuelve
 * de verdad; el resto de valores del bloque quedan en "Avanzado".
 */

/** Tipo de control de un ajuste. */
sealed interface Kind {
    /** Interruptor. Admite booleanos, 0/1 y cadenas hexadecimales tipo "0x00000001". */
    object Toggle : Kind
    /** Deslizador entero de [min] a [max]; [labels] da nombre a valores concretos. */
    data class Slider(val min: Int, val max: Int, val unit: String = "", val labels: Map<Int, String> = emptyMap()) : Kind {
        /** Texto mostrado para el valor [v]. */
        fun label(v: Int) = labels[v] ?: "$v$unit"
    }
    /** Elección entre opciones: pares (valor interno, texto mostrado). */
    data class Choice(val options: List<Pair<String, String>>) : Kind
    /** Número con [unit] opcional. */
    data class Num(val unit: String = "") : Kind
    /** Texto libre. */
    object Str : Kind
    /** Texto oculto (contraseñas). */
    object Pass : Kind
    /** Solo se muestra, no se edita. */
    object ReadOnly : Kind
}

/** Un ajuste: [key] es la ruta con puntos dentro del bloque (p. ej. "EventHandler.RecordEnable"). */
class Field(val key: String, val title: String, val help: String, val kind: Kind)

/** Grupo de ajustes con título y nota opcionales. */
class Section(val title: String?, val fields: List<Field>, val note: String? = null)

/** Pantalla de un bloque de configuración: título, introducción y grupos de ajustes. */
class Schema(val title: String, val intro: String, val sections: List<Section>)

// ------------------------------------------------------------------ ayudas de ruta JSON (compartidas)

/** Convierte "A.B.0.C" en la lista de claves e índices ["A","B",0,"C"]. */
internal fun pathOf(key: String): List<Any> = key.split('.').map { it.toIntOrNull() ?: it }

/** Valor simple (booleano, número o texto) en [key] dentro de [base], o null si no existe o no es simple. */
internal fun getPath(base: JSONObject, key: String): Any? {
    var cur: Any? = base
    for (p in pathOf(key)) {
        cur = when {
            cur is JSONObject && p is String -> cur.opt(p)
            cur is JSONArray && p is Int -> cur.opt(p)
            else -> return null
        }
        if (cur == null || cur == JSONObject.NULL) return null
    }
    return if (cur is Boolean || cur is Number || cur is String) cur else null
}

/** Escribe [value] en la ruta [path] del JSON [root] (claves y/o índices). */
internal fun setAt(root: Any, path: List<Any>, value: Any) {
    var cur: Any = root
    for (p in path.dropLast(1)) cur = if (cur is JSONObject) cur.get(p as String) else (cur as JSONArray).get(p as Int)
    val last = path.last()
    if (cur is JSONObject) cur.put(last as String, value) else (cur as JSONArray).put(last as Int, value)
}

/** Rutas completas (con el índice de canal delante si el bloque es un array) de los ajustes que cubre [schema]. */
internal fun coveredPaths(schema: Schema, prefix: List<Any>): Set<String> =
    schema.sections.flatMap { it.fields }.map { (prefix + pathOf(it.key)).joinToString(".") }.toSet()

/** Nombres en español de claves técnicas habituales, para la lista "Avanzado". */
internal val FRIENDLY_NAMES = mapOf(
    "Enable" to "Activado", "Level" to "Nivel de sensibilidad", "Sensitivity" to "Sensibilidad",
    "RecordEnable" to "Grabar en la SD", "SnapEnable" to "Hacer foto", "MessageEnable" to "Enviar mensaje de alarma",
    "MailEnable" to "Enviar email", "FTPEnable" to "Subir a FTP", "BeepEnable" to "Pitido de la cámara",
    "RecordLatch" to "Segundos extra de grabación", "EventLatch" to "Pausa entre avisos",
    "TimeSection" to "Horario (día y franja)", "Region" to "Zona de detección", "MotionArea" to "Zona de movimiento",
    "TotalSpace" to "Capacidad total (MB)", "RemainSpace" to "Espacio libre (MB)", "Status" to "Estado",
    "Name" to "Nombre", "Port" to "Puerto", "UserName" to "Usuario", "Password" to "Contraseña",
    "SSID" to "Nombre de la red WiFi", "Keys" to "Contraseña WiFi", "Address" to "Dirección", "Speed" to "Velocidad"
)

// ------------------------------------------------------------------ esquemas

/** Acciones comunes al detectar un evento ([trigger]: "movimiento", "una persona"...). */
private fun handlerFields(trigger: String) = listOf(
    Field("EventHandler.RecordEnable", "Grabar en la tarjeta SD", "Guarda un clip en la SD cuando detecta $trigger. Necesita tarjeta SD y que la grabación no esté apagada.", Kind.Toggle),
    Field("EventHandler.SnapEnable", "Hacer una foto", "Guarda una foto en la SD cuando detecta $trigger.", Kind.Toggle),
    Field("EventHandler.MessageEnable", "Enviar mensaje de alarma", "Avisa a las aplicaciones conectadas a la cámara (incluida la nube del fabricante, si está activa).", Kind.Toggle),
    Field("EventHandler.MailEnable", "Enviar email", "Manda un correo cuando detecta $trigger. Requiere configurar «Email (SMTP)».", Kind.Toggle),
    Field("EventHandler.FTPEnable", "Subir fotos y vídeo a FTP", "Envía el material a tu servidor FTP. Requiere configurar «FTP».", Kind.Toggle),
    Field("EventHandler.BeepEnable", "Pitido de la cámara", "Hace sonar el zumbador interno de la cámara.", Kind.Toggle),
    Field("EventHandler.RecordLatch", "Segundos extra de grabación", "Cuánto sigue grabando después de que termine el evento.", Kind.Num("s")),
    Field("EventHandler.EventLatch", "Pausa entre avisos", "Tras un aviso, ignora nuevos eventos durante este tiempo para no repetirlos.", Kind.Num("s"))
)

/** Campos de un flujo de vídeo; [p] es "MainFormat" o "ExtraFormat". */
private fun streamFields(p: String) = listOf(
    Field("$p.Video.Resolution", "Resolución", "Tamaño de la imagen (p. ej. 1080P, 720P, D1). Escribe solo valores que acepte tu cámara.", Kind.Str),
    Field("$p.Video.FPS", "Fotogramas por segundo", "Más fotogramas = movimiento más fluido, pero más datos y más espacio en la SD.", Kind.Num("fps")),
    Field("$p.Video.BitRate", "Caudal de vídeo", "Más caudal = mejor calidad y más espacio ocupado en la SD y en la red.", Kind.Num("kbps")),
    Field("$p.Video.Quality", "Calidad", "De 1 (más baja) a 6 (más alta). Solo actúa con caudal variable.", Kind.Slider(1, 6)),
    Field("$p.Video.BitRateControl", "Tipo de caudal", "Constante mantiene el mismo caudal siempre; variable lo adapta a la escena.", Kind.Choice(listOf("CBR" to "Constante", "VBR" to "Variable"))),
    Field("$p.Video.Gop", "Intervalo de fotograma clave", "Cada cuántos fotogramas hay uno completo. Más bajo = se salta mejor en el vídeo, pero pesa más.", Kind.Num()),
    Field("$p.Video.Compression", "Códec", "Formato de compresión del vídeo (solo lectura).", Kind.ReadOnly),
    Field("$p.AudioEnable", "Incluir audio", "Graba y transmite el sonido del micrófono de la cámara.", Kind.Toggle)
)

private val DAYS = listOf(
    "Never" to "Nunca", "Everyday" to "Todos los días", "Monday" to "Los lunes", "Tuesday" to "Los martes",
    "Wednesday" to "Los miércoles", "Thursday" to "Los jueves", "Friday" to "Los viernes",
    "Saturday" to "Los sábados", "Sunday" to "Los domingos"
)

private val SENS = mapOf(1 to "1 · Muy baja", 2 to "2 · Baja", 3 to "3 · Media-baja", 4 to "4 · Media-alta", 5 to "5 · Alta", 6 to "6 · Muy alta")

/** Esquema del bloque de configuración [name], o null si no hay uno hecho (se muestra todo en "Avanzado"). */
internal fun configSchema(name: String): Schema? = when (name) {
    "Detect.MotionDetect" -> Schema(
        "Alarma de movimiento",
        "La cámara vigila la imagen y, si algo se mueve, hace lo que elijas abajo. Solo aparecen las opciones que tu cámara admite.",
        listOf(
            Section("Detección", listOf(
                Field("Enable", "Detectar movimiento", "Si lo apagas, la cámara no avisa ni graba por movimiento.", Kind.Toggle),
                Field("Level", "Sensibilidad", "Con sensibilidad alta salta con cambios pequeños (insectos, luces, árboles); con baja, solo con movimientos claros.", Kind.Slider(1, 6, labels = SENS))
            )),
            Section("Qué hace al detectar movimiento", handlerFields("movimiento"))
        )
    )
    "Detect.HumanDetection" -> Schema(
        "Alarma de persona",
        "Igual que el movimiento, pero la cámara distingue a las personas de otros cambios en la imagen (si tu modelo lo soporta).",
        listOf(
            Section("Detección", listOf(
                Field("Enable", "Detectar personas", "Si lo apagas, no se generan alarmas de persona.", Kind.Toggle),
                Field("Sensitivity", "Sensibilidad", "Más alto = detecta personas más lejanas o poco claras, con más falsas alarmas. El rango depende del modelo.", Kind.Num())
            )),
            Section("Qué hace al detectar una persona", handlerFields("una persona"))
        )
    )
    "Record" -> Schema(
        "Grabación",
        "Cómo y cuándo graba la cámara en la tarjeta SD.",
        listOf(Section(null, listOf(
            Field("RecordMode", "Modo de grabación", "«Según horario» sigue el calendario de abajo (en Avanzado); «Grabar siempre» ignora el horario; «Apagada» no graba.",
                Kind.Choice(listOf("ConfigRecord" to "Según horario", "ManualRecord" to "Grabar siempre", "ClosedRecord" to "Apagada"))),
            Field("PacketLength", "Duración de cada clip", "Longitud de cada archivo grabado. Clips cortos son más fáciles de revisar y descargar.", Kind.Num("min")),
            Field("PreRecord", "Pre-grabación", "Segundos de vídeo anteriores al evento que se añaden al principio del clip.", Kind.Num("s"))
        )))
    )
    "Simplify.Encode" -> Schema(
        "Codificación de vídeo",
        "Calidad del vídeo. El flujo principal es el de alta calidad (se graba en la SD); el secundario es ligero y sirve para ver en directo con poca red.",
        listOf(
            Section("Flujo principal (alta calidad)", streamFields("MainFormat")),
            Section("Flujo secundario (ligero)", streamFields("ExtraFormat"))
        )
    )
    "Camera.Param" -> Schema(
        "Imagen",
        "Orientación y comportamiento de la imagen. Los cambios se ven al momento en el directo.",
        listOf(Section(null, listOf(
            Field("PictureMirror", "Espejo horizontal", "Invierte la imagen de izquierda a derecha.", Kind.Toggle),
            Field("PictureFlip", "Voltear verticalmente", "Pon la imagen boca abajo; útil si la cámara está instalada en el techo.", Kind.Toggle),
            Field("BLCMode", "Compensación de contraluz (BLC)", "Aclara el sujeto cuando hay mucha luz detrás (ventanas, focos).", Kind.Toggle),
            Field("RejectFlicker", "Anti-parpadeo", "Elige la frecuencia de tu red eléctrica para evitar bandas en el vídeo bajo luces artificiales.",
                Kind.Choice(listOf("0" to "Apagado", "1" to "50 Hz (Europa)", "2" to "60 Hz (América)")))
        )))
    )
    "Camera.ParamEx" -> Schema(
        "Imagen: extras",
        "Mejoras adicionales de la imagen. No todas las cámaras las incluyen.",
        listOf(Section(null, listOf(
            Field("BroadTrends", "WDR (rango dinámico amplio)", "Equilibra zonas muy claras y muy oscuras de la misma escena.", Kind.Toggle),
            Field("LowLuxMode", "Mejora con poca luz", "Aumenta la sensibilidad en la oscuridad; puede añadir ruido.", Kind.Toggle),
            Field("Dis", "Estabilizador digital (DIS)", "Reduce las vibraciones de la imagen.", Kind.Toggle),
            Field("Ldc", "Corrección de distorsión (LDC)", "Corrige la deformación de las lentes gran angular.", Kind.Toggle),
            Field("CorridorMode", "Modo pasillo", "Gira la imagen 90° para llenar pasillos o escaleras estrechas.", Kind.Toggle)
        )))
    )
    "AVEnc.VideoWidget" -> Schema(
        "Texto en pantalla (OSD)",
        "Rótulos que la cámara dibuja encima del vídeo (también en las grabaciones).",
        listOf(Section(null, listOf(
            Field("ChannelTitle.Name", "Nombre mostrado", "Texto que aparece en el vídeo como nombre de la cámara.", Kind.Str),
            Field("ChannelTitleAttribute.EncodeBlend", "Mostrar el nombre en el vídeo", "Dibuja el nombre sobre la imagen.", Kind.Toggle),
            Field("TimeTitleAttribute.EncodeBlend", "Mostrar fecha y hora en el vídeo", "Dibuja la fecha y la hora sobre la imagen.", Kind.Toggle)
        )))
    )
    "NetWork.NetEmail" -> Schema(
        "Email (SMTP)",
        "Cuenta de correo desde la que la cámara envía avisos. Para que se envíen, activa «Enviar email» en la alarma de movimiento o de persona.",
        listOf(Section(null, listOf(
            Field("Enable", "Enviar emails", "Activa el envío de correos desde la cámara.", Kind.Toggle),
            Field("Server.Name", "Servidor SMTP", "Dirección del servidor de correo saliente (p. ej. smtp.gmail.com).", Kind.Str),
            Field("Server.Port", "Puerto", "Suele ser 465 con SSL o 587/25 sin SSL.", Kind.Num()),
            Field("UseSSL", "Conexión segura (SSL)", "Cifra la conexión con el servidor. Actívala si tu servidor lo exige.", Kind.Toggle),
            Field("Server.UserName", "Usuario", "Normalmente tu dirección de correo.", Kind.Str),
            Field("Server.Password", "Contraseña", "Contraseña o clave de aplicación de esa cuenta.", Kind.Pass),
            Field("SendAddr", "Remitente", "Dirección que aparece como remitente de los avisos.", Kind.Str),
            Field("Recievers.0", "Destinatario", "Dirección que recibe los avisos.", Kind.Str),
            Field("Title", "Asunto", "Asunto de los correos de aviso.", Kind.Str)
        )))
    )
    "NetWork.NetFTP" -> Schema(
        "FTP",
        "Servidor al que la cámara sube fotos y vídeos de las alarmas. Para que se suban, activa «Subir fotos y vídeo a FTP» en la alarma.",
        listOf(Section(null, listOf(
            Field("Enable", "Subir por FTP", "Activa la subida de archivos.", Kind.Toggle),
            Field("Server.Name", "Servidor FTP", "Dirección IP o nombre de tu servidor.", Kind.Str),
            Field("Server.Port", "Puerto", "El estándar es 21.", Kind.Num()),
            Field("Server.UserName", "Usuario", "Usuario del servidor FTP.", Kind.Str),
            Field("Server.Password", "Contraseña", "Contraseña del servidor FTP.", Kind.Pass),
            Field("Directory", "Carpeta de destino", "Carpeta del servidor donde se guardan los archivos.", Kind.Str),
            Field("MaxFileLen", "Tamaño máximo de archivo", "Los archivos mayores no se suben.", Kind.Num("MB"))
        )))
    )
    "NetWork.NetCommon" -> Schema(
        "Red",
        "Ajustes de red de la cámara. CUIDADO: cambiar puertos puede dejar la cámara inaccesible desde esta app y desde otros equipos.",
        listOf(Section(null, listOf(
            Field("HostName", "Nombre en la red", "Nombre con el que la cámara se anuncia en tu red.", Kind.Str),
            Field("HttpPort", "Puerto web (HTTP)", "Puerto de la página web y de las capturas de imagen. Por defecto 80.", Kind.Num()),
            Field("TCPPort", "Puerto de la app", "Puerto del protocolo que usa esta app. Por defecto 34567; si lo cambias, hay que cambiarlo también en la ficha de la cámara.", Kind.Num()),
            Field("UDPPort", "Puerto de descubrimiento", "Puerto UDP para encontrar cámaras en la red. Por defecto 34568.", Kind.Num()),
            Field("MaxBps", "Límite de ancho de banda", "Tope de datos que envía la cámara.", Kind.Num("kbps"))
        )))
    )
    "NetWork.Wifi" -> Schema(
        "WiFi",
        "Red inalámbrica de la cámara. Si cambias la red o la contraseña, la cámara se desconectará y tendrás que volver a encontrarla.",
        listOf(Section(null, listOf(
            Field("Enable", "WiFi activado", "Enciende o apaga la conexión inalámbrica.", Kind.Toggle),
            Field("SSID", "Nombre de la red", "Nombre de tu red WiFi.", Kind.Str),
            Field("Keys", "Contraseña", "Contraseña de tu red WiFi.", Kind.Pass)
        )))
    )
    "NetWork.NetNTP" -> Schema(
        "Hora y NTP",
        "Mantiene el reloj de la cámara en hora (importante para que las grabaciones y alarmas tengan la hora correcta).",
        listOf(Section(null, listOf(
            Field("Enable", "Sincronizar la hora por Internet", "La cámara consulta un servidor de hora (NTP) para ajustar su reloj.", Kind.Toggle),
            Field("Server.Name", "Servidor de hora", "Servidor NTP (p. ej. pool.ntp.org).", Kind.Str),
            Field("UpdatePeriod", "Sincronizar cada", "Cada cuánto tiempo vuelve a ajustar el reloj.", Kind.Num("min"))
        )))
    )
    "StorageInfo" -> Schema(
        "Almacenamiento (SD)",
        "Estado de la tarjeta SD (solo lectura). Para formatearla, usa el menú de la cámara o la app oficial.",
        emptyList()
    )
    "General.AutoMaintain" -> Schema(
        "Mantenimiento automático",
        "Tareas que la cámara hace sola para seguir funcionando bien.",
        listOf(Section(null, listOf(
            Field("AutoRebootDay", "Reinicio automático", "Reinicia la cámara con regularidad para evitar bloqueos.", Kind.Choice(DAYS)),
            Field("AutoRebootHour", "Hora del reinicio", "Hora del día (0–23) en que se reinicia.", Kind.Slider(0, 23, " h")),
            Field("AutoDeleteFilesDays", "Borrar grabaciones de más de", "Días que se conservan las grabaciones. 0 = no borrar nunca (la SD se llenará y sobrescribirá).", Kind.Num("días"))
        )))
    )
    "General.Location" -> Schema(
        "Idioma y fecha",
        "Cómo muestra la cámara la fecha y la hora, y el idioma de su menú.",
        listOf(Section(null, listOf(
            Field("DateFormat", "Formato de fecha", "Orden de año, mes y día en el vídeo y en los rótulos.",
                Kind.Choice(listOf("DDMMYY" to "Día-Mes-Año", "MMDDYY" to "Mes-Día-Año", "YYMMDD" to "Año-Mes-Día"))),
            Field("TimeFormat", "Formato de hora", "Reloj de 24 horas o de 12 horas.", Kind.Choice(listOf("24" to "24 horas", "12" to "12 horas"))),
            Field("DSTRule", "Horario de verano automático", "Ajusta la hora en los cambios de verano/invierno.", Kind.Choice(listOf("Off" to "No", "On" to "Sí"))),
            Field("Language", "Idioma del menú", "Idioma del menú de la propia cámara (p. ej. Spanish, English).", Kind.Str)
        )))
    )
    "Uart.PTZ" -> Schema(
        "PTZ (protocolo)",
        "Ajustes del motor de giro. Solo para cámaras con PTZ; si no tienes motor, no necesitas tocar nada.",
        emptyList()
    )
    else -> null
}
