# Pasala — protocolo v1

Pasala pasa archivos entre dispositivos propios (Mac, Android, más adelante otros).
Todos los nodos son iguales: cada uno corre un servidor HTTP y un cliente. No hay
servidor central. La confianza se crea escaneando un QR una sola vez.

## Identidad

Cada nodo tiene, persistido la primera vez que arranca:

| campo      | ejemplo                                 |
|------------|-----------------------------------------|
| `id`       | UUID v4 en minúsculas                   |
| `name`     | "MacBook", "Pixel 9"                    |
| `platform` | `mac` \| `ios` \| `android` \| `linux` \| `windows` |
| `port`     | `47820` (si está ocupado, el siguiente libre) |

`addrs` = lista de IPv4 donde el nodo escucha ahora mismo, en este orden:
IPs de LAN (Wi-Fi/Ethernet) primero, después la IP de Tailscale (`100.64.0.0/10`).
Nunca `127.0.0.1`.

## Emparejamiento por QR

El nodo **A** muestra un QR con el texto:

```
pasala://pair?d=<base64url sin padding de un JSON UTF-8>
```

JSON:

```json
{"v":1,"id":"…","name":"…","platform":"mac","port":47820,
 "addrs":["192.168.1.20","100.101.102.103"],"code":"<22 chars base64url, 16 bytes random>"}
```

- `code` es de un solo uso y vence a los 5 minutos. A puede regenerarlo.
- El nodo **B** escanea, prueba las `addrs` en orden (timeout de conexión 2 s) y manda:

`POST http://<addr>:<port>/v1/pair` — `Content-Type: application/json`

```json
{"code":"…","id":"<id de B>","name":"…","platform":"android","port":47820,"addrs":["…"]}
```

Respuesta `200`:

```json
{"id":"<id de A>","name":"…","platform":"mac","port":47820,"addrs":["…"],"secret":"<43 chars base64url, 32 bytes random>"}
```

`403 {"error":"bad_code"}` si el código no existe, venció o ya se usó.

A partir de ahí **los dos** guardan al otro como *peer* `{id, name, platform, port, addrs, secret, pairedAt, lastSeen}`
con el mismo `secret`. El enlace es simétrico: cualquiera de los dos puede mandar al otro.
No importa quién escaneó a quién. Si se empareja de nuevo con un peer que ya existe, se reemplaza el secret.

## Autenticación

Todas las rutas salvo `/v1/pair` llevan:

```
X-Pasala-From: <id del que llama>
Authorization: Bearer <secret compartido con ese peer>
```

Si el `id` no es un peer o el secret no coincide (comparación en tiempo constante): `401 {"error":"unauthorized"}`.

## Rutas

### `POST /v1/hello`
Body: `{"name":"…","port":47820,"addrs":["…"]}` del que llama. El receptor actualiza ese peer
(`name`, `port`, `addrs`, `lastSeen`). Respuesta `200` con el mismo formato del receptor:
`{"id","name","platform","port","addrs","collections"}`. Se usa como ping antes de mandar y cada vez que
la app vuelve al frente.

**Cada cuánto.** Cada `hello` que llega despierta al receptor (radio y CPU); un celular con la pantalla
apagada lo cuenta como actividad en segundo plano y el sistema termina ofreciendo "detener la app".
Por eso:

- Se manda `hello` justo antes de mandar algo, cuando la app vuelve al frente y, mientras haya una
  pantalla visible que muestre si los dispositivos están en línea, como mucho cada 20 s.
- **Sin pantalla visible, como mucho cada 10 min** (y solo a la última dirección que anduvo). Para saber
  si un dispositivo sigue en la LAN sin despertarlo alcanza con el anuncio mDNS/NSD (ver abajo); si no
  aparece, se lo muestra como "visto hace…" con el último dato.
- Siempre se prueba primero la última dirección que respondió, sola; las demás (LAN y Tailscale) recién
  si esa falla.
- Al buscar la dirección (ver abajo) se prueba primero la última que funcionó; las demás solo si esa falla,
  así un `hello` es una sola conexión y no una por cada dirección.

`collections` (opcional, puede faltar o venir vacío) son las **colecciones del receptor**: destinos con
nombre que la persona armó en ese dispositivo (en la Mac, cada una es una carpeta).

```json
"collections":[{"id":"c-3f2a","name":"shared-folder"},{"id":"c-91bb","name":"Facturas"}]
```

El `id` es estable mientras la colección exista; el `name` es lo que se muestra. El que manda toma
la lista del `hello` que hace justo antes de mandar: si viene vacía, manda directo; si tiene algo,
le pregunta a la persona en cuál dejarlo, con una opción **"Ninguna"** (= destino por defecto del receptor).

### `POST /v1/offer`
Antes de subir un lote. Body:

```json
{"batch":"<uuid>","collection":"c-3f2a","files":[{"name":"IMG_001.jpg","size":123456,"mime":"image/jpeg"}]}
```

`collection` es opcional: sin él (o `null`), va al destino por defecto. Si el id no existe (se borró
la colección en el medio), el receptor usa el destino por defecto; no rechaza el lote.

Respuesta `200 {"accepted":true}` o `200 {"accepted":false}`.
Por defecto el receptor acepta solo. Si el usuario activó "pedir confirmación", el receptor
muestra el pedido y retiene la respuesta hasta 60 s; si no hay respuesta, `accepted:false`.
**El que manda tiene que esperar la respuesta del offer al menos 75 s** (timeout de lectura/request),
porque el receptor puede retenerla 60 s. Ojo con clientes cuyo timeout por defecto es 60 s
(p. ej. `URLRequest.timeoutInterval`): cortarían justo cuando la persona acepta.
Un lote aceptado sigue valiendo 30 min desde el último archivo recibido.

### `POST /v1/files`
Un archivo por request. Cuerpo = bytes crudos del archivo (`Content-Type: application/octet-stream`,
con `Content-Length`). Cabeceras:

| cabecera              | valor                                             |
|-----------------------|---------------------------------------------------|
| `X-Pasala-Batch`      | uuid del lote (el mismo del offer)                |
| `X-Pasala-Index`      | 0-based                                           |
| `X-Pasala-Count`      | total de archivos del lote                        |
| `X-Pasala-Name`       | nombre de archivo, **percent-encoded UTF-8** (espacio = `%20`; un `+` es un `+` literal) |
| `X-Pasala-Mime`       | mime type                                         |
| `X-Pasala-Modified`   | epoch ms (opcional)                               |

El receptor escribe a un archivo temporal y recién al terminar lo mueve al destino.
Si el nombre ya existe: `foto.jpg` → `foto (1).jpg` → `foto (2).jpg`.
Respuesta `200 {"ok":true,"name":"<nombre final>"}`. Si el batch no fue aceptado: `409 {"error":"not_accepted"}`.
Si el cuerpo llegó incompleto: se borra el temporal, no se registra nada.
`Content-Length` es obligatorio (no `Transfer-Encoding: chunked`); sin él: `411 {"error":"length_required"}`.

### `POST /v1/text`
Un texto para copiar en el otro dispositivo (el "portapapeles" entre dispositivos). Body JSON:

```json
{"id":"<uuid>","text":"lo que se copió"}
```

- `text` en UTF-8, hasta 1 MB; si es más largo: `413 {"error":"too_large"}`.
- El receptor **no** lo pone solo en el portapapeles del sistema: lo guarda como un texto recibido
  ("Texto listo para copiar") y la persona lo copia cuando quiere. Se registra en el historial/índice
  con mime `text/x-pasala-snippet`.
- No pasa por "pedir confirmación" (es chico y viene de un dispositivo vinculado).
- Respuesta `200 {"ok":true}`. Un `id` repetido se ignora (`200`), para que reintentar sea seguro.

### `POST /v1/unpair`
Sin body. El receptor borra a ese peer. Respuesta `200 {"ok":true}`.
El que llama también lo borra localmente (aunque el otro no responda).

## Elegir dirección para llegar a un peer

1. La última dirección que funcionó.
2. Lo que se descubrió por mDNS/NSD en la LAN (ver abajo).
3. Las `addrs` guardadas, en orden.

Para cada candidata: `POST /v1/hello` con 2 s de timeout de conexión. La primera que responde gana.

## Descubrimiento en la LAN

Cada nodo anuncia el servicio `_pasala._tcp` (Bonjour en Mac, NSD en Android) en su puerto,
con TXT `id=<id>`. Sirve para encontrar a un peer conocido cuyo IP de LAN cambió.
Nunca empareja por sí solo: solo se habla con peers que ya están en la lista.

## Destino de lo recibido

Si el lote trae una colección válida, va a la carpeta de esa colección. Si no:

- **Mac**: carpeta elegida por el usuario (por defecto `~/Downloads/Pasala`), con opción de una carpeta propia por dispositivo.
- **Android**: MediaStore — imágenes a `Pictures/Pasala`, videos a `Movies/Pasala`, el resto a `Download/Pasala`.

## Seguridad (lo que hay y lo que no)

- Solo hablan nodos que se escanearon. El secret nunca viaja salvo en la respuesta de `/v1/pair`.
- El transporte es HTTP plano. Por Tailscale va cifrado por WireGuard; en la LAN de casa va en claro.
  Siguiente paso posible: TLS con certificado autofirmado cuyo fingerprint viaje en el QR.
