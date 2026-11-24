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

<a id="idle-wakeups"></a>
### En reposo no hay timers, locks ni búsquedas; cada `hello` cuesta lo mínimo
**Rule:** con la pantalla apagada el proceso solo tiene el socket escuchando y el anuncio NSD: sin alarmas, jobs,
wake locks ni Wi-Fi locks (esos se toman solo durante una transferencia, `core/Radio.kt`), sin búsqueda NSD (solo
con la app en pantalla) y sin `hello` periódicos (solo con Home visible). Un `hello` entrante actualiza el peer en
memoria y escribe el archivo de estado solo si cambió algo o pasaron 10 min (`Store.seen`). El anuncio NSD se
rehace solo si cambió lo anunciado (nombre, puerto, direcciones).
**Why:** MagicOS marcó a Pasala como "se activa frecuentemente en segundo plano". Medido en ese teléfono, 6 min con
la pantalla apagada (`dumpsys batterystats` tras `--reset`): 0 alarmas, 0 jobs, 0 wake locks; 262 paquetes
recibidos y 260 enviados (~2600/h) y ~0,8 s de CPU, todo por conexiones entrantes: la Mac hacía `hello` cada 20 s
a cada dirección (LAN y Tailscale). Cada uno además reescribía el JSON de estado. El protocolo ahora pide no hacer
`hello` en bucle sin pantalla visible (`PROTOCOL.md`, "Cada cuánto"); el lado que pregunta es el que tiene que cambiar.
Mac: cada 20 s solo con la isla abierta o una ventana visible; si no, cada 10 min; y prueba primero la última
dirección sola en vez de todas a la vez (de ~2600 paquetes/h a unas decenas).
**Where:** `kotlin/shared/…/data/Store.kt` (`seen`), `kotlin/shared/…/net/PasalaServer.kt`, `android/…/service/PasalaService.kt` (`scheduleReannounce`);
`apple/Sources/PasalaKit/Engine.swift` (`tick`, `resolve`).

<a id="survive-swipe"></a>
### Sacar a Pasala de recientes no la apaga
**Rule:** el servicio declara `stopWithTask="false"` y en `onTaskRemoved` vuelve a afirmarse en primer plano;
`onStartCommand` devuelve `START_STICKY`.
**Why:** al deslizar la app desde recientes parecía desaparecer; el servidor tiene que seguir escuchando.
**Where:** `android/app/src/main/AndroidManifest.xml`, `service/PasalaService.kt`.

<a id="vendor-autostart"></a>
### En Honor el arranque solo depende de "Inicio de apps"
**Rule:** Ajustes muestra "Inicio automático" con un atajo a la pantalla del fabricante cuando existe
(`systemmanager://com.hihonor.systemmanager/appcontrol`). No se usa `LOCKED_BOOT_COMPLETED`.
**Why:** en MagicOS, sin ese permiso, la app no recibe `BOOT_COMPLETED` ni `MY_PACKAGE_REPLACED`: después de
reiniciar o actualizar queda apagada hasta que se abre (se vio al actualizar por `adb`). `LOCKED_BOOT_COMPLETED` no
sirve: identidad y dispositivos vinculados viven en el almacenamiento que se abre recién al desbloquear.
**Where:** `android/…/service/Background.kt` (`vendorLaunchSettings`), `ui/settings/SettingsScreen.kt`, `<queries>` en el manifest.

## Windows y Linux

<a id="desktop-island-window"></a>
### La isla es un diálogo sin dueño y se mueve y redimensiona en un solo `setBounds`
**Rule:** la isla es un `ComposeDialog` sin dueño, sin borde, siempre arriba y sin foco; cada cambio de modo
le da posición y tamaño juntos con `setBounds`.
**Why:** como ventana normal aparecía en la barra de tareas (en X11 el tipo `UTILITY` no se respeta para
frames); y si se le cambiaba tamaño y posición por separado, el gestor de ventanas de XFCE aplicaba la
posición y descartaba el tamaño, y la isla quedaba como un rectángulo negro vacío.
**Where:** `desktop/app/…/ui/Island.kt` (`Island`, `islandBounds`).

<a id="desktop-drop"></a>
### Soltar archivos pasa por el modificador de Compose, no por un `DropTarget` de AWT
**Rule:** las ventanas reciben archivos con `Modifier.fileDrop` (`dragAndDropTarget`).
**Why:** Compose ya pone su propio `DropTarget` en el lienzo de la ventana; uno de AWT en la ventana nunca
recibe el arrastre (probado con Thunar).
**Where:** `desktop/app/…/ui/Drops.kt`.
