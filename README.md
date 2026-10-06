# XMCam

App Android (Kotlin + Jetpack Compose) para controlar cámaras **iCSee / XMEye** (protocolo XM/DVRIP) **en red local**,
sin la app oficial ni la nube. Código ligero: sin Room, sin Retrofit, sin Hilt; JSON con `org.json` (incluido en Android).

> ⚠️ **Estado: primera versión sin compilar ni probar con una cámara real.** Se escribió a partir de la documentación
> del protocolo de la comunidad. Es normal que haya que ajustar algún detalle al abrirlo en Android Studio o con tu
> firmware. Ver [Cómo probar](#cómo-probar-por-orden) y [Pendiente](#pendiente-y-limitaciones).

Documentación del protocolo: [`docs/PROTOCOL_XM.md`](docs/PROTOCOL_XM.md).

Investigación (sin implementar) de un segundo protocolo, para una posible compatibilidad futura con otras marcas:
[`docs/PROTOCOL_CLOSELI.md`](docs/PROTOCOL_CLOSELI.md) — cámaras Blurams / Ease Life (SDK ArcSoft Closeli).

---

## 1. Qué hace

| Área | Funciones |
|---|---|
| **Cámaras** | Búsqueda automática en la LAN (UDP 34569), alta manual, prueba de login, borrado. Indicador de estado cada 4 s: 🟢 en línea · 🔴 sin conexión · ⚪ comprobando |
| **Directo** | Vídeo RTSP (HD/SD) con buffers cortos y reconexión automática, audio escuchar/silenciar, captura a la galería |
| **PTZ** | Joystick analógico (8 direcciones, velocidad según lo que lo alejes, vuelve al centro y para al soltar), zoom, enfoque, 6 presets (toque = ir, largo = guardar) |
| **Grabaciones** | Lista de clips de la tarjeta SD por día |
| **Ajustes de la cámara** | Información del sistema, sincronizar hora, reiniciar, y **editor genérico** de cualquier bloque de configuración |
| **Acciones nativas ante alarma** | Editor de los interruptores `EventHandler` de la cámara: grabar, foto, email, FTP, zumbador, sirena, PTZ... (los que tu firmware tenga) |
| **Vigilancia** | Servicio en segundo plano que escucha alarmas de todas las cámaras |
| **Reglas** | "Cuando pase X → haz Y" con condiciones de cámara, evento, inicio/fin, horario, perfil y anti-spam |
| **Perfiles** | Casa / Fuera / Noche (editables); las reglas pueden limitarse a un perfil |
| **Historial** | Eventos con fecha, cámara y miniatura |
| **Seguridad** | Credenciales en SharedPreferences cifradas (Android Keystore) |

## 2. Dos niveles de acción ante una alarma

| Nivel | Quién lo ejecuta | Ejemplos | Dónde se configura |
|---|---|---|---|
| **Nativo** | La propia cámara | Grabar en SD, enviar email, subir a FTP, zumbador/sirena, foto, mover PTZ | *Ajustes > Alarma de movimiento/persona: qué hacer* |
| **App** | El móvil, al recibir el aviso | Notificación, foto, llamar a una URL, mover PTZ a preset | *Reglas* |

Ejemplo: "al detectar una persona: grabar **sí**, luz **no**, email **sí**, llamar a esta URL **sí**" →
`RecordEnable`=sí, `MailEnable`=sí en la cámara (nivel nativo) + una regla con acción "Llamar a una URL" (nivel app).

> La cámara **no** hace llamadas HTTP por sí sola; envía el aviso por TCP y es la app quien llama a la URL.
> Las acciones de nivel app solo funcionan mientras la vigilancia esté activa (y el móvil encendido y en la red).

### Motor de reglas

| Campo | Significado |
|---|---|
| Cámara | Una concreta o todas |
| Evento | Movimiento, persona, tapada, pérdida de vídeo, SD, red... o cualquiera |
| Solo al empezar | Ignora los avisos de "fin" (`Status = Stop`) |
| Horario | Franja `HH:mm`-`HH:mm`; admite cruzar la medianoche (22:00-07:00) |
| Perfil | Solo si ese perfil está activo |
| Anti-spam | Segundos mínimos entre ejecuciones de la misma regla y cámara |
| Acciones | Foto, mover PTZ a preset, llamar URL (variables `{camera} {event} {status} {time} {channel}`), notificación |

Orden de ejecución fijo: **foto → PTZ → URL → notificación** (así la notificación puede llevar la foto).

## 3. Mapa de la app oficial iCSee → XMCam

| iCSee | XMCam |
|---|---|
| Dispositivos (lista) | Pantalla principal |
| Añadir dispositivo (QR, serie, LAN) | *Añadir cámara* (LAN y manual). QR/serie/nube: no (requieren P2P) |
| Directo + PTZ + captura + audio | *Directo* |
| Intercomunicador | Pendiente |
| Reproducción local (SD) | *Grabaciones* (solo lista) |
| Reproducción en la nube / Cloud Storage | Fuera de alcance (servicio de pago de Xiongmai) |
| Ajustes > Básicos, Red, Hora | *Ajustes* + editor genérico |
| Ajustes > Alarma normal / inteligente | Editor genérico de `Detect.*` + *Reglas* |
| Ajustes > Almacenamiento / Grabación | Editor genérico (`StorageInfo`, `Record`) |
| Alarmas / Mensajes | *Eventos* + notificaciones |
| Compartir cámara | Fuera de alcance (es de cuenta en la nube) |
| Time Microcosm (timelapse) | Fuera de alcance |

## 4. Estructura del proyecto

```
XMCam/
├── README.md
├── docs/PROTOCOL_XM.md                  Protocolo XM completo
├── settings.gradle.kts / build.gradle.kts / gradle.properties
└── app/
    ├── build.gradle.kts                 Dependencias (Compose, Media3 RTSP, security-crypto, corrutinas)
    └── src/main/
        ├── AndroidManifest.xml
        └── java/com/xmcam/
            ├── App.kt                   Estado global (StateFlow) y canales de notificación
            ├── protocol/                ★ Kotlin puro, reutilizable en otros proyectos
            │   ├── XmProtocol.kt        IDs de mensaje, comandos PTZ, eventos, códigos Ret
            │   ├── XmCrypto.kt          Hash de contraseña XM
            │   ├── DvripClient.kt       Cliente TCP: login, config, PTZ, alarmas, archivos...
            │   ├── XmDiscovery.kt       Descubrimiento UDP en la LAN (socket enlazado al 34569)
│   └── XmDiagnostics.kt     Diagnóstico de conexión por capas
            ├── data/
            │   ├── Models.kt            Camera, Rule, Action, EventRec (+ JSON)
            │   ├── Store.kt             Persistencia cifrada
            │   ├── Net.kt               HTTP: capturas, webhooks, guardar en galería
            │   └── ControlSession.kt    Sesión reutilizable con reconexión
            ├── rules/RuleEngine.kt      Motor de reglas y ejecución de acciones
            ├── service/AlarmService.kt  Servicio en primer plano (sesiones + servidor 15002)
            └── ui/                      Pantallas Compose
                ├── MainActivity.kt      Navegación (pila de Screen)
                ├── Common.kt            Tema, barra, campos, definición de Screen
                ├── HomeScreen / AddScreen / LiveScreen / PlaybackScreen
                ├── SettingsScreen / ConfigScreen      (ajustes + editor genérico)
                └── EventsScreen / RulesScreen / RuleEditScreen
```

### Flujo de una alarma

```
Cámara ──(1504 por sesión 34567  ó  TCP a puerto 15002)──► AlarmService
   AlarmService ──► RuleEngine.handle(cámara, evento)
        ├─ filtra reglas (cámara, evento, inicio, horario, perfil, anti-spam)
        ├─ SNAPSHOT  → HTTP webcapture.jpg
        ├─ PTZ       → DvripClient.ptz(GotoPreset)
        ├─ HTTP_GET  → Net.httpGet(url)
        ├─ NOTIFY    → notificación (con foto si hay)
        └─ historial → App.addEvent()
```

## 5. Compilar

Requisitos: **Android Studio Koala (2024.1.1) o superior**, JDK 17 (el que trae Android Studio), Android SDK 34.

1. Abrir la carpeta `XMCam` en Android Studio y esperar a la sincronización de Gradle
   (el proyecto trae `gradle-wrapper.properties`; si pide el wrapper, aceptar la descarga de Gradle 8.7).
2. Conectar el móvil (depuración USB) o usar un emulador **con la cámara accesible desde su red** (mejor un móvil real en
   la misma WiFi que la cámara).
3. *Run ▶*.

Parámetros principales: `minSdk 29`, `compileSdk/targetSdk 34`, Kotlin 1.9.24, Compose BOM 2024.06, Media3 1.3.1.

## 6. Cómo probar (por orden)

1. **Misma red:** móvil y cámara en la misma WiFi/subred (cuidado con el "aislamiento de clientes" de algunos routers).
2. **Añadir cámara:** *Buscar en la red*; si no aparece, IP a mano (puerto 34567, usuario `admin`, contraseña la que pusiste
   en iCSee; vacía en cámaras sin configurar). *Probar y guardar* comprueba el login.
3. **Directo:** si el vídeo no abre, activar en Ajustes *RTSP con hash XM*; comprobar que RTSP está habilitado en la cámara.
4. **PTZ:** solo en cámaras motorizadas.
5. **Ajustes:** abrir *Información*; luego *Alarma de movimiento: qué hacer* y revisar qué interruptores aparecen.
6. **Alarmas:** activar *Vigilancia*, crear una regla con notificación y provocar movimiento. En *Eventos* debe aparecer.
   - Si **no llega nada** y la cámara sí detecta movimiento, es del tipo "servidor de alarmas": en la configuración de la
     cámara (bloque de red/alarma de tu firmware) hay que poner la **IP del móvil y el puerto 15002**, y activar el envío de
     mensajes a red (`MsgtoNetEnable`) en el `EventHandler`. La app ya escucha el 15002.
7. **Capturas:** usan HTTP (puerto 80). Si falla, comprobar que el puerto HTTP de la cámara no está cambiado (campo `httpPort`).

### Si no conecta: Diagnóstico de conexión

En *Añadir cámara*, con la IP escrita, pulsa **Diagnóstico de conexión**. Prueba por capas y dice cuál falla:

| Resultado | Significado |
|---|---|
| Red de la app: DATOS MÓVILES / VPN | El tráfico no sale por la WiFi de la cámara |
| 1 ❌ timeout | IP incorrecta, otra subred, aislamiento de clientes del router |
| 1 ❌ rechazado | Hay cámara pero el puerto 34567 está cerrado (solo-nube) |
| 2 ❌ cerró la conexión / respuesta ilegible | Firmware con protocolo **cifrado** (no soportado aún) |
| 2 ❌ Ret 106/203/205 | Usuario o contraseña incorrectos |
| 2 ✅ | Login correcto: el problema está en otra parte |

Pulsa *Copiar resultado* para pegarlo donde necesites.

**WiFi forzada:** la app fuerza que todo su tráfico salga por la WiFi (aunque no tenga internet) para que Android no use datos móviles. Efecto secundario: si esa WiFi no tiene internet, las acciones "Llamar a una URL" a Internet fallarán.

## 7. Permisos

| Permiso | Para qué |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Hablar con las cámaras y forzar la WiFi |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` | Recibir las respuestas del descubrimiento (broadcast UDP) |
| `POST_NOTIFICATIONS` | Avisos de alarma (Android 13+) |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Servicio de vigilancia |
| `usesCleartextTraffic` | HTTP/RTSP sin TLS en la LAN |

## 8. Pendiente y limitaciones

**No implementado todavía**

| Función | Nota |
|---|---|
| Reproducir / descargar grabaciones de la SD | Hoy solo se listan. Requiere el mensaje 1420 (reproducción) y decodificar el flujo propio; el detalle exacto depende del firmware |
| Audio bidireccional (intercomunicador) | Mensaje 1430 + G.711 |
| Grabación manual en el móvil | Posible con Media3 o capturando el RTSP |
| P2P / acceso desde Internet | Descartado a propósito (solo red local) |
| Cambio de contraseña y gestión de usuarios | Mensajes 1470-1489 |
| Vista multicámara | Varios `ExoPlayer` en cuadrícula |
| Pantalla completa / giro automático | Solo vertical |
| Tours PTZ, iris | Comandos descritos en el protocolo |
| Servidor de alarmas en un PC (Python) | Ver siguiente sección |
| Compatibilidad con cámaras Blurams / Ease Life (protocolo Closeli) | Solo investigado, no implementado. Descubrimiento LAN (UDP 9999/40080, JSON+AES-128) es replicable; vídeo/PTZ depende de un SDK P2P propietario (ArcSoft), ver `docs/PROTOCOL_CLOSELI.md` |

**Limitaciones técnicas**

- **Batería / Doze:** con el móvil en reposo Android puede frenar la red del servicio. Para vigilancia fiable, desactivar la
  optimización de batería para la app. Si es crítico, lo ideal es el servidor fijo en casa.
- La IP de la cámara debe ser **fija** (reserva DHCP en el router).
- Las cámaras de **bajo consumo (batería)** duermen y no mantienen sesión; no son compatibles con este enfoque.
- Los nombres de configuración y `EventHandler` varían por firmware: por eso existe el editor genérico.
- Una cámara admite ≈10 sesiones: la app mantiene una de vigilancia por cámara y abre otras al usar pantallas.

## 9. Siguiente paso recomendado: servidor de alarmas en un PC

Un PC viejo siempre encendido (con Python) puede hacer de "cerebro": escuchar el puerto 15002 y mantener sesiones,
ejecutar las reglas sin depender del móvil y exponer una API a la app. Reutilizaría exactamente el protocolo documentado en
`docs/PROTOCOL_XM.md`.

## 10. Uso responsable

Usa esta app solo con cámaras que sean tuyas o sobre las que tengas autorización. No expongas las cámaras a Internet.

## 11. Reutilizar la capa de protocolo

La carpeta `protocol/` no depende de Android (solo de `org.json`). Para usarla en otro proyecto Kotlin/JVM, cópiala y:

```
DvripClient("192.168.1.10").use { c ->
    c.login("admin", "")                 // hash XM automático
    c.ptzStart(PtzCmd.LEFT); c.ptzStop(PtzCmd.LEFT)
    val cfg = c.getConfig("Detect.MotionDetect")
}
```
