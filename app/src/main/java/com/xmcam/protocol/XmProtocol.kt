package com.xmcam.protocol

/**
 * Constantes del protocolo XM (DVRIP/Sofia) usado por las cámaras iCSee/XMEye.
 * Esta carpeta (`protocol`) es Kotlin/JVM puro: no depende de Android salvo `org.json`,
 * así que se puede copiar a otros proyectos.
 */

/** IDs de mensaje. La respuesta suele ser el ID de la petición + 1. */
object Msg {
    const val LOGIN = 1000
    const val LOGOUT = 1002
    const val KEEPALIVE = 1006
    const val SYSINFO = 1020
    const val CONFIG_SET = 1040
    const val CONFIG_GET = 1042
    const val ABILITY = 1360
    const val PTZ = 1400
    /** OPPlayBack: DownloadStart / DownloadStop (1420), Claim (1424); los datos llegan por 1426. */
    const val PB_CTRL = 1420
    const val PB_CLAIM = 1424
    const val PB_DATA = 1426
    const val FILE_QUERY = 1440
    const val MACHINE = 1450
    const val TIME_QUERY = 1452
    const val ALARM_SUB = 1500
    const val ALARM_PUSH = 1504
    const val DISCOVER = 1530
    const val SNAP = 1560
    const val TIME_SET = 1590
}

/** Comandos PTZ del mensaje OPPTZControl. */
object PtzCmd {
    const val UP = "DirectionUp"
    const val DOWN = "DirectionDown"
    const val LEFT = "DirectionLeft"
    const val RIGHT = "DirectionRight"
    const val LEFT_UP = "DirectionLeftUp"
    const val LEFT_DOWN = "DirectionLeftDown"
    const val RIGHT_UP = "DirectionRightUp"
    const val RIGHT_DOWN = "DirectionRightDown"
    const val ZOOM_IN = "ZoomTile"
    const val ZOOM_OUT = "ZoomWide"
    const val FOCUS_NEAR = "FocusNear"
    const val FOCUS_FAR = "FocusFar"
    const val GOTO_PRESET = "GotoPreset"
    const val SET_PRESET = "SetPreset"
}

/** Eventos de alarma conocidos y su etiqueta en español. */
object XmEvents {
    val labels = linkedMapOf(
        "VideoMotion" to "Movimiento",
        "HumanDetect" to "Persona",
        "VideoBlind" to "Cámara tapada",
        "VideoLoss" to "Pérdida de vídeo",
        "StorageNotExist" to "SD no encontrada",
        "StorageFailure" to "Fallo de SD",
        "StorageLowSpace" to "SD casi llena",
        "NetAbort" to "Red caída",
        "IPConflict" to "Conflicto de IP"
    )
    fun label(event: String) = labels[event] ?: event
}

/** Evento de alarma ya interpretado. */
data class AlarmEvent(val event: String, val status: String, val channel: Int, val time: String)

/** Error del protocolo (login fallido, `Ret` distinto de OK...). */
class XmException(message: String, val ret: Int = 0) : Exception(message)

/** Descripción de los códigos `Ret` más habituales. */
object RetCodes {
    val OK = setOf(100, 515, 603)
    fun describe(ret: Int) = when (ret) {
        100 -> "OK"
        101 -> "Error desconocido"
        103 -> "Petición ilegal"
        105 -> "Usuario no conectado"
        106 -> "Usuario o contraseña incorrectos"
        107 -> "Sin permiso"
        108 -> "Timeout"
        203 -> "Contraseña incorrecta"
        205 -> "Usuario inexistente"
        else -> "Código $ret"
    }
}
