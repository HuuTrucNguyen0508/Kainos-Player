#!/usr/bin/env bash
# Tear down Funnel exposure and stop the gateway container.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "Resetting Tailscale Funnel/Serve config…"
tailscale funnel reset || true
tailscale serve reset || true

echo "Stopping gateway…"
docker compose down || true

echo "Done."
