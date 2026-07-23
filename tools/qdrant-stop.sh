#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$SCRIPT_DIR/qdrant.pid"

if [ ! -f "$PID_FILE" ]; then
  echo "No PID file found, Qdrant may not be running"
  exit 0
fi

PID=$(cat "$PID_FILE")
if kill -0 "$PID" 2>/dev/null; then
  echo "Stopping Qdrant (PID $PID)..."
  kill "$PID"
  for i in $(seq 1 10); do
    kill -0 "$PID" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$PID" 2>/dev/null; then
    echo "Force killing..."
    kill -9 "$PID" 2>/dev/null || true
  fi
  echo "Qdrant stopped"
else
  echo "Qdrant is not running (stale PID $PID)"
fi

rm -f "$PID_FILE"
