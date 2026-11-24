# Decisiones

<a id="d-001"></a>
## D-001 — 2026-11-23 — Peer-to-peer sobre HTTP, sin servidor central
Cada dispositivo corre un servidor HTTP chico y es cliente de los demás. Llega por la LAN o por
Tailscale. Sin nube: nada para mantener, nada que vea los archivos. Costo: los dos tienen que estar
encendidos y alcanzables al mismo tiempo.

<a id="d-002"></a>
## D-002 — 2026-11-23 — HTTP plano + secret por par; cifrado lo da Tailscale
Por Tailscale el tráfico va cifrado (WireGuard); en la LAN de casa va en claro. Se aceptó para la
primera versión porque solo hablan dispositivos vinculados. Siguiente paso posible: TLS con
certificado autofirmado cuyo fingerprint viaje en el QR.

<a id="d-003"></a>
## D-003 — 2026-11-23 — Vinculación simétrica con QR de un solo uso
El QR lleva direcciones + código de un uso (5 min). Quien escanea pide `/v1/pair` y recibe un secret
que los dos guardan. No importa quién escanea a quién; el resultado es el mismo enlace bidireccional.
El link `pasala://pair?d=…` también abre la app desde la cámara del sistema.

<a id="d-004"></a>
## D-004 — 2026-11-23 — Apps nativas separadas, el protocolo como único contrato
SwiftUI en Mac (isla tipo notch, barra de menú, Quick Look) y Kotlin + Compose en Android (share
target, MediaStore, servicio en primer plano). No se comparte código: lo que tiene que coincidir está en
`docs/PROTOCOL.md`.

<a id="d-005"></a>
## D-005 — 2026-11-23 — Servidores HTTP propios y mínimos
Mac: `HTTPServer` sobre Network.framework. Android: `MiniHttpServer`. Ambos para que el cuerpo vaya
directo a disco en streaming y no sumar dependencias de servidor web. Solo HTTP/1.1, una petición por conexión.

<a id="d-006"></a>
## D-006 — 2026-11-23 — Índice SQLite compartido con el MCP; acciones por la app
La app escribe `pasala.db`; `pasala-mcp` la lee directo (WAL). Lo que tiene efectos (mandar, vincular)
pasa por la ruta local de la app (loopback + token 0600) para que haya un solo lugar que mande cosas.

<a id="d-007"></a>
## D-007 — 2026-11-24 — Colecciones definidas por el receptor
Cada receptor arma sus destinos con nombre (en la Mac, carpetas). Los anuncia en `hello`; el que manda
elige una o "Ninguna". Un id desconocido cae en el destino por defecto, nunca rechaza el lote. Android
todavía no define colecciones.

<a id="d-008"></a>
## D-008 — 2026-11-24 — Marca propia: avión de papel con estela
Ícono y marca dibujados para Pasala (`docs/brand/`), no tomados de librerías de íconos, para no
parecerse a otras apps. Misma geometría en las dos plataformas.

<a id="d-009"></a>
## D-009 — 2026-11-24 — iPhone en Swift compartiendo el núcleo con la Mac
Se evaluó unificar Android + iPhone con Kotlin/Compose Multiplatform. Se descartó: obligaba a reescribir
la app Android que ya anda (red, servidor, MediaStore), la extensión de Compartir de iOS igual va en Swift
y la Mac quedaba como un tercer mundo. En cambio `apple/` es un solo paquete Swift: `PasalaCore` + `PasalaKit`
(motor y vistas de marca) los usan la app de Mac, la de iPhone y su extensión. Quedan dos códigos: Apple y Android.
