# Perfil de guardrails

| Tema | Pasala |
|---|---|
| Lenguajes | Swift 6 toolchain en modo Swift 5 (Mac), Kotlin 2.3 (Android), Python 3 stdlib (herramientas de prueba) |
| Build | Swift Package Manager + `mac/build.sh` (arma y firma ad hoc el `.app`); Gradle wrapper (AGP 9, JDK 21) |
| Superficies | App de barra de menú + isla (Mac), servidor MCP stdio (Mac), app Android con share target y servicio |
| Persistencia | SQLite (Mac), JSON en `filesDir` (Android), MediaStore para lo recibido |
| Red | HTTP/1.1 propio en ambos lados, puerto 47820, Bonjour/NSD `_pasala._tcp`, LAN o Tailscale |
| Auth | Vinculación por QR (código de un uso) → secret compartido por par; `Bearer` + `X-Pasala-From` |
| MCP | `pasala-mcp`: lectura del índice + envío/vinculación vía ruta local loopback con token |
| Segundo plano | Servicio en primer plano `connectedDevice` (Android); app residente (Mac) |
| Pruebas | Sin suite unitaria todavía. Pruebas de punta a punta con `smoke.py` / `fake_peer.py` y dispositivos reales |
| CI / deploy | Ninguno. Builds locales; APK debug instalado por adb |

## Fronteras críticas

1. Rutas `/v1/*` — todo lo que llega es de un peer: auth primero, después validar.
2. `/v1/pair` — la única ruta sin auth; depende del código de un uso.
3. Escritura de lo recibido — nombre saneado, sin salir de la carpeta destino.
4. `/local/*` (Mac) — solo loopback y token; es lo que deja a un agente mandar archivos.
5. Herramientas MCP que mandan (`pasala_send`) — side effect real fuera de la Mac.

## Cambios de alto riesgo (piden plan + revisión independiente + prueba real)

- Cualquier cambio de protocolo.
- Auth, vinculación, manejo del secret.
- Dónde y cómo se escribe lo recibido.
- Servicio en segundo plano, permisos y tipo de servicio en Android.
- Herramientas MCP que escriben o mandan.
- Esquema de la base (migraciones).

## Puertas mínimas antes de dar algo por terminado

- `cd mac && swift build` sin errores; `./build.sh` si se toca la app.
- `cd android && ./gradlew assembleDebug` sin errores.
- Camino real según [TESTING.md](TESTING.md) para lo que se tocó.
- Diff revisado contra la regla de cero datos personales.
