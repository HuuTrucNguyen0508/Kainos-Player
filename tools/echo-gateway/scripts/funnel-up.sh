#!/usr/bin/env bash
# Expose the local echo gateway through Tailscale Funnel (public HTTPS :443).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ ! -f .env ]]; then
  echo "Missing .env — copy .env.example and set PUBLIC_STREAM_TOKEN / PUBLIC_BASE_URL" >&2
  exit 1
fi

# shellcheck disable=SC1091
set -a
source .env
set +a

: "${PUBLIC_BASE_URL:?PUBLIC_BASE_URL must be set}"
: "${PUBLIC_STREAM_TOKEN:?PUBLIC_STREAM_TOKEN must be set}"

echo "Starting gateway (Compose)…"
docker compose up --build -d

echo "Enabling Tailscale Funnel → 127.0.0.1:8787 …"
# Funnel must run on the host, not inside the container.
tailscale funnel --bg --yes 8787

echo
echo "Public base:     $PUBLIC_BASE_URL"
echo "Health:          $PUBLIC_BASE_URL/health"
echo "Token stream:    $PUBLIC_BASE_URL/stream/t/<token>"
echo "Alexa endpoint:  $PUBLIC_BASE_URL/alexa"
echo
echo "WARNING: Funnel makes matched routes reachable on the public internet."
echo "Open /stream/test is disabled when ALLOW_OPEN_TEST_STREAM=false."
echo
tailscale funnel status
