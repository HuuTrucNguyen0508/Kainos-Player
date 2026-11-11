#!/usr/bin/env bash
# Install or update yt-dlp for Kainos Player YouTube audio (playback, Mix radio, downloads).
# Run it again whenever Settings → Downloads says the YouTube extractor looks out of date:
# it replaces every copy the app can find with the latest release.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="${1:-$HOME/.local/bin}"
TOOLS_DIR="$ROOT/tools"
URL="https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"

# Every place the app looks (KAINOS_YT_DLP, ~/.local/bin, tools/), plus the chosen destination.
targets=("$DEST_DIR/yt-dlp" "$TOOLS_DIR/yt-dlp")
if [[ -n "${KAINOS_YT_DLP:-}" ]]; then targets+=("$KAINOS_YT_DLP"); fi

version_of() { [[ -x "$1" ]] && "$1" --version 2>/dev/null || echo "none"; }

mkdir -p "$DEST_DIR" "$TOOLS_DIR"
TMP="$(mktemp)"
cleanup() { rm -f "$TMP"; }
trap cleanup EXIT

echo "Downloading the latest yt-dlp…"
curl -fsSL "$URL" -o "$TMP"
chmod +x "$TMP"
latest="$("$TMP" --version)"

declare -A seen=()
for target in "${targets[@]}"; do
    [[ -n "${seen[$target]:-}" ]] && continue
    seen[$target]=1
    before="$(version_of "$target")"
    if [[ "$before" == "$latest" ]]; then
        echo "  $target: already $latest"
        continue
    fi
    if ! install -D -m 755 "$TMP" "$target" 2>/dev/null; then
        echo "  $target: could not write (left at $before)" >&2
        continue
    fi
    if [[ "$before" == "none" ]]; then
        echo "  $target: installed $latest"
    else
        echo "  $target: updated $before → $latest"
    fi
done

echo "Restart Kainos Player, or tap Retry in Settings → Downloads."
