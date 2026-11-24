# AGENTS.md — desktop/

Rige todo lo de [`../AGENTS.md`](../AGENTS.md). Esto es lo propio de la app de Windows y Linux.

- Kotlin + Compose Multiplatform Desktop (JVM 17), una sola app para Windows y Linux. Paquete
  `com.tecnomanu.pasala.desktop`. Versiones en `gradle/libs.versions.toml`; Kotlin, coroutines, serialization y
  okhttp tienen que coincidir con `android/gradle/libs.versions.toml` porque las dos compilan `kotlin/shared`.
- El protocolo **no** se implementa acá: vive en `../kotlin/shared` (modelos, `Store`, cliente, servidor HTTP,
  vincular, mandar, recibir), el mismo código que usa Android ([D-010](../docs/engineering/DECISIONS.md#d-010)).
  Lo propio de escritorio entra por `ReceiverHost` / `SenderHost` (`Desktop.kt`): dónde se escribe, qué se avisa.
  Un cambio de protocolo se hace en `kotlin/shared` y en Swift, no en esta carpeta.
- Capas: `ui/` (isla, ventanas) y `Main.kt` (bandeja) → `Desktop` (motor: servidor, índice, colecciones) →
  `kotlin/shared`. `Ui` guarda qué ventana está abierta; el estado de verdad está en `Store` y `Desktop`.
- Estado en la carpeta de datos (`%APPDATA%\Pasala` / `~/.local/share/pasala`, o `PASALA_HOME`):
  `pasala.json` (identidad, peers, ajustes; el mismo formato que Android), `pasala.db` (índice SQLite con el
  esquema de la Mac, para el MCP), `local-token` (0600), `texts/`.
- Lo recibido va a `~/Downloads/Pasala` (o la carpeta elegida, o la de la colección): a un `.part` en la misma
  carpeta y se mueve al terminar. Nunca afuera de la carpeta destino.
- Una segunda ejecución (`Pasala archivo…`, `--send-text`, `--pair`) le pasa el pedido a la que corre por
  `/local/open` y se cierra. `Pasala --mcp` es el servidor MCP (mismas herramientas que `pasala-mcp` de la Mac).
- Textos: `src/main/composeResources/values/strings.xml` (inglés, por defecto) y `values-es/` (español
  rioplatense), con `plurals`. Sigue el idioma del sistema. Nunca un texto visible escrito en el código.
- Integraciones que escriben fuera de la app (Enviar a, acciones del gestor de archivos, abrir al iniciar sesión)
  solo corren desde una copia instalada (`jpackage.app-path` / `$APPIMAGE`) y solo en la carpeta del usuario.
- Atajo global Ctrl+Alt+P: `RegisterHotKey` (Windows) y `XGrabKey` (X11). En Wayland no hay atajos globales para
  apps: la persona asocia un atajo del sistema a `pasala --send-text`.
- Sin descubrimiento mDNS todavía: se llega a los peers por las direcciones que trae cada `hello`.

## Comandos

```bash
cd desktop
./gradlew :app:run                        # correr (en cualquier sistema; en Mac solo para probar el protocolo)
PASALA_HOME=/tmp/p2 PASALA_DEBUG=1 ./gradlew :app:run   # segunda copia; loguea el link de vinculación
./gradlew :app:packageDeb :app:packageAppImage          # en Linux x64
./gradlew :app:packageMsi                               # en Windows
tools/make-appimage.sh                                  # carpeta AppImage → Pasala-x86_64.AppImage
```

jpackage no cruza sistemas: el `.msi` se arma en Windows y el `.deb` en Linux. Detalle y prueba real en
[`TESTING.md`](../docs/engineering/TESTING.md#windows-y-linux).
