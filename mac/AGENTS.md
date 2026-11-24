# AGENTS.md — mac/

Rige todo lo de [`../AGENTS.md`](../AGENTS.md). Esto es lo propio de la app de Mac.

- Swift Package (`Package.swift`), modo de lenguaje Swift 5, macOS 14+. Sin Xcode project:
  `./build.sh` arma `build/Pasala.app`, le pone `Info.plist` y la firma ad hoc.
- Targets: `PasalaCore` (SQLite, servidor HTTP sobre Network.framework, utilidades de red),
  `Pasala` (la app), `PasalaMCP` (`pasala-mcp`, servidor MCP por stdio).
- `Engine` (`Sources/Pasala/Engine.swift`) es el único dueño del estado: peers, transferencias,
  colecciones, servidor. Las vistas leen de ahí; no abrir otra conexión a la base desde la UI.
- La base (`~/Library/Application Support/Pasala/pasala.db`) la escribe la app; `pasala-mcp` la lee
  y para mandar o vincular usa la ruta local `/local/*` de la app (solo loopback + token en archivo 0600).
  Cambios de esquema: migración aditiva en `PasalaDB.migrate()`, nunca borrar columnas.
- Herramientas MCP: nombres `pasala_*`, descripciones en inglés para el agente, resultados JSON.
  Una herramienta nueva que escribe o manda algo pasa por la app, no por la base directo.
- UI: componentes y colores en `Theme.swift`; la marca se dibuja con `MarkGeometry`
  (misma geometría que `docs/brand/pasala-mark.svg`).
- Ventanas propias (`Windows.swift`) con `sizingOptions = []`: el contenido no cambia el tamaño de la ventana.
- Debug: `PASALA_DEBUG=1` escribe el link de vinculación en `debug-pair.txt`; `PASALA_ISLAND=open`
  deja la isla abierta; `--show`, `--pair`, `--settings` abren esas ventanas al arrancar.
