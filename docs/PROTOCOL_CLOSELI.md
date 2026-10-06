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
| **Vídeo / audio / PTZ en vivo — vía SDK nativo móvil** (`libp2p.so`, `libp2pEngine.so`, `libcloseliP2P.so`, `libP2PWrapper.so`, `libvirtcsdk2.so`) | Streaming, control PTZ, audio bidireccional | P2P propietario tipo libjingle (STUN/TURN/relay) + WebRTC, con *product key* contra servidores cloud de ArcSoft/Closeli | ⚠️ Sección 6 (solo lo observable por ingeniería inversa estática, no es suficiente para reimplementarlo) |
| **Vídeo / audio / PTZ en vivo — vía API web/cloud** (`soul.ehomeease.com`, `wss://*.blurams.com`) | Login, streaming FLV, PTZ, talk-back, todo contra la nube del fabricante | HTTPS firmado (HMAC-SHA256) + WebSocket binario (AES-256-CBC) | ✅ Sección 7 — **verificado por una implementación externa funcional** (`asmsaifs/ease_life`), no solo por desensamblado propio |
| **API cloud REST** (`/sclient/...`) | Login, gestión de cuenta, control remoto, reglas, notificaciones | HTTPS REST contra `*.closeli.cn` y `*.ehomeease.com` | ⚠️ Sección 8 (mapa de endpoints; autenticación ya documentada en sección 7) |

**Conclusión práctica:** la detección de cámaras en LAN (sección 2-5) es replicable con confianza razonable, pero
**no da vídeo** — solo sirve para alta/estado del dispositivo. El SDK P2P nativo móvil (sección 6) sigue sin ser
viable sin sus librerías propietarias. La vía realmente funcional para vídeo/PTZ/audio es la **API cloud web**
(sección 7): no es LAN pura (como sí lo es XM/DVRIP), pero es un protocolo cerrado, documentado y reproducible,
con cliente de referencia funcionando contra cámaras reales.

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

## 7. Protocolo cloud web (HTTPS + WebSocket) — verificado, con cliente de referencia

A diferencia de las secciones 2-6 (reconstruidas solo por ingeniería inversa estática, sin probar contra hardware
real), esta sección documenta un protocolo **confirmado en producción**: la integración de Home Assistant
[`asmsaifs/ease_life`](https://github.com/asmsaifs/ease_life) implementa este mismo flujo contra cuentas y
cámaras reales de Ease Life/Blurams. Es la vía que usa el **reproductor web oficial** (`www.ehomeease.com`), no
el SDK P2P nativo de la app móvil.

> **Diferencia clave con el protocolo XM/DVRIP de este proyecto:** aquí no hay modo LAN para vídeo. Login,
> streaming y PTZ pasan siempre por la nube del fabricante. Hace falta una cuenta Ease Life/Blurams real
> (email + contraseña) con la cámara ya dada de alta. No es "vigilancia 100% local" como con las cámaras
> iCSee/XMEye que ya soporta XMCam.

### 7.1 Hosts y puertos

| Host | Protocolo | Uso |
|---|---|---|
| `soul.ehomeease.com` | HTTPS (REST) | Login (API "v4 Soul"), listado de dispositivos, refresco de token |
| `wss://<vrs-host>/h5player/live` | WebSocket (WSS, puerto 443) | Vídeo en vivo (FLV binario) y audio bidireccional. El host VRS concreto lo asigna el backend por cámara/sesión |
| `wss://wsrelay.blurams.com:50843` | WebSocket (WSS) | Canal de control: PTZ y mensajes de dispositivo |

### 7.2 Autenticación REST (login)

Cada petición HTTPS va firmada; no basta con un token Bearer simple.

**Credenciales de la app** (constantes, iguales para todos los usuarios — es la clave de *la app*, no la del
usuario):

```
PRODUCT_KEY    = "45c8bd2a-e70"
PRODUCT_SECRET = "qqg5C36lYBlDAQvp3XL6"
```

**Firma de cada request** (HMAC-SHA256 en dos pasos):

```
ts     = timestamp actual en milisegundos (string)
nonce  = UUID v4 aleatorio (hex, sin guiones del formato estándar)
body   = JSON del payload, serializado compacto (sin espacios)

paso1  = access_key + "|" + ts + "|" + path (+ "|" + nonce si hay nonce)
clave_intermedia = HMAC_SHA256(key=paso1, msg=secret)          # nota: key y msg invertidos respecto al uso típico
body_hash        = SHA256(body)
firma            = HMAC_SHA256(key=clave_intermedia, msg=body_hash)   # hexdigest
```

**Cabeceras HTTP de cada request**:

| Cabecera | Valor |
|---|---|
| `User-Agent` | `okhttp/4.9.2` |
| `Request-Version` | `1` |
| `Accept-Time` | `<ts>` |
| `Accept-AccessKey` | `<PRODUCT_KEY>` |
| `Accept-Nonce` | `<nonce>` |
| `Accept-Sign` | `<firma>` (ver arriba) |
| `Content-Type` | `application/json; charset=utf-8` |
| `userToken` | token de sesión (solo en requests autenticadas, tras login) |

**Login** — `POST /oauth/user/login`:

```json
{
  "data": {
    "account": "<email>",
    "accountType": "email",
    "deviceId": "<uuid4>",
    "password": "<md5(\"Arcsoft_\" + password_en_claro)>",
    "passwordTypeEnum": "enc1",
    "loc": "en_US"
  }
}
```

La contraseña **no** viaja en claro ni con el AES-128 de la sección 4: aquí es un MD5 simple con el prefijo fijo
`"Arcsoft_"` — es un esquema de cifrado de contraseña completamente distinto, propio de la capa cloud.

Respuesta: `{"code": 200, "data": {...token, refreshToken...}}`. Un `code` distinto de 200 es error, con el
mensaje en `data.msg`.

**Listado de dispositivos** — `POST /sclient/compatible/device/list` (con `userToken` en cabecera):

```json
{"data": {"pageSize": 50, "settingPaths": [], "supportPaths": [], "attributeIdList": []}}
```

Devuelve `data.deviceList`, con (entre otros) `deviceId`, `thumbnailUrlList` (snapshot cloud) y un campo de
comentario/capacidades donde viaja un bitmask `FEATURE` (ver 7.4).

**Refresco de token** — `POST /oauth/token/refresh`: `{"data": {"refreshToken": "<refresh>"}}`.

### 7.3 Vídeo en vivo (WebSocket FLV)

Conexión: `wss://<vrs_host>/h5player/live`, cabecera `Origin: https://www.ehomeease.com`.

**Mensaje de petición** (enviado como frame de **texto**, justo al conectar):

```
__reqJSONStr=<urlencode(base64(AES-256-CBC(JSON_params)))>
```

Cifrado de ese JSON:

| Parámetro | Valor |
|---|---|
| Algoritmo | AES-256-CBC, PKCS7 |
| Clave | `"viWebsdkCrypto"` rellenada con ceros a 32 bytes (clave fija, igual para todos) |
| IV | 16 bytes a cero |

JSON cifrado (campos principales):

```json
{
  "requestTime": "<ms>", "productKey": "45c8bd2a-e70", "deviceId": "<id>",
  "token": "<userToken>", "hasAudio": "false", "channel": "720p",
  "clientId": "WEBCLIENT_H5_<19 chars aleatorios>", "relayServer": "",
  "isSDCardPlayback": "false", "noAAC": "1"
}
```

**Framing binario de la respuesta** — primer byte = comando:

| Byte | Significado |
|---|---|
| `0x00` | Resto del frame = datos FLV en bruto (vídeo/audio muxado) |
| `0x04` | Resto del frame = JSON urlencoded de control (respuesta del servidor) |

**Sincronización de reloj**: tras el primer frame FLV recibido, el cliente envía **una vez** un frame binario:
`0x03` + `urlencode(json.dumps({"time": <ms transcurridos>, "cmd": 3}))`.

### 7.4 PTZ (WebSocket de control)

Conexión: `wss://wsrelay.blurams.com:50843`, cabecera `Origin: https://www.ehomeease.com`.

1. **Handshake**: `{"type":1,"token":"<userToken>","deviceId":"WEBCLIENT_WEBSOCKET<ms>","channelName":"websocket","userName":"","productKey":"45c8bd2a-e70"}`.
2. **Keepalive**: cada ~10 s, `{"type":7,"cmdId":N}` → el servidor responde `type:7`, el cliente contesta `type:8`.
3. **Movimiento PTZ**: `{"type":3,"cameraId":"<deviceId>","msg":{"msgSession":<id fijo de sesión>,"msgSequence":0,"msgTimeStamp":<ms>,"msgCategory":"camera","msgContent":{"request":1793,"requestParams":{"value":V},"subRequest":5}}}`.

| `value` | Dirección |
|---|---|
| 0 | Volver a posición original ("home") |
| 1 | Izquierda |
| 2 | Derecha |
| 3 | Arriba |
| 4 | Abajo |

4. **Confirmación**: la cámara responde `type:133`/`134` con `msgContent.responseRequest=1793`,
   `responseSubRequest=5` y `response=0` si fue aceptado (cualquier otro valor es rechazo).

**Capacidades PTZ del modelo**: se leen de un bitmask `FEATURE` embebido como texto en el campo de comentario del
dispositivo (el mismo que devuelve `/sclient/compatible/device/list`): `pan=0x10`, `tilt=0x20`, `zoom=0x40`.

### 7.5 Audio bidireccional (talk-back)

Viaja sobre el **mismo WebSocket de vídeo** (sección 7.3), no uno aparte. Codec: **G.711A, 8 kHz, mono**, en
frames de 300 ms (2400 muestras).

| Frame binario | Significado |
|---|---|
| `0x07` + `u32_big_endian(len(json))` + `json` + `audio_g711a_crudo` | Envío de audio. `json` (urlencoded): `{"enctype":0,"channelCount":1,"sampleRate":8000,"timeSpan":300}` |
| `0x02` + `u32_big_endian(len("{}"))` + `"{}"` | Fin de talk-back (sin audio). El JSON vacío es importante: un header incompleto deja la cámara esperando indefinidamente hasta reinicio físico |

### 7.6 Qué falta / riesgos de esta vía

- Depende de servidores del fabricante (`ehomeease.com`, `blurams.com`): si cambian el protocolo o cierran el
  servicio, deja de funcionar — no hay fallback local.
- `PRODUCT_KEY`/`PRODUCT_SECRET` son constantes de la app oficial, no de cada cámara; su uso fuera de la app
  firmada podría violar términos de servicio del fabricante (a diferir con el usuario del proyecto).
- Requiere credenciales de usuario reales (email+contraseña de la cuenta Ease Life/Blurams), con el riesgo de
  gestión de secretos que eso implica dentro de XMCam (ver cómo XMCam ya cifra credenciales con Android Keystore
  para XM, sección "Seguridad" del README — habría que aplicar el mismo criterio aquí).
- Sin confirmar: formato exacto de HLS/snapshot adicional, manejo de múltiples cámaras simultáneas, límites de
  sesión del VRS (el propio cliente de referencia menciona sesiones de 60-100 s con rotación de servidor).

## 8. API cloud REST (`/sclient/...`) — mapa de endpoints adicionales

Vistos como strings literales en los `.dex` de Blurams/Ease Life. El esquema de autenticación ya está resuelto en
la sección 7.2; esto es solo el inventario de endpoints adicionales no explorados en detalle.

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

## 9. Fuentes de esta documentación

- `com.vi.nhe` / `com.blurams` — APK de Blurams, 14 ficheros `classes*.dex`.
- `com.vitec.easelifeEn` — APK de Ease Life, mismo SDK base, con librerías nativas `arm64-v8a` completas.
- Librerías nativas analizadas en detalle: `liblddiscover.so` (discovery LAN), `libP2PWrapper.so`,
  `libnheslink.so`, `libnms.so`, `libvirtcsdk2.so`, `libp2p.so`, `libmk_api.so` (ZLMediaKit, usado como motor
  interno de reproducción/streaming local de la propia app, no como protocolo expuesto por la cámara).
- Técnica propia (secciones 2-6): extracción de símbolos (`.dynsym`/`.symtab`), desensamblado ARM64 con
  Capstone, lectura de `.rodata` para literales de texto, sin acceso a tráfico de red real ni a una cámara física.
- [`asmsaifs/ease_life`](https://github.com/asmsaifs/ease_life) (sección 7) — integración de Home Assistant de
  terceros, open-source, que implementa y usa en producción el protocolo cloud HTTPS+WebSocket contra cuentas y
  cámaras reales de Ease Life/Blurams. Es la fuente del detalle de autenticación, vídeo FLV, PTZ y talk-back; su
  propio README indica que fue reconstruido reverse-engineering el reproductor web oficial (`webPlayer.min.js`)
  y capturas de tráfico reales (HAR), con validación contra hardware físico — nivel de confianza bastante más
  alto que el resto de este documento.

## 10. Pendiente y limitaciones

**Descubrimiento LAN (secciones 2-5), ingeniería inversa propia, sin validar contra hardware:**

- [ ] Confirmar valor del campo `"request"` en la petición de discovery.
- [ ] Confirmar IV usado en el primer contacto (antes de tener `apIV` de la cámara).
- [ ] Validar formato exacto de JSON de respuesta con una captura de tráfico real.
- [ ] Determinar el protocolo de aplicación sobre `streamport`/`httpport` del modo `LanPlay`.
- [ ] Documentar `checkUserInfo` y `changePassWord` a nivel de payload (solo se documentó el transporte común).

**SDK P2P nativo móvil (sección 6):**

- [ ] Sin información suficiente para implementarlo sin las librerías nativas propietarias.

**Protocolo cloud web (sección 7), ya verificado por terceros, pendiente solo de portarlo a XMCam:**

- [ ] Portar el cliente (login, vídeo FLV, PTZ, talk-back) de Python/Home Assistant a Kotlin/Android.
- [ ] Decidir y documentar cómo se almacenan las credenciales de la cuenta Ease Life/Blurams en XMCam
      (Android Keystore, igual que las credenciales XM).
- [ ] Revisar implicaciones de usar `PRODUCT_KEY`/`PRODUCT_SECRET` de la app oficial fuera de ella.
- [ ] Confirmar si hay límite de peticiones/sesión por cuenta que afecte a un uso tipo "vigilancia 24/7".
