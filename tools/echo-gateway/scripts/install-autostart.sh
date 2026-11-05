#!/usr/bin/env bash
# Start the gateway at boot, for the current user. Run once from the checkout that holds .env.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UNIT_NAME=kainos-echo-gateway.service
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"

if [[ ! -f "$ROOT/.env" ]]; then
  echo "Missing $ROOT/.env — copy .env.example and fill it in first" >&2
  exit 1
fi

mkdir -p "$UNIT_DIR"
# WorkingDirectory takes a path, not a shell word. Percent signs are specifiers.
python3 - "$ROOT" "$UNIT_DIR/$UNIT_NAME" <<'PYTHON'
from pathlib import Path
import sys
root, target = sys.argv[1:]
if any(ord(char) < 32 for char in root) or "\\" in root or root != root.rstrip():
    raise SystemExit("Gateway directory cannot contain control characters, backslashes or trailing whitespace")
escaped = root.replace("%", "%%")
template = (Path(root) / "scripts/kainos-echo-gateway.service").read_text()
Path(target).write_text(template.replace("__GATEWAY_DIR__", escaped))
PYTHON

systemctl --user daemon-reload
systemctl --user enable "$UNIT_NAME"
# Without linger the user manager, and so this unit, waits for a login.
loginctl enable-linger "$(id -un)"

echo "Installed $UNIT_DIR/$UNIT_NAME"
echo "Start it now with: systemctl --user start $UNIT_NAME"
