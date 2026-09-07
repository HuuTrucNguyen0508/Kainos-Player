#!/usr/bin/env bash
# Install a user desktop launcher and icons for Kainos Player.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN_DIR="${XDG_BIN_HOME:-$HOME/.local/bin}"
APP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
ICON_BASE="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor"
LAUNCHER="$ROOT/scripts/kainos-player"

mkdir -p "$BIN_DIR" "$APP_DIR" \
  "$ICON_BASE/256x256/apps" \
  "$ICON_BASE/512x512/apps"

chmod +x "$LAUNCHER"

# Wrapper keeps a stable PATH entry while always running the repo script.
cat > "$BIN_DIR/kainos-player" <<EOF
#!/usr/bin/env bash
exec "$LAUNCHER" "\$@"
EOF
chmod 755 "$BIN_DIR/kainos-player"

install -m 644 "$ROOT/desktopApp/icons/linux.png" "$ICON_BASE/256x256/apps/kainos-player.png"
install -m 644 "$ROOT/desktopApp/icons/linux-512.png" "$ICON_BASE/512x512/apps/kainos-player.png"

cat > "$APP_DIR/kainos-player.desktop" <<EOF
[Desktop Entry]
Type=Application
Version=1.0
Name=Kainos Player
GenericName=Music Player
Comment=Unified music player for local files, Spotify, and YouTube Music
Exec=$BIN_DIR/kainos-player
Icon=kainos-player
Terminal=false
Categories=AudioVideo;Audio;Player;
StartupNotify=true
StartupWMClass=com-universalmusic-player-desktop-MainKt
EOF

chmod 644 "$APP_DIR/kainos-player.desktop"

if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "$APP_DIR" >/dev/null 2>&1 || true
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
  gtk-update-icon-cache -f -t "$ICON_BASE" >/dev/null 2>&1 || true
fi

echo "Installed launcher: $BIN_DIR/kainos-player"
echo "Desktop entry: $APP_DIR/kainos-player.desktop"
echo "Icons: $ICON_BASE/{256x256,512x512}/apps/kainos-player.png"
