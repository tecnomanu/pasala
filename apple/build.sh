#!/bin/bash
# Builds Pasala.app (menu bar + notch app) with the MCP binary inside.
set -euo pipefail
cd "$(dirname "$0")"
CONFIG=${CONFIG:-release}
swift build -c "$CONFIG"
BIN=$(swift build -c "$CONFIG" --show-bin-path)
APP=build/Pasala.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN/Pasala" "$APP/Contents/MacOS/Pasala"
cp "$BIN/pasala-mcp" "$APP/Contents/MacOS/pasala-mcp"
[ -f Resources/AppIcon.icns ] && cp Resources/AppIcon.icns "$APP/Contents/Resources/"
cp -R Localization/*.lproj "$APP/Contents/Resources/"
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleDevelopmentRegion</key><string>en</string>
  <key>CFBundleLocalizations</key><array><string>en</string><string>es</string></array>
  <key>CFBundleIdentifier</key><string>com.tecnomanu.pasala</string>
  <key>CFBundleName</key><string>Pasala</string>
  <key>CFBundleDisplayName</key><string>Pasala</string>
  <key>CFBundleExecutable</key><string>Pasala</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSUIElement</key><true/>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSLocalNetworkUsageDescription</key><string>Pasala usa tu red para recibir y mandar archivos a tus dispositivos vinculados.</string>
  <key>NSBonjourServices</key><array><string>_pasala._tcp</string></array>
  <key>NSServices</key>
  <array><dict>
    <key>NSMenuItem</key><dict><key>default</key><string>Pasar con Pasala</string></dict>
    <key>NSMessage</key><string>sendText</string>
    <key>NSPortName</key><string>Pasala</string>
    <key>NSSendTypes</key><array><string>NSStringPboardType</string><string>public.utf8-plain-text</string></array>
    <key>NSRequiredContext</key><dict/>
  </dict>
  <dict>
    <key>NSMenuItem</key><dict><key>default</key><string>Enviar con Pasala</string></dict>
    <key>NSMessage</key><string>sendFiles</string>
    <key>NSPortName</key><string>Pasala</string>
    <key>NSSendFileTypes</key><array><string>public.item</string></array>
    <key>NSRequiredContext</key><dict/>
  </dict></array>
</dict></plist>
PLIST
codesign --force --deep --sign - "$APP" >/dev/null
echo "$PWD/$APP"
