<p align="center"><img src="docs/brand/pasala-icon.svg" width="112" alt="Pasala"></p>

# Pasala

Pasá archivos entre tus dispositivos: del celu a la Mac, de la Mac al celu, de un Android a otro.
Se vinculan una vez escaneando un QR y después cualquiera le manda a cualquiera, por el Wi-Fi de casa
o por [Tailscale](https://tailscale.com) desde afuera. Sin nube, sin cuentas, sin telemetría.

*Share files between your own Mac and Android devices, peer-to-peer over LAN or Tailscale. Pair once with a QR code. No cloud, no accounts, no telemetry. Includes an MCP server so AI agents can see what arrived.*

## Qué hace

**Mac** — app de barra de menú con una isla arriba al centro (estilo notch):
- Pasás el mouse y ves tus dispositivos y lo último que llegó; doble clic abre la ventana.
- Cuando llegan archivos, la isla se despliega y las miniaturas caen adentro con su progreso.
- Arrastrás archivos hacia la isla y los soltás sobre un dispositivo para mandarlos.
- Ventana con el mazo de lo recibido y lo enviado, agrupado por día.
- **Colecciones**: carpetas con nombre que tus otros dispositivos pueden elegir al mandarte algo.

**Android** — aparece en *Compartir* para cualquier archivo:
- Elegís un destino (y una colección si ese destino tiene) y listo.
- Recibe en segundo plano; fotos y videos aparecen en la galería (`Pictures/Pasala`, `Movies/Pasala`).
- Vincula con otro Android o con la Mac: cualquiera de los dos escanea al otro.

**Agentes (MCP)** — `pasala-mcp` deja que Claude u otro agente consulte el índice de lo recibido
(qué llegó, de quién, cuándo, dónde quedó), vea una imagen y mande archivos a un dispositivo.

## Estructura

```
docs/PROTOCOL.md     contrato entre apps (QR, rutas, cabeceras, colecciones)
mac/                 app de Mac (SwiftUI) + pasala-mcp
android/             app Android (Kotlin + Jetpack Compose)
docs/engineering/    mapa del sistema, decisiones, pruebas, flujo de cambios
```

## Mac

Requiere macOS 14+ y Xcode (o las Command Line Tools con Swift 6).

```bash
cd mac && ./build.sh
cp -R build/Pasala.app /Applications/ && open /Applications/Pasala.app
```

La app se firma ad hoc: la primera vez macOS pregunta si puede usar la red local (hace falta para
mandar por Wi-Fi). Lo recibido va a `~/Downloads/Pasala` (se cambia en Ajustes, por dispositivo o por colección).

### MCP

```bash
claude mcp add --scope user pasala /Applications/Pasala.app/Contents/MacOS/pasala-mcp
```

| herramienta | qué hace |
|---|---|
| `pasala_new_files` | lo recibido que el agente todavía no vio (y lo marca visto) |
| `pasala_list_files` | lista con filtros: `direction`, `since`, `device`, `kind`, `collection`, `only_new` |
| `pasala_search` | busca por nombre |
| `pasala_get_file` | metadatos; con `include_content` devuelve la imagen o el texto |
| `pasala_mark_seen` | marca como vistos |
| `pasala_devices` | dispositivos, si están conectados, colecciones y carpeta de recibidos |
| `pasala_send` | manda archivos de la Mac a un dispositivo (opcional: a una de sus colecciones) |
| `pasala_pair` | vincula con el link `pasala://pair?d=…` de otro dispositivo |

## Android

Requiere JDK 21 y el Android SDK (API 36).

```bash
cd android
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Seguridad

- Solo hablan dispositivos que se vincularon escaneando el QR (código de un solo uso, vence en 5 minutos);
  cada par comparte un secreto propio.
- Por Tailscale el tráfico va cifrado. En la red local va sin cifrar (por ahora): usalo en redes en las que confiás.
- La app no manda nada a ningún servicio externo.

## Licencia

[MIT](LICENSE). La fuente Plus Jakarta Sans se distribuye bajo la
[SIL Open Font License](android/third_party/plus-jakarta-sans/OFL.txt).
