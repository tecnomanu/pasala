# Mapa del sistema

Para entender Pasala sin leer todo el código.

## Qué corre

| Proceso | Dónde | Qué hace |
|---|---|---|
| `Pasala.app` | Mac, en la barra de menú (sin Dock) | Servidor en `:47820`, isla arriba al centro, ventanas, índice SQLite, anuncio Bonjour `_pasala._tcp` |
| `pasala-mcp` | Mac, lo lanza el cliente MCP (stdio) | Lee el índice; para mandar/vincular le habla a la app por loopback |
| App Android | Celular | Pantallas + servicio en primer plano que mantiene el servidor en `:47820` y el anuncio NSD |

Cada dispositivo es servidor y cliente a la vez. No hay nube ni servidor central.

## Flujos

**Vincular** (una vez por par de dispositivos)
A muestra QR `pasala://pair?d=…` (IPs LAN + Tailscale, puerto, código de un solo uso, 5 min) →
B lo escanea (o abre el link) → `POST /v1/pair` a A → A responde con un `secret` →
los dos guardan al otro como peer con ese mismo secret. Simétrico: después cualquiera manda.

**Mandar** (A → B)
Resolver dirección de B (última que anduvo → descubierta en la LAN → guardadas; `POST /v1/hello`,
2 s) → el `hello` trae las colecciones de B → si hay, la persona elige una →
`POST /v1/offer` (B acepta solo o pregunta, hasta 60 s) → un `POST /v1/files` por archivo, en streaming →
B escribe a temporal, mueve al destino (colección / carpeta del peer / carpeta general en Mac;
MediaStore en Android) y lo registra.

**Agente (MCP)**
Cliente MCP → `pasala-mcp` (stdio) → lee `pasala.db` (archivos, peers, colecciones; marca "visto por el agente") →
para `pasala_send` / `pasala_pair` → `http://127.0.0.1:<puerto>/local/*` de la app con el token de
`~/Library/Application Support/Pasala/local-token`.

## Estado

| Dónde | Qué |
|---|---|
| Mac: `~/Library/Application Support/Pasala/pasala.db` | `settings`, `peers` (con secret), `files` (índice, con colección), `collections` |
| Mac: `…/Pasala/local-token` (0600) | Token de la ruta local para `pasala-mcp` |
| Android: `filesDir/pasala.json` | Identidad, peers, ajustes, historial |
| Android: MediaStore `Pictures|Movies|Download/Pasala` | Lo recibido |

## Archivos que vale la pena conocer

- `docs/PROTOCOL.md` — el contrato.
- `mac/Sources/Pasala/Engine.swift` — todo el comportamiento de la Mac (servidor, envío, colecciones).
- `mac/Sources/PasalaCore/HTTPServer.swift` — HTTP/1.1 mínimo con cuerpos en streaming.
- `mac/Sources/Pasala/Notch.swift` — la isla (estados, tamaños, arrastrar y soltar).
- `mac/Sources/PasalaMCP/main.swift` — herramientas MCP.
- `android/app/src/main/kotlin/com/tecnomanu/pasala/net/PasalaServer.kt` — rutas del servidor Android.
- `android/…/core/Sender.kt` y `core/Receiver.kt` — mandar y recibir.
- `android/…/service/PasalaService.kt` — mantiene vivo el servidor.
- `android/…/ui/send/SendScreen.kt` — pantalla de compartir (destino, colección, progreso).

## Costuras frágiles

- **Las dos implementaciones del protocolo** pueden divergir: no hay pruebas cruzadas automáticas.
  Las herramientas `mac/tools/smoke.py` y `android/tools/fake_peer.py` hacen de "la otra punta".
- **Permiso de red local en macOS**: firmada ad hoc, cada recompilación puede volver a pedirlo
  ([HISTORY](HISTORY.md#local-network-prompt)).
- **Segundo plano en Android**: depende del servicio en primer plano y del tipo declarado
  ([HISTORY](HISTORY.md#fgs-type)).
- **Isla**: es un panel transparente encima de todo; si deja de ignorar el mouse, bloquea la barra de menú
  ([HISTORY](HISTORY.md#island-mouse)).

## Qué está forzado y qué es convención

| Regla | Forzado por |
|---|---|
| Solo peers vinculados | Código (auth en cada ruta, comparación en tiempo constante) |
| Código de vinculación de un uso y 5 min | Código |
| Ruta local solo desde loopback | Código |
| Nombre de archivo saneado, escritura a temporal | Código |
| Cero datos personales, sin telemetría | Convención + revisión (`AGENTS.md`) |
| Protocolo igual en las dos apps | Convención + smoke/fake peer |
