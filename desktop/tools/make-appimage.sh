#!/bin/sh
# Wraps the app image from `./gradlew :app:packageAppImage` into a single Pasala-x86_64.AppImage.
# Needs appimagetool (https://github.com/AppImage/appimagetool) on PATH or in $APPIMAGETOOL.
set -eu
cd "$(dirname "$0")/.."
SRC=app/build/compose/binaries/main/app/Pasala
OUT=app/build/compose/binaries/main/appimage
TOOL=${APPIMAGETOOL:-appimagetool}
[ -d "$SRC" ] || { echo "Run ./gradlew :app:packageAppImage first" >&2; exit 1; }
rm -rf "$OUT" && mkdir -p "$OUT" && cp -a "$SRC" "$OUT/Pasala.AppDir"
D="$OUT/Pasala.AppDir"
cp icons/pasala.png "$D/pasala.png"
cat > "$D/pasala.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=Pasala
Comment=Pass files and text between your own devices
Exec=Pasala %F
Icon=pasala
Categories=Network;FileTransfer;
DESKTOP
cat > "$D/AppRun" <<'APPRUN'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/bin/Pasala" "$@"
APPRUN
chmod +x "$D/AppRun"
ARCH=x86_64 "$TOOL" --appimage-extract-and-run "$D" "$OUT/Pasala-x86_64.AppImage" 2>/dev/null \
  || ARCH=x86_64 "$TOOL" "$D" "$OUT/Pasala-x86_64.AppImage"
echo "$OUT/Pasala-x86_64.AppImage"
