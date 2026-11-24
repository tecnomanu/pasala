# AGENTS.md — android/

Rige todo lo de [`../AGENTS.md`](../AGENTS.md). Esto es lo propio de la app Android.

- Kotlin + Jetpack Compose (Material 3), minSdk 29, target/compile 36, paquete `com.tecnomanu.pasala`.
  Versiones en `gradle/libs.versions.toml`; JDK 21 para Gradle.
- Capas (dependencia hacia abajo): `ui/` → `core/` (Pairing, Sender, Receiver) → `net/`
  (MiniHttpServer, PasalaServer, PasalaClient, Resolver, Discovery, Addresses) → `data/` (Store, Models).
  `service/` mantiene vivo el servidor (servicio en primer plano tipo `connectedDevice`).
- `Store` (un JSON en `filesDir`) es la única fuente de identidad, peers, ajustes e historial.
- Lo recibido va por MediaStore (`Pictures/Pasala`, `Movies/Pasala`, `Download/Pasala`), escrito
  como pendiente y publicado al terminar. Nunca rutas de archivo directas.
- Los archivos a mandar se leen en streaming desde el `content://`; nunca cargar un video entero en memoria.
- Android no define colecciones (por ahora): ignora `collection` al recibir y responde `"collections":[]`.
- Marca: `res/drawable/ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, `ic_stat_mark.xml`,
  `ic_mark.xml`, generados desde `docs/brand/pasala-mark.svg`. Fuente Plus Jakarta Sans (OFL, ver
  `third_party/plus-jakarta-sans/OFL.txt`).
- Debug: el build debug loguea el link de vinculación con tag `Pasala`;
  `am start -n com.tecnomanu.pasala/.ui.MainActivity --es screen show_code` abre la pantalla del código.
