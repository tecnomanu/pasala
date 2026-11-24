# AGENTS.md — android/

Rige todo lo de [`../AGENTS.md`](../AGENTS.md). Esto es lo propio de la app Android.

- Kotlin + Jetpack Compose (Material 3), minSdk 29, target/compile 36, paquete `com.tecnomanu.pasala`.
  Versiones en `gradle/libs.versions.toml`; JDK 21 para Gradle.
- Capas (dependencia hacia abajo): `ui/` → `core/` → `net/` → `data/`. El protocolo (Models, Store, TextStore,
  MiniHttpServer, PasalaServer, PasalaClient, Resolver, Addresses, Pairing, Sender, Receiver, Live) vive en
  `../kotlin/shared` con los mismos paquetes y lo comparte la app de escritorio ([D-010](../docs/engineering/DECISIONS.md#d-010)).
  Lo propio de Android lo conecta `core/AndroidPlatform.kt` (MediaStore, notificaciones, `content://`, Radio).
  Acá quedan Discovery (NSD), Radio, Clip, Format, `service/` (servicio en primer plano tipo `connectedDevice`) y la UI.
  Un cambio en `kotlin/shared` se compila y prueba también en `desktop/`.
- `Store` (un JSON en `filesDir`) es la única fuente de identidad, peers, ajustes e historial.
- Lo recibido va por MediaStore (`Pictures/Pasala`, `Movies/Pasala`, `Download/Pasala`), escrito
  como pendiente y publicado al terminar. Nunca rutas de archivo directas.
- Los archivos a mandar se leen en streaming desde el `content://`; nunca cargar un video entero en memoria.
- Textos entre dispositivos (`/v1/text`): el cuerpo completo va a `filesDir/texts/<id>.txt` (`data/TextStore.kt`) y el
  historial guarda solo un resumen. Nunca se escribe el portapapeles sin que la persona toque "Copiar", y solo se lee
  con Pasala al frente: `TextActivity` lo lee recién cuando su ventana tiene foco, y se abre desde el botón de Home
  (visible solo si hay texto copiado, mirando la descripción del portapapeles, nunca el contenido), el mosaico de
  ajustes rápidos, la acción de la notificación "Lista para recibir", el atajo del ícono y el widget.
- Android no define colecciones (por ahora): ignora `collection` al recibir y responde `"collections":[]`.
- Marca: `res/drawable/ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, `ic_stat_mark.xml`,
  `ic_mark.xml`, generados desde `docs/brand/pasala-mark.svg`. Fuente Plus Jakarta Sans (OFL, ver
  `third_party/plus-jakarta-sans/OFL.txt`).
- Textos: todos en `res/values/strings.xml` (inglés, por defecto) y `res/values-es/strings.xml` (español
  rioplatense); cantidades con `plurals`, tiempos relativos con `DateUtils` (`core/Format.kt`). Idioma por app
  declarado en `res/xml/locales_config.xml`. Nunca un texto visible escrito en el código.
- Debug: el build debug loguea el link de vinculación con tag `Pasala`;
  `am start -n com.tecnomanu.pasala/.ui.MainActivity --es screen show_code` abre la pantalla del código.
