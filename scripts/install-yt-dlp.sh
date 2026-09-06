#!/usr/bin/env bash
# Install yt-dlp for Kainos Player YouTube audio resolution.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="${1:-$HOME/.local/bin}"
TOOLS_DIR="$ROOT/tools"
URL="https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"

mkdir -p "$DEST_DIR" "$TOOLS_DIR"
TMP="$(mktemp)"
cleanup() { rm -f "$TMP"; }
trap cleanup EXIT

echo "Downloading yt-dlp…"
curl -fsSL "$URL" -o "$TMP"
chmod +x "$TMP"

install -m 755 "$TMP" "$DEST_DIR/yt-dlp"
install -m 755 "$TMP" "$TOOLS_DIR/yt-dlp"

echo "Installed:"
echo "  $DEST_DIR/yt-dlp"
echo "  $TOOLS_DIR/yt-dlp"
"$DEST_DIR/yt-dlp" --version
