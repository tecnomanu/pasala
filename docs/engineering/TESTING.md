# Pruebas

No hay suite unitaria todavía. La verificación es de punta a punta, con la otra app o con una
herramienta que hace de "la otra punta".

## Mac

```bash
cd mac
swift build                                   # compila los tres targets
./build.sh                                    # build/Pasala.app firmada ad hoc
cp -R build/Pasala.app /Applications/ && open /Applications/Pasala.app
```

Celular falso contra la Mac (vincula, manda, espera que la Mac le mande algo):

```bash
PASALA_DEBUG=1 /Applications/Pasala.app/Contents/MacOS/Pasala &      # escribe debug-pair.txt
python3 mac/tools/smoke.py "$(cat ~/Library/Application\ Support/Pasala/debug-pair.txt)" foto.jpg
```

MCP por stdio:

```bash
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"pasala_devices","arguments":{}}}' \
  | /Applications/Pasala.app/Contents/MacOS/pasala-mcp
```

Ver la isla abierta sin pasar el mouse: `PASALA_ISLAND=open`.

## Android

```bash
cd android
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s Pasala                          # link de vinculación en builds debug
python3 tools/fake_peer.py --help             # pair / send / serve / unpair
```

- Vincular sin cámara: `adb shell am start -a android.intent.action.VIEW -d '<pasala://pair?d=…>'`.
- Compartir hacia la app: `adb shell am start -a android.intent.action.SEND -t image/jpeg --eu android.intent.extra.STREAM <content://…> -n com.tecnomanu.pasala/.ui.send.ShareActivity`
  (con un archivo que la app pueda leer, p. ej. uno que ella misma recibió).
- `fake_peer.py serve --collections "a,b"` anuncia colecciones e imprime la que llega.

## Camino real (lo que hay que probar según lo que se tocó)

| Si tocaste… | Probá |
|---|---|
| Protocolo | Mac ↔ Android reales, en los dos sentidos, y cada app contra la herramienta de la otra |
| Vinculación | QR desde los dos lados; link por la cámara; código vencido o usado → error claro |
| Envío / recepción | Varios archivos, un video grande, nombre repetido (` (1)`), con y sin colección |
| Segundo plano (Android) | Recibir con la pantalla bloqueada y después de actualizar la app sin abrirla |
| Isla (Mac) | Pasar el mouse, arrastrar archivos hacia arriba, llegada de archivos, que la barra de menú siga usable |
| MCP | `pasala_new_files`, `pasala_get_file` con contenido, `pasala_send` a un dispositivo real |
| Tailscale | Mandar con el otro dispositivo fuera de la LAN |

Lo que no se probó se informa como `UNVERIFIED`.
