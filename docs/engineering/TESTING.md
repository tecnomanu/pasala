# Pruebas

No hay suite unitaria todavía. La verificación es de punta a punta, con la otra app o con una
herramienta que hace de "la otra punta".

## Mac

```bash
cd apple
swift build                                   # compila los tres targets
./build.sh                                    # build/Pasala.app firmada ad hoc
cp -R build/Pasala.app /Applications/ && open /Applications/Pasala.app
```

Celular falso contra la Mac (vincula, manda, espera que la Mac le mande algo):

```bash
PASALA_DEBUG=1 /Applications/Pasala.app/Contents/MacOS/Pasala &      # escribe debug-pair.txt
python3 apple/tools/smoke.py "$(cat ~/Library/Application\ Support/Pasala/debug-pair.txt)" foto.jpg
```

MCP por stdio:

```bash
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"pasala_devices","arguments":{}}}' \
  | /Applications/Pasala.app/Contents/MacOS/pasala-mcp
```

Ver la isla abierta sin pasar el mouse: `PASALA_ISLAND=open`. En inglés: agregar `-AppleLanguages "(en)"` al
ejecutable. Texto por MCP: `pasala_send_text`.

## iPhone

```bash
cd apple/iOS
xcodebuild -project Pasala.xcodeproj -scheme PasalaPhone \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

- Correr: hace falta un runtime de simulador de iOS (`xcodebuild -downloadPlatform iOS`) o un iPhone con
  equipo de firma configurado en el proyecto.
- En el simulador el iPhone comparte la red de la Mac: si la app de Mac usa el 47820, el iPhone toma el
  siguiente puerto libre. Sirve para probar Mac ↔ iPhone y Android ↔ iPhone por la LAN.
- Vincular sin cámara: abrir el link `pasala://pair?d=…` (`xcrun simctl openurl booted '<link>'`).

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
- `fake_peer.py serve --collections "a,b"` anuncia colecciones e imprime la que llega; también imprime los textos
  (`/v1/text`) que recibe. `fake_peer.py text "hola"` (o `text @archivo.txt`, `--id X` para probar repetidos) manda uno.
- Texto desde el celular sin tocar otra app: `adb shell "am start -a android.intent.action.PROCESS_TEXT -t text/plain --es android.intent.extra.PROCESS_TEXT 'hola' -n com.tecnomanu.pasala/.ui.send.TextActivity"`;
  compartir texto: lo mismo con `-a android.intent.action.SEND --es android.intent.extra.TEXT '…' -n …/.ui.send.ShareActivity`.
- Idioma: `adb shell cmd locale set-app-locales com.tecnomanu.pasala --locales en-US` (vacío = el del sistema).
- Con la pantalla apagada: `curl -X POST http://<ip>:47820/v1/hello` tiene que contestar `401` enseguida; para
  simular reposo sin desenchufar: `dumpsys battery unplug` + `dumpsys deviceidle force-idle light` (y `unforce` /
  `battery reset` al terminar). Ver `HISTORY.md#idle-reachability`.
- Contra un emulador, `--connect 127.0.0.1:<puerto>` con `adb forward` (el emulador no es alcanzable por su IP).

<a id="windows-y-linux"></a>
## Windows y Linux

```bash
cd desktop
./gradlew :app:compileKotlin                    # compila (en cualquier sistema)
PASALA_HOME=/tmp/pasala-a PASALA_DEBUG=1 ./gradlew :app:run   # copia aparte; loguea el link de vinculación
```

- Instaladores: jpackage no cruza sistemas. `.deb` y AppImage en Linux x64 (`./gradlew :app:packageDeb
  :app:packageAppImage` + `tools/make-appimage.sh`, necesita `binutils` y `fakeroot`); `.msi` en Windows
  (`gradlew.bat :app:packageMsi`, baja WiX solo).
- Probar sin máquinas propias: contenedores temporales `lscr.io/linuxserver/webtop:ubuntu-xfce` (escritorio XFCE
  en el navegador) y `dockurr/windows` (Windows 11 de evaluación). Para que el celular y la Mac los alcancen
  como a una compu más, darles una IP de la LAN (red `macvlan`; en Windows `DHCP=Y`). Manejar el escritorio
  Linux con `docker exec -u abc -e DISPLAY=:1 … xdotool` y capturas con `xwd`; Windows por su visor web
  (`:8006`) o por SSH si se habilitó en `/oem/install.bat`. Borrar contenedores y volúmenes al terminar.
- Vincular sin cámara: `PASALA_DEBUG=1` escribe el link en la salida; desde la Mac, `pasala_pair` del MCP con ese link;
  desde la compu, ventana Sumar dispositivo → "¿Es otra compu? Pegá acá su código".
- Ruta local (lo que usa el MCP y una segunda ejecución): `curl -H "X-Pasala-Local: $(cat <datos>/local-token)"
  http://127.0.0.1:<puerto>/local/status`; sin el token o desde otra IP tiene que dar `401`.
- MCP: `printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | Pasala --mcp`
  (Linux: `/opt/pasala/bin/Pasala --mcp`; Windows: `"%LOCALAPPDATA%\Pasala\Pasala.exe" --mcp`).
- Isla abierta sin pasar el mouse: `PASALA_ISLAND=open`. Idioma: `LANG=es_AR.UTF-8` (Linux) o el idioma de Windows.

## Camino real (lo que hay que probar según lo que se tocó)

| Si tocaste… | Probá | ¿Alcanza el emulador? |
|---|---|---|
| Protocolo | Mac ↔ Android reales, en los dos sentidos, y cada app contra la herramienta de la otra | No: dispositivos reales |
| Vinculación | QR desde los dos lados; link por la cámara; código vencido o usado → error claro | Solo el link por `adb`; la cámara, en uno real |
| Envío / recepción | Varios archivos, un video grande, nombre repetido (` (1)`), con y sin colección | Sí, con la herramienta de la otra punta; el video grande, en uno real |
| Segundo plano (Android) | Recibir con la pantalla bloqueada y después de actualizar la app sin abrirla | No: los límites de batería cambian por fabricante |
| Isla (Mac) | Pasar el mouse, arrastrar archivos hacia arriba, llegada de archivos, que la barra de menú siga usable | — (Mac) |
| MCP | `pasala_new_files`, `pasala_get_file` con contenido, `pasala_send` a un dispositivo real | — (Mac, Windows, Linux) |
| `kotlin/shared` | Android con `fake_peer.py` **y** escritorio contra la Mac, en los dos sentidos | Android: sí (emulador); escritorio: contenedores |
| Isla / bandeja (Windows, Linux) | Bandeja con su menú, isla que crece al pasar el mouse, soltar archivos, moverla de pantalla | Contenedores sirven; la transparencia en Linux depende del compositor |
| Tailscale | Mandar con el otro dispositivo fuera de la LAN | No |

Lo que no se probó se informa como `UNVERIFIED`.
