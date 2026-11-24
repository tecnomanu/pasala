# Historia (los porqués que el código no muestra)

## Protocolo

<a id="offer-timeout"></a>
### El que manda espera la respuesta del offer al menos 75 s
**Rule:** timeout de `/v1/offer` ≥ 75 s en los dos clientes (Mac usa 90 s).
**Why:** con "preguntar antes de recibir" el receptor retiene la respuesta hasta 60 s; el timeout por
defecto de `URLRequest` (60 s) cortaba justo cuando la persona aceptaba.
**Where:** `mac/Sources/Pasala/Engine.swift` (`send`), cliente Android (`PasalaClient.kt`).

## Mac

<a id="island-mouse"></a>
### La isla ignora el mouse salvo cuando el puntero está encima
**Rule:** el panel de la isla tiene `ignoresMouseEvents = true` y un monitor de mouse lo activa solo
dentro del rectángulo de la isla, durante un arrastre de archivos o con un pedido de confirmación.
**Why:** el panel es grande y transparente, sobre la barra de menú; si captura el mouse, tapa los menús
de las demás apps.
**Where:** `mac/Sources/Pasala/Notch.swift` (`NotchController.updateHover`).

<a id="thumb-clip"></a>
### Las miniaturas llenan un rectángulo y se recortan a él
**Rule:** `Thumb` dibuja un `Rectangle` que toma el tamaño ofrecido y pone la imagen `scaledToFill`
como overlay recortado.
**Why:** con la imagen como contenido directo, un video vertical tomaba su tamaño natural y se salía del
cuadro, tapando el resto de la isla.
**Where:** `mac/Sources/Pasala/Theme.swift` (`Thumb`).

<a id="window-size"></a>
### Las ventanas no se ajustan al contenido
**Rule:** `NSHostingView.sizingOptions = []` en las ventanas propias.
**Why:** por defecto SwiftUI agranda la ventana al tamaño ideal del contenido; abrir "Detalles de
conexión" dejaba Ajustes gigante.
**Where:** `mac/Sources/Pasala/Windows.swift` (`show`).

<a id="single-instance"></a>
### Una sola instancia
**Rule:** al arrancar, si ya hay otra con el mismo bundle id, se la activa y esta sale.
**Why:** dos instancias dibujaban dos islas superpuestas y peleaban por el puerto.
**Where:** `mac/Sources/Pasala/PasalaApp.swift` (`applicationDidFinishLaunching`).

<a id="local-network-prompt"></a>
### El permiso de red local puede volver a aparecer
**Rule:** la primera vez macOS pregunta si Pasala puede usar la red local; hay que permitir para mandar por Wi-Fi.
**Why:** el `.app` se firma ad hoc; cada build cambia la firma y macOS puede tratarla como app nueva.
Por Tailscale no hace falta el permiso. Firmar con un certificado estable lo resuelve.
**Where:** `mac/build.sh`.

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
