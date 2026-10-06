# Protocolo XM (DVRIP / Sofia) — cámaras iCSee / XMEye

Documentación del protocolo propietario de Xiongmai (XM) que usan las cámaras iCSee, XMEye y marcas blancas.
Reconstruido a partir de documentación y herramientas de la comunidad (python-dvr, dvr-alarm-server, análisis con Wireshark)
y de pruebas con la propia implementación de este proyecto.

> **Aviso de fiabilidad:** no es un estándar publicado. Los IDs, nombres de configuración y campos pueden variar según el
> firmware. Lo marcado como *verificado* se ha comprobado (p. ej. el hash). El resto debe validarse con tu cámara.

Implementación de referencia en este repo: `app/src/main/java/com/xmcam/protocol/`.

---

## 1. Puertos

| Puerto | Protocolo | Uso |
|---|---|---|
| 34567 | TCP | Protocolo principal: login, configuración, PTZ, alarmas, grabaciones, vídeo propio |
| 34568 | UDP | A veces para medios |
| 34569 | UDP | Descubrimiento de cámaras en la LAN |
| 554 | TCP | RTSP (vídeo) |
| 80 | TCP | Web / HTTP (capturas JPEG) |
| 8899 | TCP | ONVIF (si está activo) |
| 15002 | TCP | **Servidor de alarmas**: es el puerto que escucha *tu* servidor; la cámara se conecta a él |

## 2. Estructura de un mensaje

Cabecera de **20 bytes, little-endian**, seguida del cuerpo.

| Bytes | Campo | Ejemplo |
|---|---|---|
| 0 | Marca | `0xFF` |
| 1 | Versión | `0x00` |
| 2-3 | Reservado | `00 00` |
| 4-7 | ID de sesión | `02 00 00 00` |
| 8-11 | Nº de secuencia | `01 00 00 00` |
| 12 | Total de paquetes | `00` |
| 13 | Paquete actual | `00` |
| 14-15 | ID de mensaje | `E8 03` (= 1000, login) |
| 16-19 | Longitud del cuerpo | `5F 00 00 00` |

El cuerpo es JSON en texto UTF-8 terminado en `\n` + byte nulo (`0x0A 0x00`); la longitud incluye esos dos bytes.
La respuesta suele llevar el ID de la petición **+ 1** (1000 → 1001, 1042 → 1043...).

## 3. Hash de contraseña (verificado)

| Paso | Detalle |
|---|---|
| 1 | MD5 de la contraseña (16 bytes) |
| 2 | Sumar los bytes por parejas (0+1, 2+3, ... 14+15) → 8 valores |
| 3 | Cada suma módulo 62 |
| 4 | Convertir con el alfabeto `0-9`, `A-Z`, `a-z` (0-9 → dígito, 10-35 → A-Z, 36-61 → a-z) |
| Ejemplo | Contraseña vacía → `tlJwpbo6` |

Implementación: `XmCrypto.hashPassword()`.

> El hash es equivalente a la contraseña (basta con él para iniciar sesión). Trátalo como un secreto.

## 4. Flujo de una sesión

1. Abrir TCP al puerto 34567.
2. **Login** (1000) con sesión 0 → la respuesta trae `SessionID`, `AliveInterval` y `ChannelNum`.
3. Usar ese `SessionID` en la cabecera (bytes 4-7) y en el campo `SessionID` del JSON de cada mensaje.
4. Enviar **keepalive** (1006) cada pocos segundos (la mitad de `AliveInterval` es seguro).
5. Hacer peticiones. La cámara limita las sesiones simultáneas (≈10): conviene cerrar las que no se usen.

## 5. IDs de mensaje

| ID petición / respuesta | Función |
|---|---|
| 1000 / 1001 | Login |
| 1002 / 1003 | Logout |
| 1006 / 1007 | Keepalive |
| 1020 / 1021 | Información del sistema |
| 1040 / 1041 | Escribir configuración |
| 1042 / 1043 | Leer configuración |
| 1360 / 1361 | Capacidades del equipo (`SystemFunction`) |
| 1400 / 1401 | PTZ |
| 1410 / 1411 | Monitor (vídeo en directo propio) |
| 1412 | Datos de vídeo |
| 1413 / 1414 | Claim (abrir canal de vídeo) |
| 1420 / 1421 | Reproducción de grabaciones |
| 1430 / 1431 | Audio bidireccional |
| 1440 / 1441 | Búsqueda de archivos grabados |
| 1442 / 1443 | Consulta de logs |
| 1450 / 1451 | Gestión del sistema (reinicio, reset) |
| 1452 / 1453 | Consulta de hora |
| 1460 / 1461 | Gestión de discos |
| 1470-1489 | Usuarios y grupos |
| 1500 / 1501 | Suscripción a alarmas |
| 1504 | Notificación de alarma (la cámara la envía sola) |
| 1520-1523 | Actualización de firmware |
| 1530 / 1531 | Descubrimiento en LAN (UDP 34569) |
| 1560 / 1561 | Captura de imagen |
| 1590 / 1591 | Fijar hora |

Marcados como **usados** en esta app: 1000, 1006, 1020, 1040, 1042, 1360, 1400, 1440, 1450, 1452, 1500, 1504, 1530, 1590.

## 6. Mensajes con ejemplos

`SESSION` = `0x00000002` en los ejemplos.

| Función | Cuerpo JSON |
|---|---|
| **Login (1000)** | `{"EncryptType":"MD5","LoginType":"DVRIP-Web","PassWord":"tlJwpbo6","UserName":"admin"}` |
| Respuesta | `{"AliveInterval":21,"ChannelNum":1,"DeviceType":"IPC","Ret":100,"SessionID":"0x00000002"}` |
| **Keepalive (1006)** | `{"Name":"KeepAlive","SessionID":"SESSION"}` |
| **Info sistema (1020)** | `{"Name":"SystemInfo","SessionID":"SESSION"}` → `SerialNo`, `HardWare`, `SoftWareVersion`, `BuildTime`... |
| **Capacidades (1360)** | `{"Name":"SystemFunction","SessionID":"SESSION"}` |
| **Leer config (1042)** | `{"Name":"General.Location","SessionID":"SESSION"}` |
| **Escribir config (1040)** | `{"Name":"General.Location","General.Location":{...},"SessionID":"SESSION"}` — se envía el bloque completo |
| **PTZ mover (1400)** | `{"Name":"OPPTZControl","OPPTZControl":{"Command":"DirectionLeft","Parameter":{"AUX":{"Number":0,"Status":"On"},"Channel":0,"MenuOpts":"Enter","POINT":{"bottom":0,"left":0,"right":0,"top":0},"Pattern":"SetBegin","Preset":65535,"Step":5,"Tour":0}},"SessionID":"SESSION"}` |
| **PTZ parar** | Mismo mensaje con `"Preset":-1` |
| **Ir a preset** | `"Command":"GotoPreset"` y `"Preset":N` |
| **Guardar preset** | `"Command":"SetPreset"` y `"Preset":N` |
| **Suscribir alarmas (1500)** | `{"Name":"","SessionID":"SESSION"}` |
| **Alarma recibida (1504)** | `{"Name":"AlarmInfo","AlarmInfo":{"Channel":0,"Event":"VideoMotion","Status":"Start","StartTime":"2026-10-04 12:00:00"}}` |
| **Buscar grabaciones (1440)** | `{"Name":"OPFileQuery","OPFileQuery":{"BeginTime":"2026-10-04 00:00:00","EndTime":"2026-10-04 23:59:59","Channel":0,"DriverTypeMask":"0x0000FFFF","Event":"*","StreamType":"0x00000000","Type":"h264"},"SessionID":"SESSION"}` |
| **Hora (1452)** | `{"Name":"OPTimeQuery","SessionID":"SESSION"}` |
| **Fijar hora (1590)** | `{"Name":"OPTimeSetting","OPTimeSetting":"2026-10-04 12:00:00","SessionID":"SESSION"}` |
| **Reiniciar (1450)** | `{"Name":"OPMachine","OPMachine":{"Action":"Reboot"},"SessionID":"SESSION"}` |
| **Captura (1560)** | `{"Name":"OPSNAP","OPSnap":{"Channel":0},"SessionID":"SESSION"}` (esta app usa HTTP, ver §10) |

## 7. Comandos PTZ

| Comando | Acción |
|---|---|
| `DirectionUp / Down / Left / Right` | 4 direcciones |
| `DirectionLeftUp / LeftDown / RightUp / RightDown` | Diagonales |
| `ZoomTile / ZoomWide` | Zoom acercar / alejar |
| `FocusNear / FocusFar` | Enfoque |
| `IrisSmall / IrisLarge` | Iris |
| `SetPreset / GotoPreset / ClearPreset` | Presets |
| `StartTour / StopTour` | Rutas automáticas |

**Ojo, es al revés de lo intuitivo:** para MOVER (dirección, zoom, enfoque) el mensaje lleva `Preset` = **65535**, y el movimiento continúa hasta que se envía el **mismo comando con `Preset` = -1**. Verificado con una captura de tráfico real y con la librería python-dvr. Para GotoPreset/SetPreset/ClearPreset, `Preset` es el número de preset.

## 8. Bloques de configuración habituales

| Nombre | Contenido |
|---|---|
| `General.Location` | Idioma, formato de fecha |
| `General.AutoMaintain` | Reinicio automático |
| `Simplify.Encode` | Resolución, FPS, bitrate (flujo principal y secundario) |
| `Camera.Param` | Imagen: brillo, contraste, espejo, IR |
| `AVEnc.VideoWidget` | OSD (hora y título en pantalla) |
| `Detect.MotionDetect` | Detección de movimiento (incluye `EventHandler`) |
| `Detect.HumanDetection` | Detección de personas |
| `Detect.BlindDetect` / `Detect.LossDetect` | Cámara tapada / pérdida de vídeo |
| `NetWork.NetCommon` | IP, máscara, puerta de enlace, puertos |
| `NetWork.NetDHCP` | DHCP |
| `NetWork.Wifi` | WiFi |
| `NetWork.NetNTP` | Servidor de hora |
| `NetWork.NetEmail` | SMTP para notificaciones por email |
| `NetWork.NetFTP` | FTP |
| `Uart.PTZ` | Protocolo y velocidad PTZ |
| `Record` | Programación de grabación |
| `StorageInfo` | Estado de la tarjeta SD |

### `EventHandler`: qué hace la cámara al detectar algo

Dentro de los bloques de detección (`Detect.MotionDetect`, `Detect.HumanDetection`...) hay un objeto `EventHandler`
con interruptores por cada acción nativa. Los nombres exactos cambian según firmware; los más habituales:

| Campo | Acción de la cámara |
|---|---|
| `RecordEnable` | Grabar en la SD |
| `SnapEnable` | Hacer foto |
| `MailEnable` | Enviar email (usa `NetWork.NetEmail`) |
| `FTPEnable` | Subir a FTP |
| `BeepEnable` | Zumbador |
| `AlarmOutEnable` | Salida de alarma / sirena / relé |
| `MsgtoNetEnable` | Enviar el aviso al servidor de alarmas (§9) |
| `PtzEnable` | Mover PTZ a preset |
| `TipEnable`, `MessageEnable`, `LogEnable` | Avisos en pantalla, mensajes y logs |

Por eso la app incluye un **editor genérico**: lee el bloque, muestra todos los interruptores que existan en *tu* firmware
y escribe el bloque completo al guardar.

## 9. Alarmas: cómo avisa la cámara

Hay dos mecanismos, según el firmware:

| Mecanismo | Funcionamiento |
|---|---|
| **Sesión abierta** | Tras `1500`, la cámara empuja paquetes `1504` por la misma conexión TCP del puerto 34567 |
| **Servidor de alarmas** | En la configuración de la cámara se indica IP y puerto (por defecto **15002**) de un servidor. Al saltar una alarma la cámara **abre una conexión TCP contra ese servidor** y envía cabecera XM + JSON. Es TCP, **no HTTP** |

Si la sesión abierta no da callbacks aunque la cámara detecte movimiento, probablemente es del segundo grupo.

JSON típico de un aviso empujado (los campos varían):

| Campo | Significado |
|---|---|
| `Type` | `Alarm` (evento) o `Log` (registro del sistema) |
| `Event` | `VideoMotion`, `HumanDetect`, `VideoBlind`... |
| `Status` | `Start` / `Stop` |
| `Channel` | Canal |
| `StartTime` | Fecha y hora del evento |
| `SerialID` | Número de serie |
| `Address` | IP de la cámara en hexadecimal little-endian |

El aviso **no incluye foto ni vídeo**: hay que pedirla después (captura HTTP o snapshot XM). La cámara no espera
respuesta; si el servidor no escucha, el aviso se pierde.

**Llamar a una URL:** no se ha encontrado evidencia de que la cámara pueda hacer un GET HTTP por sí sola. Lo hace el
receptor del aviso (la app o un servicio en casa) cuando recibe la alarma.

### Tipos de evento

| Evento | Significado |
|---|---|
| `VideoMotion` | Movimiento |
| `HumanDetect` | Persona detectada |
| `VideoBlind` | Cámara tapada |
| `VideoLoss` | Pérdida de vídeo |
| `StorageNotExist` / `StorageFailure` / `StorageLowSpace` | Problemas con la SD |
| `NetAbort` / `IPConflict` | Problemas de red |

## 10. Códigos de respuesta (`Ret`)

| Código | Significado |
|---|---|
| 100 | OK |
| 101 | Error desconocido |
| 103 | Petición ilegal |
| 105 | Usuario no conectado |
| 106 | Usuario o contraseña incorrectos |
| 107 | Sin permiso |
| 108 | Timeout |
| 203 / 205 | Contraseña incorrecta / usuario inexistente (según firmware) |
| 515 | Visto como éxito de login en algunos firmwares |
| 603 | Visto al escribir config; suele indicar que hace falta reiniciar |

## 11. Vídeo y capturas

| Vía | Ejemplo |
|---|---|
| RTSP principal | `rtsp://IP:554/user=admin&password=&channel=1&stream=0.sdp?real_stream` |
| RTSP secundario | Igual con `stream=1` |
| Captura HTTP | `http://IP/webcapture.jpg?command=snap&channel=1&user=admin&password=HASH` |
| Vídeo por canal XM | Mensajes 1410/1412 con frames marcados `00 00 01 Fx` (I, P, audio, info); H.264/H.265 y audio G.711 |

La app usa RTSP para el directo y HTTP para las capturas. Si el RTSP no abre, probar con el hash XM en lugar de la
contraseña (opción en Ajustes de la cámara) o comprobar que RTSP esté activado en la cámara.

## 12. Descubrimiento en la LAN

| Paso | Detalle |
|---|---|
| Envío | Broadcast UDP a `255.255.255.255:34569` con cabecera y mensaje 1530, sin cuerpo (hex: `ff00000000000000000000000000fa0500000000`) |
| **Recepción** | Las cámaras responden **al puerto UDP 34569**, no al puerto de origen: hay que enlazar (`bind`) el socket al 34569 *antes* de enviar |
| Respuesta (1531) | JSON con `NetWork.NetCommon`: `HostName`, `HostIP`, `MAC`, `SN`, `TCPPort`, `HttpPort`, `GateWay`, `Submask` |
| `HostIP` | Hexadecimal little-endian: `0x0A01A8C0` → `192.168.1.10` |

## 13. Firmware reciente (transporte cifrado)

Algunas versiones recientes de iCSee/XMEye solo responden al DVRIP **cifrado** que usan las apps oficiales: con el login normal la conexión se abre pero la respuesta llega vacía o ilegible. Existen implementaciones de la comunidad que lo detectan en el login y cambian de modo (p. ej. `dbuezas/icsee-ptz`), aunque su autor indica que no lo ha probado aún en una cámara real. XMCam **no lo implementa todavía**: la pantalla *Diagnóstico de conexión* indica si tu cámara responde así.

## 14. Seguridad

- El protocolo XM no usa TLS. Mantén las cámaras en una red local aislada y **no las expongas a Internet**.
- Se han publicado vulnerabilidades en firmwares XM (p. ej. ONVIF sin autenticación). Desactiva ONVIF si no lo usas y
  cambia las credenciales por defecto.
- El hash XM equivale a la contraseña; no lo registres en logs.

## Reproducción y descarga de clips de la SD

Verificado por otros proyectos con cámaras iCSee/XMEye (voidnullvalue/Icsee-android, MIT); sin probar aún con la cámara de este proyecto.

1. `OPFileQuery` (1440) → lista de clips (`FileName`, `BeginTime`, `EndTime`).
2. `OPPlayBack` **Claim** en el mensaje 1424 (esperar la respuesta 1425).
3. `OPPlayBack` **DownloadStart** en el mensaje 1420, con el mismo cuerpo: `{"Name":"OPPlayBack","OPPlayBack":{"Action":"DownloadStart","StartTime":..,"EndTime":..,"Parameter":{"PlayMode":"ByName","FileName":..,"StreamType":0,"Value":0,"TransMode":"TCP"}},"SessionID":..}`.
4. Los datos llegan por el mensaje 1426 hasta un paquete de longitud 0. Se ignoran los paquetes cuyo cuerpo empieza por `{` (respuestas de control).
5. `DownloadStop` (1420) y cerrar la sesión. La cámara solo atiende **una descarga a la vez**.

El contenido es vídeo H.265 (a veces H.264) con envoltorios XM (NAL 0xF9/0xFA/0xFC/0xFD). La app extrae las NAL de vídeo (`XmClipParser`), las vuelve a empaquetar en MP4 con `MediaMuxer` (`ClipConverter`) y lo reproduce con Media3. Las miniaturas salen del primer fotograma de los primeros ~768 KB del clip. Por ahora el MP4 no incluye audio.
