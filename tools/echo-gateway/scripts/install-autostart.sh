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
ESCAPED_ROOT="$(printf '%s' "$ROOT" | sed 's/[&|\\]/\\&/g')"
sed "s|__GATEWAY_DIR__|$ESCAPED_ROOT|" "$ROOT/scripts/$UNIT_NAME" > "$UNIT_DIR/$UNIT_NAME"

systemctl --user daemon-reload
systemctl --user enable "$UNIT_NAME"
# Without linger the user manager, and so this unit, waits for a login.
loginctl enable-linger "$(id -un)"

echo "Installed $UNIT_DIR/$UNIT_NAME"
echo "Start it now with: systemctl --user start $UNIT_NAME"
