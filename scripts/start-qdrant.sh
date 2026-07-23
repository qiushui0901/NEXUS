#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if ! command -v docker >/dev/null 2>&1; then
  export PATH="$HOME/.local/bin:/Applications/Docker.app/Contents/Resources/bin:$PATH"
fi

docker compose up -d qdrant

for _ in $(seq 1 30); do
  if curl -sf http://localhost:6333/collections >/dev/null 2>&1; then
    echo "Qdrant started on http://localhost:6333"
    echo "Dashboard: http://localhost:6333/dashboard"
    exit 0
  fi
  sleep 1
done

echo "Qdrant failed to become ready; run: docker compose logs qdrant" >&2
exit 1
