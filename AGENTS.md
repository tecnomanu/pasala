# AGENTS.md — Pasala

Pasala pasa archivos entre los dispositivos propios de una persona (Mac, iPhone, Android, Windows y Linux, en cualquier combinación),
por la red local o por Tailscale. Los dispositivos se vinculan una vez escaneando un QR; después
es peer-to-peer, sin servidor central. La Mac y las compus con Windows o Linux además exponen un servidor MCP con el índice de lo recibido.

## Mapa rápido

| Carpeta | Qué es | Reglas propias |
|---|---|---|
| `docs/PROTOCOL.md` | **El contrato** entre apps: QR, rutas, cabeceras, colecciones | — |
| `apple/` | Paquete Swift: núcleo compartido (`PasalaKit`), app de Mac (isla tipo notch + barra de menú), `pasala-mcp`, y en `apple/iOS/` la app de iPhone con su extensión de Compartir | [`apple/AGENTS.md`](apple/AGENTS.md) |
| `kotlin/shared/` | Núcleo Kotlin del protocolo (modelos, servidor, cliente, vincular, mandar, recibir) que usan Android y escritorio | este archivo + [D-010](docs/engineering/DECISIONS.md#d-010) |
| `android/` | App Android (Kotlin + Jetpack Compose) | [`android/AGENTS.md`](android/AGENTS.md) |
| `desktop/` | App de Windows y Linux (Kotlin + Compose Desktop): bandeja, isla, ventanas, MCP | [`desktop/AGENTS.md`](desktop/AGENTS.md) |
| `docs/brand/` | Ícono y marca (SVG fuente) | — |
| `docs/engineering/` | Cómo está hecho y cómo se cambia | ver abajo |

Hay **dos códigos**: Apple (Swift; Mac e iPhone comparten `PasalaCore` + `PasalaKit`) y Kotlin (Android, Windows y
Linux comparten `kotlin/shared`). Entre los dos, lo único compartido es `docs/PROTOCOL.md`. Ver
[D-009](docs/engineering/DECISIONS.md#d-009) y [D-010](docs/engineering/DECISIONS.md#d-010).

Docs de ingeniería: [perfil](docs/engineering/GUARDRAILS_PROFILE.md) ·
[mapa del sistema](docs/engineering/SYSTEM_MAP.md) · [decisiones](docs/engineering/DECISIONS.md) ·
[historia](docs/engineering/HISTORY.md) · [pruebas](docs/engineering/TESTING.md) ·
[flujo de cambios](docs/engineering/WORKFLOW.md).

## Reglas de este proyecto

1. **Protocolo primero.** Todo cambio en lo que viaja entre dispositivos (rutas, cabeceras, JSON, QR,
   colecciones) se escribe primero en `docs/PROTOCOL.md` y se implementa en **los dos** códigos (Apple y `kotlin/shared`)
   en el mismo cambio. Un campo nuevo es opcional y el receptor viejo lo ignora; nunca se rompe a un peer
   con la versión anterior sin subir `v`.
2. **Cero datos personales en el repo.** Ni nombres reales, ni IPs reales (LAN o Tailscale), ni números
   de serie de dispositivos, ni rutas de una máquina (`/Users/…`, `/Volumes/…`), ni mails, ni capturas
   con información de quien las sacó. En ejemplos: `192.168.1.20`, `100.101.102.103`, "MacBook", "Pixel 9".
   Capturas para docs: dispositivos con nombres neutros y escritorio limpio.
3. **La app no manda nada a terceros.** Sin telemetría, analytics, crash reporters ni llamadas a
   servicios externos. Los archivos y nombres solo viajan entre dispositivos vinculados. Cualquier
   excepción es una decisión nueva en `DECISIONS.md` y se habla antes.
4. **Confianza solo por vinculación.** Nada responde sin `X-Pasala-From` + `Bearer` de un peer
   vinculado (salvo `/v1/pair` con código de un solo uso). El descubrimiento en la LAN nunca vincula.
   Lo que llega de un peer (nombres de archivo, JSON) es entrada no confiable: sanitizar nombres,
   escribir a temporal y mover al terminar, nunca fuera de la carpeta destino.
5. **Textos de la UI para la persona que la usa** (español rioplatense): qué puede hacer y qué pasa.
   Nunca nombres internos (HTTP, offer, batch, secret, token, NSD, MCP, peer) ni caveats técnicos.
6. **Diseño compartido.** Paleta y marca iguales en todas las apps: tinta `#0E0F1A`, superficie `#181A2A`,
   degradé `#FF6B3D → #7C5CFF`, menta `#3DFFB0`; ícono = `docs/brand/pasala-icon.svg`. Si cambia la
   marca, cambia en todas.
7. **Sin dependencias casuales.** Mac: solo frameworks del sistema (SwiftUI, Network, SQLite3,
   QuickLook). Android: las del catálogo `android/gradle/libs.versions.toml`. Escritorio: las de
   `desktop/gradle/libs.versions.toml` (Compose Desktop, sqlite-jdbc, zxing, JNA). Una nueva necesita decir
   por qué no alcanza lo que hay.
8. La arquitectura existente gana sobre patrones genéricos. Sin refactors ajenos a la tarea, sin
   abstracciones especulativas. Buscar la operación existente antes de crear otra.
9. Nunca debilitar chequeos (auth del peer, límites de tamaño, validaciones) para que algo "ande".
10. Un arreglo de bug suma una prueba que lo reproduzca cuando sea posible (smoke/fake peer).
11. Cambios de red, servicio en segundo plano o permisos se verifican en el camino real
    (dispositivo o emulador + la otra app), no solo compilando. Ver `TESTING.md`.
12. Revisión independiente (contexto nuevo) para cambios no triviales; la auto-revisión no cuenta.

## Comentarios e historia

- Un comentario: 1–2 líneas, solo el *por qué* que no es obvio.
- Nada de historia en el código ("antes…", "lo cambiamos porque…", incidentes, fechas). Eso va a
  `docs/engineering/HISTORY.md` o `DECISIONS.md`, y el comentario apunta al ancla:
  `// See HISTORY.md#offer-timeout`.
- Sin metadatos de herramientas de IA (modelos, "generated by") en código, docs ni commits.

## Comandos

```bash
# Mac
cd apple && ./build.sh                      # build/Pasala.app (incluye pasala-mcp)
# iPhone (app + extensión de Compartir)
xcodebuild -project apple/iOS/Pasala.xcodeproj -scheme PasalaPhone -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
python3 apple/tools/smoke.py <link> <archivos>   # celular falso contra la Mac

# Android
cd android && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew assembleDebug
python3 android/tools/fake_peer.py --help       # Mac falsa contra el celular

# Windows / Linux (el .msi se arma en Windows, el .deb y el AppImage en Linux)
cd desktop && ./gradlew :app:run
cd desktop && ./gradlew :app:packageDeb :app:packageAppImage && tools/make-appimage.sh
cd desktop && gradlew.bat :app:packageMsi
```

Detalle y verificación real: [`docs/engineering/TESTING.md`](docs/engineering/TESTING.md).
