#!/usr/bin/env bash
# Build the pinned upstream Spotify Connect receiver locally.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if ! command -v cargo >/dev/null 2>&1; then
  echo "Install Rust/Cargo and ALSA development libraries, then run this script again." >&2
  exit 1
fi
cargo install librespot --version 0.8.0 --locked --root "$ROOT/tools/librespot-runtime"
"$ROOT/tools/librespot-runtime/bin/librespot" --version
