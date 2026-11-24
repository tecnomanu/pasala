# AGENTS.md — apple/

Rige todo lo de [`../AGENTS.md`](../AGENTS.md). Esto es lo propio del código Apple (Mac + iPhone).

- Un Swift Package (`Package.swift`, modo Swift 5, macOS 14+ / iOS 17+):
  - `PasalaCore` — SQLite, servidor HTTP sobre Network.framework, utilidades de red. Sin UI.
  - `PasalaKit` — compartido por Mac e iPhone: `Engine` (vincular, mandar, recibir, colecciones) y las
    vistas de marca (`Theme.swift`: colores, `PasalaMark`, `Thumb`, `DeviceGlyph`…). Re-exporta `PasalaCore`.
  - `Pasala` — app de Mac (isla, barra de menú, ventanas). `PasalaMCP` — `pasala-mcp`, solo Mac.
- Lo que sirve a las dos plataformas va en `PasalaKit`, con `#if os(macOS)` / `canImport(UIKit)` solo donde
  hace falta. No duplicar lógica del motor en las apps.
- `Engine` es el único dueño del estado (peers, transferencias, colecciones, servidor). Las vistas leen de ahí.
  `Engine.platform` dice qué es este proceso (`mac` / `ios`); la ruta `/local/*` y el MCP son solo Mac.
- Base: Mac `~/Library/Application Support/Pasala/pasala.db`; iPhone en el App Group
  ([HISTORY](../docs/engineering/HISTORY.md#app-group)). Cambios de esquema: migración aditiva en
  `PasalaDB.migrate()`, nunca borrar columnas.
- Herramientas MCP: nombres `pasala_*`, descripciones en inglés, resultados JSON. Las que escriben o mandan
  pasan por la app, no por la base directo.

## Idiomas
- Texto visible siempre en español en el código: `Text("…")` / `Label("…")` se traducen solos; cualquier `String`
  que llega a la pantalla va con `String(localized: "…")`. Inglés en `tools/translations_en.py` → regenerar
  `Localization/en.lproj/Localizable.strings` ([HISTORY](../docs/engineering/HISTORY.md#localization-keys)).
- Para ver qué falta traducir: compilar con `-Xswiftc -emit-localized-strings -Xswiftc -emit-localized-strings-path -Xswiftc <dir>`
  y comparar las claves con el diccionario.

## Mac
- Sin Xcode project: `./build.sh` arma `build/Pasala.app`, le pone `Info.plist` y la firma ad hoc.
- Ventanas propias (`Windows.swift`) con `sizingOptions = []`.
- Debug: `PASALA_DEBUG=1` escribe el link de vinculación en `debug-pair.txt`; `PASALA_ISLAND=open`
  deja la isla abierta; `--show`, `--pair`, `--settings` abren esas ventanas al arrancar.

## iPhone (`iOS/`)
- `Pasala.xcodeproj` con carpetas sincronizadas: `App/` (app), `Share/` (extensión de Compartir) y
  `Shared/` (vistas que usan las dos, p. ej. `SendPanel`). Un archivo nuevo en esas carpetas entra solo.
- Depende del paquete local `..` (producto `PasalaKit`).
- Recibe solo con la app abierta ([HISTORY](../docs/engineering/HISTORY.md#ios-foreground)); lo recibido va a
  `Documents/` (Archivos › Pasala) y, si la persona quiere, a Fotos.
- Un iPhone real necesita equipo de firma (App Group + extensión); el simulador compila sin firmar.
