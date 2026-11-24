# Historia (los porqués que el código no muestra)

## Protocolo

<a id="offer-timeout"></a>
### El que manda espera la respuesta del offer al menos 75 s
**Rule:** timeout de `/v1/offer` ≥ 75 s en los dos clientes (Mac usa 90 s).
**Why:** con "preguntar antes de recibir" el receptor retiene la respuesta hasta 60 s; el timeout por
defecto de `URLRequest` (60 s) cortaba justo cuando la persona aceptaba.
**Where:** `apple/Sources/PasalaKit/Engine.swift` (`send`), cliente Android (`PasalaClient.kt`).

## Mac

<a id="island-mouse"></a>
### La isla ignora el mouse salvo cuando el puntero está encima
**Rule:** el panel de la isla tiene `ignoresMouseEvents = true` y un monitor de mouse lo activa solo
dentro del rectángulo de la isla, durante un arrastre de archivos o con un pedido de confirmación.
**Why:** el panel es grande y transparente, sobre la barra de menú; si captura el mouse, tapa los menús
de las demás apps.
**Where:** `apple/Sources/Pasala/Notch.swift` (`NotchController.updateHover`).

<a id="thumb-clip"></a>
### Las miniaturas llenan un rectángulo y se recortan a él
**Rule:** `Thumb` dibuja un `Rectangle` que toma el tamaño ofrecido y pone la imagen `scaledToFill`
como overlay recortado.
**Why:** con la imagen como contenido directo, un video vertical tomaba su tamaño natural y se salía del
cuadro, tapando el resto de la isla.
**Where:** `apple/Sources/PasalaKit/Theme.swift` (`Thumb`).

<a id="window-size"></a>
### Las ventanas no se ajustan al contenido
**Rule:** `NSHostingView.sizingOptions = []` en las ventanas propias.
**Why:** por defecto SwiftUI agranda la ventana al tamaño ideal del contenido; abrir "Detalles de
conexión" dejaba Ajustes gigante.
**Where:** `apple/Sources/Pasala/Windows.swift` (`show`).

<a id="single-instance"></a>
### Una sola instancia
**Rule:** al arrancar, si ya hay otra con el mismo bundle id, se la activa y esta sale.
**Why:** dos instancias dibujaban dos islas superpuestas y peleaban por el puerto.
**Where:** `apple/Sources/Pasala/PasalaApp.swift` (`applicationDidFinishLaunching`).

<a id="local-network-prompt"></a>
### El permiso de red local puede volver a aparecer
**Rule:** la primera vez macOS pregunta si Pasala puede usar la red local; hay que permitir para mandar por Wi-Fi.
**Why:** el `.app` se firma ad hoc; cada build cambia la firma y macOS puede tratarla como app nueva.
Por Tailscale no hace falta el permiso. Firmar con un certificado estable lo resuelve.
**Where:** `apple/build.sh`.

## Texto entre dispositivos

<a id="clipboard-untouched"></a>
### Lo que llega como texto no se pega solo en el portapapeles
**Rule:** un texto recibido queda como "Texto listo para copiar"; el portapapeles del sistema solo se
toca cuando la persona toca Copiar.
**Why:** la persona suele tener algo copiado que no quiere perder; escribir el portapapeles al llegar lo pisaba.
**Where:** `apple/Sources/PasalaKit/Engine.swift` (`handleText`), receptor Android.

<a id="services-menu"></a>
### "Pasar con Pasala" (texto) y "Enviar con Pasala" (archivos) en la Mac son Servicios de macOS
**Rule:** el `Info.plist` declara `NSServices` (`sendText` para texto, `sendFiles` para archivos en Finder) y la app registra `servicesProvider` al arrancar.
**Why:** es la forma que da macOS de sumar una acción al clic derecho sobre texto seleccionado en cualquier app.
Aparece en el menú contextual (o en Servicios) después de instalar la app en `/Applications`; si no aparece:
`/System/Library/CoreServices/pbs -update` o activarlo en Ajustes del Sistema › Teclado › Atajos › Servicios.
**Where:** `apple/build.sh` (`NSServices`), `apple/Sources/Pasala/PasalaApp.swift` (`PasalaServices`).

## Idiomas

<a id="localization-keys"></a>
### El texto en español es la clave; el inglés es el idioma por defecto
**Rule:** en Mac e iPhone los textos se escriben en español en el código (`Text("…")` o
`String(localized: "…")`); `apple/tools/translations_en.py` tiene el inglés y genera
`apple/Localization/en.lproj/Localizable.strings`. `CFBundleDevelopmentRegion` es `en`.
**Why:** así cualquier idioma que no sea español cae en inglés, y el español no necesita traducción aparte.
Un `String` armado a mano (ternarios, parámetros `title: String`) no se traduce solo: va con `String(localized:)`.
**Where:** `apple/Localization/`, `apple/build.sh`, `apple/iOS/Pasala.xcodeproj` (grupo `../Localization`).

## Mac

<a id="status-item"></a>
### El ícono de la barra es un `NSStatusItem` propio, no `MenuBarExtra`
**Rule:** un clic muestra el menú (después del intervalo de doble clic), doble clic abre la ventana, clic derecho
muestra el menú al instante; el menú se arma de nuevo cada vez.
**Why:** `MenuBarExtra` abre el menú en el primer clic (no hay doble clic) y sus tildes no se refrescaban al
cambiar "Mostrar notch".
La escena SwiftUI que exige la app es un `MenuBarExtra` con `isInserted: false`: una escena `Settings` vacía
aparecía sola al cerrar la ventana de Ajustes, y usar la escena `Settings` para los ajustes desarmaba el diseño.
Mover la isla entre pantallas también va por eventos de AppKit (`NotchController.handle`): un gesto de SwiftUI
no llegaba en el panel de la isla.
**Where:** `apple/Sources/Pasala/PasalaApp.swift` (`StatusBar`).

## iPhone

<a id="ios-foreground"></a>
### El iPhone recibe mientras Pasala está abierta
**Rule:** el servidor del iPhone vive mientras la app está en pantalla; al pasar a segundo plano se pide
tiempo extra solo para terminar lo que está en curso. La app lo dice en Ajustes.
**Why:** iOS suspende las apps en segundo plano y no permite un servidor permanente (no hay equivalente al
servicio en primer plano de Android). Mandar desde el iPhone no tiene esa limitación.
**Where:** `apple/iOS/App/PasalaPhoneApp.swift` (`BackgroundTime`).

<a id="app-group"></a>
### La app y la extensión de Compartir comparten datos por App Group
**Rule:** en iOS la base (identidad, dispositivos, índice) vive en el contenedor del App Group
`group.com.tecnomanu.pasala`; las dos piezas declaran ese grupo.
**Why:** la extensión de Compartir es otro proceso con su propio contenedor; sin el grupo no ve los
dispositivos vinculados. En un iPhone real el grupo necesita un equipo de desarrollo para firmar.
**Where:** `apple/Sources/PasalaCore/Database.swift` (`PasalaPaths`), `apple/iOS/*/…entitlements`.

## Android

<a id="fgs-type"></a>
### Servicio en primer plano tipo `connectedDevice`
**Rule:** el servicio que mantiene el servidor declara `connectedDevice` y pide
`FOREGROUND_SERVICE_CONNECTED_DEVICE` + `CHANGE_NETWORK_STATE`.
**Why:** en Android 14–16 hace falta un tipo; `dataSync` tiene límite diario de tiempo y cortaba la recepción.
**Where:** `android/app/src/main/AndroidManifest.xml`, `service/PasalaService.kt`.

<a id="picker-names"></a>
### Nombres tipo cámara para lo elegido con el selector de fotos
**Rule:** si el `content://` no da un nombre útil, se nombra `IMG_aaaammdd_hhmmss.jpg` / `VID_…`.
**Why:** el selector de fotos del sistema oculta el nombre real y devolvía números (`1000006948.jpg`).
**Where:** `android/…/core/Sender.kt`.

<a id="idle-reachability"></a>
### Seguir visible con la pantalla apagada (optimización de batería)
**Rule:** el servicio en primer plano no alcanza en todas las marcas. Cuando ya hay al menos un dispositivo
vinculado, Home muestra una tarjeta (una sola vez, se puede posponer) que pide sacar a Pasala de la
optimización de batería con `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; Ajustes muestra el estado y el mismo
atajo. No se usa un Wi-Fi lock permanente.
**Why:** un Honor con MagicOS (Android 16) aparecía desconectado en la Mac con la pantalla apagada. En un Samsung
(One UI, Android 16) se midió con `curl -X POST http://<ip>:47820/v1/hello` cada 10–20 s: con pantalla apagada
y con *light idle* forzado (`dumpsys deviceidle force-idle light`, batería "desenchufada") respondió `401` siempre,
en 20–500 ms (un pico de 1,7 s), sin exención: el servicio `connectedDevice` deja a la app fuera del corte de red
de Doze. El *deep idle* no se pudo forzar en ese equipo (lo frena una alarma pendiente de otra app). Por eso el
problema del Honor se atribuye al congelamiento de apps en segundo plano propio de MagicOS ("Inicio de apps" /
optimización de batería), que la exención evita; **no está verificado en el Honor**. No se agregó un Wi-Fi lock de baja
latencia: en el Samsung la respuesta ya era rápida sin él y no hubo nada que mejorar.
**Where:** `android/…/service/Background.kt`, tarjeta en `ui/home/HomeScreen.kt`, fila en `ui/settings/SettingsScreen.kt`,
permiso en `AndroidManifest.xml`.
