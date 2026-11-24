# Installing Pasala — guide for AI agents

You are an AI agent (Claude Code, Codex, Cursor, …) helping a person install **Pasala** on their own devices.
Pasala passes files and text between a person's own computers and phones, peer-to-peer, over their home network
or Tailscale. There is no server, no account and no telemetry. This guide tells you what to check, what to
propose, and the exact steps for each platform.

**Talk to the person in their language.** They may not be technical: explain each step in plain words, say what
will happen before it happens, and let them do the steps that only they can do (taps on the phone, system
permission prompts, passwords, Apple ID).

## 0. Ground rules

- **Propose first, then act.** Before installing toolchains, downloading anything, changing system settings or
  touching a phone, show the plan and get a clear yes.
- **The phone is theirs.** Developer options, USB debugging, "trust this computer", installing from unknown
  sources, battery and autostart permissions: you explain, they tap. Never uninstall an existing Pasala (it holds
  their pairings): update in place.
- **Building happens on their computer, from this repository.** Clone it if needed:
  `git clone https://github.com/tecnomanu/pasala && cd pasala`.
- **Keep secrets and personal data out of logs, commits and messages.** Pairing links (`pasala://pair?d=…`) are
  one-use codes; don't paste them anywhere public.

## 1. Find out what they have

Ask (or detect what you can) and summarize back:

| Question | Why it matters |
|---|---|
| Which computer(s)? macOS, Windows or Linux (and which desktop, Wayland or X11) | Decides the app and how it's built |
| Which phone(s)? Android (brand/model) or iPhone | Android installs from an APK; iPhone needs a Mac with Xcode |
| Same Wi-Fi at home? Do they use Tailscale? | Pasala works on the LAN, and over Tailscale from outside |
| Can the phone be connected by USB to the computer? | The fastest way to install on a phone |
| Do they want an agent to read what arrives? | Then you also set up the MCP server (step 5) |

Detect toolchains before proposing installs:

```bash
# macOS
xcodebuild -version; swift --version
# Android builds (any OS)
java -version; echo "$ANDROID_HOME"; which adb sdkmanager
# Windows / Linux desktop builds
java -version            # JDK 17+ (21 recommended)
```

Then propose a short plan, e.g. "1) build and install Pasala on your Mac, 2) install it on your Android by USB,
3) pair them with a QR, 4) send a test photo". Wait for the yes.

## 2. The computer

### macOS (14 or newer)

Needs Xcode or the Command Line Tools with Swift 6.

```bash
cd apple && ./build.sh
cp -R build/Pasala.app /Applications/ && open /Applications/Pasala.app
```

Tell the person what they'll see:
- A paper-plane icon in the menu bar and a small black "island" at the top center of the screen.
- macOS asks **"Allow Pasala to find devices on local networks?"** → they must click **Allow** (needed for Wi-Fi;
  Tailscale works without it). The app is signed ad hoc, so a rebuild can ask again.
- Optional: menu bar icon → *Abrir al iniciar sesión* / *Open at Login*.
- Text and Finder integration ("Pasar con Pasala" / "Enviar con Pasala" in right-click menus) are macOS Services;
  if they don't show up, they're under *Services*, or enable them in System Settings › Keyboard › Keyboard Shortcuts › Services.

### Windows 10/11

The `.msi` must be built on Windows (jpackage doesn't cross-build). Needs a JDK 17+ (21 recommended).

```bat
cd desktop
gradlew.bat :app:packageMsi
```

Run the `.msi` from `desktop\app\build\compose\binaries\main\msi\`. Tell them:
- Windows Firewall asks on first run → allow on **private networks**.
- Pasala lives in the tray (near the clock) and shows an island at the top center.
- Right-click files → *Send to* › *Pasala*. **Ctrl+Alt+P** sends the copied text.

### Linux (x64)

Needs a JDK 17+ and, for packaging, `dpkg`/`fakeroot`.

```bash
cd desktop
./gradlew :app:packageDeb :app:packageAppImage && tools/make-appimage.sh
sudo apt install ./app/build/compose/binaries/main/deb/*.deb      # Debian/Ubuntu
# or run the AppImage: tools/make-appimage.sh prints its path (Pasala-x86_64.AppImage)
```

Tell them: tray icon + island; **Ctrl+Alt+P** sends the copied text on X11 — on **Wayland** there are no global
shortcuts for apps, so they bind a system shortcut to `pasala --send-text`. File-manager entries exist for
Thunar, Nautilus, Nemo and Dolphin.

## 3. The phone

### Android (10 or newer)

Build the APK on the computer (JDK 21 + Android SDK API 36):

```bash
cd android
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
```

**Option A — USB (recommended).** Walk them through it:
1. On the phone: *Settings › About phone* → tap **Build number** 7 times (developer options unlock).
2. *Settings › System › Developer options* → turn on **USB debugging**. (Names vary by brand: Samsung, Xiaomi,
   Honor/Huawei put it in slightly different places — search "USB debugging" in Settings.)
3. Connect the cable; on the phone accept **"Allow USB debugging?"** (tick "always allow from this computer").
4. Check and install:
   ```bash
   adb devices                                   # the phone must show as "device", not "unauthorized"
   adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   Always pass `-s <serial>` if more than one device or emulator is connected. `install -r` updates in place.

**Option B — no cable.** Get `app-debug.apk` onto the phone any way they like (their own cloud drive, chat with
themselves…; once a computer is paired, Pasala itself can send it). On the phone, open the file; Android asks to
allow installing from that app → allow → Install.

After installing, on the phone:
- Open Pasala and **allow notifications**.
- Accept the card that asks to let Pasala **run in the background** (battery optimization). Without it some
  phones stop receiving with the screen off.
- On Honor/Huawei, Xiaomi, Oppo/Realme and similar: Pasala's Settings shows an **"Inicio automático" / autostart**
  row → open it and allow auto-launch, secondary launch and background activity. Otherwise the phone won't
  restart Pasala after a reboot or an update.
- Optional quick ways to send copied text: the action on the "Ready to receive" notification, the quick-settings
  tile, long-press the app icon, or the home-screen widget.

### iPhone (iOS 17 or newer)

Requires **a Mac with Xcode** and the iPhone connected by USB (or on the same network after the first run).

1. Open `apple/iOS/Pasala.xcodeproj`.
2. For **both** targets (`PasalaPhone` and `PasalaShare`): *Signing & Capabilities* → choose their **Team**
   (a free Apple ID works; apps then expire after 7 days and must be rebuilt).
3. If Xcode says the bundle identifier is taken, give both targets their own prefix (e.g. `com.<name>.pasala` and
   `com.<name>.pasala.share`) and change the **App Group** to match in three places: both `.entitlements` files
   and `PasalaPaths.appGroup` in `apple/Sources/PasalaCore/Database.swift`.
4. On the iPhone: connect, tap **Trust this computer**, and enable **Developer Mode**
   (*Settings › Privacy & Security › Developer Mode*, the phone restarts).
5. In Xcode pick the iPhone as destination and Run (⌘R). First launch may need *Settings › General › VPN &
   Device Management* → trust the developer.

Tell them: the iPhone **receives only while Pasala is open** (iOS doesn't allow background servers); sending
works any time, including from the share sheet. Received files are in *Files › On My iPhone › Pasala*.

## 4. Pair the devices

Pairing is once per pair of devices and works both ways afterwards.

- Computer ↔ phone: on the computer open *Add device* / *Sumar dispositivo* (menu bar or tray icon) — it shows a
  QR. On the phone: Pasala › *Add device* › *Scan code* (or the iPhone's Camera app, then tap the Pasala banner).
- Computer ↔ computer: on one, copy its code (`pasala://pair?d=…`); on the other paste it in *Add device*.
- An agent with the MCP server can pair with `pasala_pair` given that link.

Then **test both ways**: send a photo from the phone (share sheet → Pasala) and a file from the computer
(drag it onto the island). Received files go to `~/Downloads/Pasala` on computers.

## 5. Connect yourself (MCP)

So you (or another agent) can see what arrived and send files or text:

| Computer | Command |
|---|---|
| macOS | `/Applications/Pasala.app/Contents/MacOS/pasala-mcp` |
| Windows / Linux | the installed Pasala executable with `--mcp` (find it with `where Pasala` / `which pasala`; jpackage usually installs under `C:\Program Files\Pasala\` or `/opt/pasala/bin/`) |

Claude Code example:

```bash
claude mcp add --scope user pasala /Applications/Pasala.app/Contents/MacOS/pasala-mcp
```

Tools: `pasala_new_files`, `pasala_list_files`, `pasala_search`, `pasala_get_file`, `pasala_mark_seen`,
`pasala_devices`, `pasala_send`, `pasala_send_text`, `pasala_pair`. Sending needs the Pasala app running.

## 6. If something doesn't connect

| Symptom | Check |
|---|---|
| Device shows offline | Same Wi-Fi or both on Tailscale; Pasala open on that device |
| Mac can't reach the phone on Wi-Fi | System Settings › Privacy & Security › Local Network → Pasala on |
| Windows can't receive | Firewall rule for Pasala on private networks |
| Android offline with the screen off / after reboot | Background (battery) permission and vendor autostart (step 3) |
| iPhone doesn't receive | It only receives with Pasala open |
| "Code expired" while pairing | Codes last 5 minutes and work once: generate a new one |

More detail for contributors: [`AGENTS.md`](AGENTS.md), [`docs/PROTOCOL.md`](docs/PROTOCOL.md),
[`docs/engineering/TESTING.md`](docs/engineering/TESTING.md).
