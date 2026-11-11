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
[[ -e "$HOME/.local/bin/yt-dlp" ]] && targets+=("$HOME/.local/bin/yt-dlp")
IFS=: read -r -a path_dirs <<< "$PATH"
for dir in "${path_dirs[@]}"; do
    candidate="${dir:-.}/yt-dlp"
    [[ -f "$candidate" && -O "$candidate" ]] && targets+=("$candidate")
done
if [[ -n "${KAINOS_YT_DLP:-}" ]]; then targets+=("$KAINOS_YT_DLP"); fi

version_of() { [[ -x "$1" ]] && "$1" --version 2>/dev/null || echo "none"; }

mkdir -p "$DEST_DIR" "$TOOLS_DIR"
TMP="$(mktemp)"
staged=""
cleanup() { rm -f "$TMP"; [[ -z "$staged" ]] || rm -f "$staged"; }
trap cleanup EXIT

echo "Downloading the latest yt-dlp…"
curl -fsSL "$URL" -o "$TMP"
chmod +x "$TMP"
latest="$("$TMP" --version)"
[[ -n "$latest" ]] || { echo "Downloaded yt-dlp has no version" >&2; exit 1; }

declare -A seen=()
for target in "${targets[@]}"; do
    # Follow symlinks so aliases keep pointing at the same executable.
    target="$(readlink -m -- "$target")"
    if [[ -e "$target" && ! -O "$target" ]]; then
        echo "  $target: skipped (not owned by this user)" >&2
        continue
    fi
    [[ -n "${seen[$target]:-}" ]] && continue
    seen[$target]=1
    before="$(version_of "$target")"
    if [[ "$before" == "$latest" ]]; then
        echo "  $target: already $latest"
        continue
    fi
    # Stage beside the destination: rename is atomic and a failed copy cannot damage it.
    if ! mkdir -p -- "$(dirname -- "$target")" ||
        ! staged="$(mktemp "$(dirname -- "$target")/.yt-dlp-update.XXXXXX")" ||
        ! install -m 755 "$TMP" "$staged" ||
        [[ "$("$staged" --version 2>/dev/null)" != "$latest" ]] ||
        ! mv -fT -- "$staged" "$target"; then
        echo "  $target: could not write (left at $before)" >&2
        [[ -z "$staged" ]] || rm -f "$staged"
        staged=""
        continue
    fi
    staged=""
    if [[ "$before" == "none" ]]; then
        echo "  $target: installed $latest"
    else
        echo "  $target: updated $before → $latest"
    fi
done

echo "Restart Kainos Player, or tap Retry in Settings → Downloads."
