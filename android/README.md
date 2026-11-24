# Pasala — Android

Kotlin + Jetpack Compose (Material 3), minSdk 29, target 36, paquete `com.tecnomanu.pasala`.

```bash
cd android
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- Aparece en **Compartir** para cualquier archivo (uno o varios); elegís un dispositivo y "Enviar a …".
- Recibe en segundo plano (servicio en primer plano "Lista para recibir"); guarda en `Pictures/Pasala`,
  `Movies/Pasala` y `Download/Pasala`.
- **Sumar dispositivo**: mostrar mi código / escanear código. También abre enlaces `pasala://pair?d=…`
  (la cámara del sistema leyendo el QR, o `adb shell am start -a android.intent.action.VIEW -d '<link>'`).
- Debug: el build debug loguea el código de vinculación con tag `Pasala`;
  `am start -n com.tecnomanu.pasala/.ui.MainActivity --es screen show_code` abre la pantalla del código.
- `tools/fake_peer.py --help`: dispositivo de prueba para vincular, mandar y recibir sin otra app.

Código en `app/src/main/kotlin/com/tecnomanu/pasala/`: `net/` (servidor, cliente, direcciones, descubrimiento),
`core/` (vincular, recibir, mandar), `service/`, `ui/`, `data/`.
