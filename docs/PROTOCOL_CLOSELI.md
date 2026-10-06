# Protocolo Closeli (ArcSoft) — cámaras Blurams / Ease Life / Vitec

Documentación del protocolo propietario de **ArcSoft Closeli**, usado por cámaras de las marcas **Blurams** y
**Ease Life (Vitec)**, entre otras marcas blancas que integran el SDK `com.nhe` / `com.arcsoft.p2p`.

Reconstruido mediante ingeniería inversa estática de los `.dex` y de las librerías nativas (`.so`, arm64-v8a) de las
apps Android oficiales:

- **Blurams** (`com.vi.nhe` / `com.blurams`) — catorce `classes*.dex`.
- **Ease Life** (`com.vitec.easelifeEn`) — mismo SDK base (`com.nhe`, `com.v2.nhe`, `com.arcsoft.p2p`), empaquetado
  distinto pero binarios nativos equivalentes.

> **Aviso de fiabilidad:** no es un estándar publicado ni documentación oficial de ArcSoft. Reconstruido solo a
> partir del código y los binarios de las apps, **sin capturas de tráfico real ni pruebas contra una cámara física**.
> Lo marcado como *verificado (estático)* se confirmó leyendo el desensamblado ARM64; lo marcado como *inferido* se
> dedujo del contexto (nombres de símbolos, strings, flujo de llamadas) pero no se ha probado en tiempo de ejecución.
> Antes de implementar nada contra una cámara real, valida con un sniffer (Wireshark/tcpdump) en tu propia red.

No hay implementación de referencia todavía en este repo (pendiente, ver `README.md` → Pendiente y limitaciones).

---

## 1. Resumen de arquitectura

El SDK separa dos cosas completamente distintas:

| Capa | Qué hace | Transporte | Documentado aquí |
|---|---|---|---|
| **Descubrimiento LAN** (`liblddiscover.so`, namespace `com.ldd.discoverjni`) | Encuentra cámaras en la red local, lee/cambia credenciales básicas | UDP, JSON cifrado AES-128-CBC | ✅ Sección 2-5 |
| **Vídeo / audio / PTZ en vivo** (`libp2p.so`, `libp2pEngine.so`, `libcloseliP2P.so`, `libP2PWrapper.so`, `libvirtcsdk2.so`) | Streaming, control PTZ, audio bidireccional | P2P propietario tipo libjingle (STUN/TURN/relay) + WebRTC, con *product key* contra servidores cloud de ArcSoft/Closeli | ⚠️ Sección 6 (solo lo observable, no es suficiente para reimplementarlo) |
| **API cloud** (`/sclient/...`) | Login, gestión de cuenta, control remoto, reglas, notificaciones | HTTPS REST contra `*.closeli.cn` | ⚠️ Sección 7 (mapa de endpoints, sin autenticación documentada) |

**Conclusión práctica:** la detección de cámaras en LAN (sección 2-5) es replicable con confianza razonable.
El vídeo en vivo **no** es viable sin las librerías nativas propietarias — no hay un modo RTSP/ONVIF abierto
conocido en este SDK.

---

## 2. Puertos

| Puerto | Protocolo | Uso | Estado |
|---|---|---|---|
| 9999 | UDP (broadcast, destino `255.255.255.255`) | Petición de descubrimiento de dispositivos | Verificado (estático) |
| 37010 | UDP (origen local) | Socket de envío/recepción del broadcast anterior | Verificado (estático) |
| 40080 | UDP (destino `239.255.255.250`, multicast tipo SSDP) | Variante multicast del descubrimiento | Verificado (estático) |
| 37000 | UDP (origen local) | Socket de envío/recepción del multicast anterior | Verificado (estático) |
| 443/80 | TCP/HTTPS | API cloud `*.closeli.cn` (`/sclient/...`) | Inferido |

Los puertos 9999/37010 y 40080/37000 están *hardcodeados* en `liblddiscover.so`, función `UDPSend::start()`
(dos sockets independientes, uno broadcast normal y otro multicast).

---

## 3. Descubrimiento de dispositivos (broadcast/multicast)

Implementado en la clase JNI `com.ldd.discoverjni.LanDeviceDiscover`, respaldada por `liblddiscover.so`
(símbolos C++ con namespace `UDPSend`).

### 3.1 Flujo

1. La app crea un handle: `Lan_device_discover_createUdpSendHanle()`.
2. Lanza el hilo de interacción: `Lan_device_discover_startUdpSendProcess()` → `UDPSend::start()`, que:
   - Crea el socket broadcast, hace `bind()` en el puerto local **37010**, activa `SO_BROADCAST`.
   - Crea el socket multicast, hace `bind()` en el puerto local **37000**, se une a `239.255.255.250` vía
     `setsockopt` + `inet_addr("239.255.255.250")`.
   - Lanza un hilo (`MThreadCreate`/`MThreadResume`) que ejecuta el bucle de interacción.
3. Para pedir la lista de cámaras: `Lan_device_discover_getDeviceListByBroadcast()` →
   `UDPSend::startGetDeviceListByBroadcast()`, que:
   - Construye un JSON de petición (ver 3.2).
   - Lo cifra con AES-128-CBC (ver sección 4).
   - Lo envía con `sendto()` al broadcast (puerto 9999) y además al grupo multicast (puerto 40080).
4. Las respuestas llegan de forma asíncrona y se entregan vía callback nativo→Java:
   `JNIAPGetDeviceListCallBack` (firma `(Ljava/lang/String;Ljava/lang/String;J)V`), registrado con
   `SetGetDeviceListCallBack`.
5. También existe descubrimiento **unicast** dirigido a una IP conocida:
   `Lan_device_discover_getDeviceInfoByUnicast(handle, ip, timeoutMs)` →
   `UDPSend::startGetDeviceListByUnicast()`, útil para comprobar el estado de una cámara ya dada de alta
   (equivalente al "ping" de iCSee/XM) sin esperar al broadcast.

### 3.2 Payload de la petición (antes de cifrar)

JSON construido campo a campo con `Closeli::Json::Value` (hay una implementación de JsonCpp embebida en la
propia `.so`, namespace `Closeli::Json`):

```json
{
  "request": "<string, no confirmado el valor exacto — posiblemente un comando fijo tipo \"search\">",
  "time": "<fecha formateada, strftime \"%Y-%m-%d %H:%M:%S\", buffer de 31 bytes>",
  "sessionid": "<timestamp en milisegundos, gettimeofday, formateado con \"%lld\">",
  "version": "2.0"
}
```

> El campo `request` se construye con `Value::operator[]("request")` pero el string literal asignado no se
> identificó con certeza en el desensamblado disponible (no se vio el string antes del `bl` de asignación);
> **inferido** que es un comando corto tipo `"search"` o `"getDeviceList"`, por analogía con el resto del SDK.
> Verificar por captura de tráfico antes de asumirlo.

El JSON se serializa con `Value::toStyledString()` (salida *pretty-printed*, con indentación — importante si se
quiere recrear el payload byte a byte antes del cifrado, porque el cifrado AES-CBC es sensible al padding/longitud
exacta del texto de entrada).

### 3.3 Buffer de envío

- Se reserva un buffer de `0x20000` bytes (`MMemAlloc` + `memset`) — tamaño generoso, probablemente reutilizado
  para otras operaciones del mismo objeto.
- El JSON en texto plano se cifra con `aes_128_encrypt(...)` (ver sección 4) dentro de ese buffer.
- Se llama a `broadcastInteractAPServer(buffer, len, 9999)` y, por separado, a
  `multicastInteractAPServer(buffer, len, "239.255.255.250", 40080)`.

### 3.4 Respuesta y parseo

- `UDPSend::decryptAPResponseMessage()` descifra el payload recibido (AES-128-CBC, misma clave/derivación que el
  envío) y lo parsea como JSON con `Closeli::Json::Reader`.
- `UDPSend::DeviceNotifyInfoParse()` extrae los campos de cada dispositivo anunciado y los expone vía el segundo
  callback: `JNIAPGetDeviceInfoNotifyCallBack`
  (firma `(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IIJ)V`).
- Los campos de alto nivel que la capa Java recibe por dispositivo, vistos en `com.ldd.discoverjni.LddDeviceInfo`
  (los nombres reales de los campos internos están ofuscados a una letra, pero los getters los identifican):

| Getter Java | Significado |
|---|---|
| `getDeviceId()` | ID único del dispositivo |
| `getApKey()` | Clave de la cámara, se usa también para derivar la clave AES de sesión |
| `getApIV()` | Vector de inicialización asociado a esa cámara/sesión |
| `getApSessionID()` | ID de sesión AP |
| `getApSn()` | Número de serie |
| `getPort()` | Puerto de control adicional de la cámara |

Y en el modelo de más alto nivel `com.v2.nhe.ap.model.VIoTSearchInfo$LanPlay` (usado ya en la capa de
reproducción, no en el discovery puro):

| Campo | Significado |
|---|---|
| `port` | Puerto de control/señalización en LAN |
| `httpport` | Puerto HTTP local de la cámara (posible snapshot/config vía HTTP, **sin confirmar** qué expone) |
| `streamport` | Puerto de streaming de vídeo en LAN |

> Hay un string de log muy revelador en el código Java: `'Device adds USE_LAN_PLAY flag for LAN: '` — indica que
> cuando la cámara anuncia estos tres puertos, la app activa un modo de reproducción **directo en LAN** en vez de
> pasar por el relay P2P/cloud. Es la pista más prometedora para un futuro soporte de vídeo sin depender del SDK
> nativo, pero **no se ha podido determinar el protocolo de aplicación que corre sobre `streamport`/`httpport`**
> (no se vieron cabeceras RTSP/HTTP reconocibles en los símbolos de texto disponibles).

### 3.5 Otras operaciones sobre el mismo canal UDP

Mismo esquema de transporte y cifrado, implementadas como funciones hermanas en `UDPSend`:

- `checkUserInfo(...)` / `CheckUserInfoCallBack` — valida usuario/contraseña contra la cámara en LAN.
- `changePassWord(...)` / `ChangePasswordCallBack` — cambia la contraseña localmente, sin pasar por la nube.

Ambas usan los mismos campos de cifrado y el mismo framing JSON+AES que el discovery; sus payloads exactos no se
han volcado en detalle en este documento (serían el siguiente paso de ingeniería inversa).

---

## 4. Cifrado

### 4.1 Algoritmo

- **AES-128-CBC** vía OpenSSL EVP (`EVP_aes_128_cbc`, `EVP_EncryptInit_ex` / `EVP_DecryptInit_ex`,
  `EVP_EncryptUpdate` / `EVP_DecryptUpdate`, `EVP_EncryptFinal_ex` / `EVP_DecryptFinal_ex`).
- Función wrapper nativa: `aes_128_encrypt(const uint8_t* key, const uint8_t* in, uint8_t* out, int* outLen,
  const uint8_t* iv, int inLen)` (firma reconstruida desde el símbolo mangleado
  `_Z15aes_128_encryptPKhS0_PhPiS0_i`).

### 4.2 Derivación de la clave (verificado por desensamblado, función `closeli_iot_create_crypt_key`)

Algoritmo de derivación (independiente del AES en sí, vive en `liblddiscover.so`):

1. Entrada: hasta **3 cadenas** (ej. `apKey`, `deviceId`, una contraseña...). Si la primera cadena es nula/vacía,
   se usa el literal fijo **`"Closeli"`** embebido en `.rodata` (offset `0x29322` del `.so`) como valor por
   defecto — es decir, hay un "secreto" de fábrica común a toda la familia de dispositivos que sirve de semilla
   cuando no hay credenciales específicas todavía.
2. Para cada cadena *i* de las N proporcionadas: `h[i] = MD5(string_i)` → 16 bytes cada una
   (implementación propia de MD5 embebida, no OpenSSL: `closeli_iot_md5_init/update/final`, con la tabla de
   transformación estándar de RFC 1321 en `closeli_iot_md5_transform`).
3. Se **entrelazan byte a byte** los N hashes de 16 bytes en un buffer de `N*16` bytes: el byte *j* de cada hash
   se coloca en posiciones consecutivas separadas por stride N (p. ej. con N=2: `h0[0], h1[0], h0[1], h1[1], ...`;
   con N=3: `h0[0], h1[0], h2[0], h0[1], h1[1], h2[1], ...`). El código genérico calcula los offsets de
   entrelazado con aritmética sobre N en tiempo de ejecución (válido para N=1,2,3).
4. `key_final (16 bytes) = MD5(buffer_entrelazado)`.

```
key = MD5( interleave_bytes( MD5(s0), MD5(s1), ..., MD5(s_{n-1}) ) )
```

donde, si `s0` no se proporciona, `s0 = "Closeli"`.

### 4.3 IV

- El IV (16 bytes) se pasa como parámetro externo a `aes_128_encrypt`/`aes_128_decrypt`; en el flujo de discovery
  **no se ha podido verificar con certeza en el desensamblado disponible** si el IV es fijo (ceros), derivado con
  el mismo esquema de interleave+MD5 que la clave, o si es el valor `apIV` que la propia cámara devuelve en
  respuestas anteriores. El nombre del campo `apIV` en `LddDeviceInfo`/`VIoTSearchInfo` sugiere fuertemente que
  **cada cámara tiene su propio IV persistente**, entregado la primera vez (p. ej. en el emparejamiento) y
  reutilizado después. **Pendiente de confirmar con captura de tráfico real.**

### 4.4 Qué falta para una implementación funcional

Para poder hablar este protocolo contra una cámara real haría falta, en este orden de prioridad:

1. Confirmar el valor exacto del campo `"request"` (sección 3.2) — sin él la cámara probablemente ignora el
   paquete.
2. Confirmar el IV usado en el *primer* contacto (antes de tener un `apIV` propio) — candidato más probable:
   ceros, o el mismo valor que la clave por defecto (`"Closeli"`) pasado por el mismo interleave+MD5.
3. Capturar un intercambio real con Wireshark (apuntando a la red del fabricante o a una cámara de prueba) para
   validar el formato de JSON de respuesta y los nombres de campo reales (los vistos en `.rodata`:
   `deviceId`, `apIV`, `apKey`, `apSessionID`, `apSn`, `port`, `username`, `passwd`, `code`, `status`, `key2`,
   `key3`).

---

## 5. Campos JSON observados (strings de `.rodata`, uso no siempre confirmado)

| Campo | Contexto probable |
|---|---|
| `deviceId` | Identificador de la cámara |
| `apKey` | Clave de la cámara / semilla de cifrado |
| `apIV` | IV de cifrado específico de la cámara |
| `apSessionID` | Sesión actual con la cámara |
| `apSn` | Número de serie |
| `port` | Puerto de control |
| `username` | Usuario para `checkUserInfo` |
| `passwd` | Contraseña para `checkUserInfo` |
| `oldpassword` | Usado en `changePassWord` |
| `code` | Código de resultado/error en la respuesta |
| `status` | Estado de la operación |
| `key2`, `key3` | Material criptográfico adicional, uso exacto no determinado |
| `request`, `time`, `sessionid`, `version` | Envoltorio común de toda petición (sección 3.2) |

---

## 6. Vídeo / PTZ en vivo (no reimplementable con la información actual)

Observado únicamente a nivel de inventario, **no es una especificación utilizable**:

- Librerías nativas involucradas: `libp2p.so`, `libp2pEngine.so` (26 MB, el componente más grande con diferencia),
  `libcloseliP2P.so`, `libP2PWrapper.so` (expone `com.arcsoft.p2p.*` vía JNI, con *product key* obligatoria:
  strings `"CreateP2PObj failed! error product key"`), y `libvirtcsdk2.so` (WebRTC: SDP, DTLS, STUN/TURN,
  basado en una rama de libjingle).
- Modelo de conexión: P2P con *fallback* a relay cuando no hay conectividad directa
  (`CHANNELMODE_P2P_RELAY_FORCE_RELAY`), más un modo WebRTC completo (SDP offer/answer, ICE con STUN/TURN) para
  el *live view* más reciente.
- Requiere una **product key** emitida por ArcSoft/Closeli y registro contra sus servidores cloud
  (`*.closeli.cn`) — no es un protocolo abierto ni autocontenible en el dispositivo.
- El `LanPlay` descrito en la sección 3.4 (puertos `port`/`httpport`/`streamport`) es la única pista de un posible
  modo de streaming **directo en LAN sin P2P/cloud**, pero no se ha identificado el protocolo de aplicación que
  corre sobre esos puertos.

**Conclusión:** sin capturar tráfico real de vídeo (o sin las librerías nativas enlazadas directamente, lo cual
tiene implicaciones de licencia y tamaño de APK), no es viable añadir soporte de vídeo/PTZ para estas cámaras en
XMCam a corto plazo.

---

## 7. API cloud (`/sclient/...`) — mapa de endpoints, sin autenticación documentada

Vistos como strings literales en los `.dex`, dan una idea de la superficie de la API REST cloud de Closeli.
**No se ha analizado el esquema de autenticación (tokens, firma de requests, etc.) ni los parámetros de cada
endpoint** — queda fuera del alcance de este documento, que se centra en LAN.

| Endpoint | Área |
|---|---|
| `/sclient/camera/video/live/start` | Iniciar vídeo en vivo (vía cloud/relay) |
| `/sclient/camera/video/live/keepalive` | Mantener viva la sesión de vídeo |
| `/sclient/camera/video/replay/start` / `/stop` | Reproducción de grabaciones |
| `/sclient/camera/video/timeline/section/list` | Listado de secciones de grabación |
| `/sclient/camera/video/timeline/special/list` | Eventos especiales en la línea de tiempo |
| `/sclient/camera/audio/talk/start` / `/stop` | Audio bidireccional (talkback) |
| `/sclient/camera/ptz/control` | Control PTZ (vía cloud) |
| `/sclient/camera/snap` | Captura de foto |
| `/sclient/camera/reboot` | Reiniciar cámara |
| `/sclient/camera/info` / `/desc` / `/attr/save` | Información y configuración de la cámara |
| `/sclient/camera/bind/check` / `/bind/qrcode` | Alta de cámara en la cuenta |
| `/sclient/camera/stream/config/set` | Configuración de stream |
| `/sclient/device/relay/multi/ip/list` | Lista de IPs de relay P2P |
| `/sclient/org/...` | Gestión multiusuario/organización (fuera del alcance de una app doméstica) |

Servidores vistos en `cloud_pro.ini` (producción):

```
base_server_ip      = auto-link.closeli.cn
upns_pnserver        = upns.closeli.cn
upns_xmpp_ip          = xmpp.closeli.cn
esd (compra/ajustes)  = esd.closeli.cn
auto_update_server_ip = update.closeli.cn
iot_server_ip          = gwapi.closeli.cn
```

---

## 8. Fuentes de esta documentación

- `com.vi.nhe` / `com.blurams` — APK de Blurams, 14 ficheros `classes*.dex`.
- `com.vitec.easelifeEn` — APK de Ease Life, mismo SDK base, con librerías nativas `arm64-v8a` completas.
- Librerías nativas analizadas en detalle: `liblddiscover.so` (discovery LAN), `libP2PWrapper.so`,
  `libnheslink.so`, `libnms.so`, `libvirtcsdk2.so`, `libp2p.so`, `libmk_api.so` (ZLMediaKit, usado como motor
  interno de reproducción/streaming local de la propia app, no como protocolo expuesto por la cámara).
- Técnica: extracción de símbolos (`.dynsym`/`.symtab`), desensamblado ARM64 con Capstone, lectura de `.rodata`
  para literales de texto, sin acceso a tráfico de red real ni a una cámara física.

## 9. Pendiente y limitaciones

- [ ] Confirmar valor del campo `"request"` en la petición de discovery.
- [ ] Confirmar IV usado en el primer contacto (antes de tener `apIV` de la cámara).
- [ ] Validar formato exacto de JSON de respuesta con una captura de tráfico real.
- [ ] Determinar el protocolo de aplicación sobre `streamport`/`httpport` del modo `LanPlay`.
- [ ] Documentar `checkUserInfo` y `changePassWord` a nivel de payload (solo se documentó el transporte común).
- [ ] Sin información suficiente para implementar vídeo/PTZ sin las librerías nativas propietarias.
