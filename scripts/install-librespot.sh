#!/usr/bin/env bash
# Build the pinned upstream Spotify Connect receiver and the Kainos metadata helper locally.
# The helper (Discover Weekly, playlists, Search radio) is built from tools/kainos-spotify-rootlist
# every time so the installed copy cannot fall behind its source again.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME="$ROOT/tools/librespot-runtime"
if ! command -v cargo >/dev/null 2>&1; then
  echo "Install Rust/Cargo and ALSA development libraries, then run this script again." >&2
  exit 1
fi
cargo install librespot --version 0.8.0 --locked --root "$RUNTIME"
"$RUNTIME/bin/librespot" --version

cargo build --release --locked --manifest-path "$ROOT/tools/kainos-spotify-rootlist/Cargo.toml"
mkdir -p "$RUNTIME/bin"
# Replace atomically: a running Kainos may start the helper at any moment.
install -m 0755 "$ROOT/tools/kainos-spotify-rootlist/target/release/kainos-spotify-rootlist" \
  "$RUNTIME/bin/kainos-discover-weekly.new"
mv -f "$RUNTIME/bin/kainos-discover-weekly.new" "$RUNTIME/bin/kainos-discover-weekly"
# With no arguments the helper prints its usage, which names every mode, and exits 2.
usage="$("$RUNTIME/bin/kainos-discover-weekly" 2>&1 || true)"
if [[ "$usage" != *"radio:"* ]]; then
  echo "The built kainos-discover-weekly has no radio mode; check tools/kainos-spotify-rootlist." >&2
  exit 1
fi
echo "Installed $RUNTIME/bin/kainos-discover-weekly (Discover Weekly, playlist and radio modes)."
