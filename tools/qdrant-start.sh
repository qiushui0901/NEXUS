#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
QDRANT_BIN="$SCRIPT_DIR/qdrant"
STORAGE_DIR="$PROJECT_DIR/qdrant-storage"
PID_FILE="$SCRIPT_DIR/qdrant.pid"
LOG_FILE="$SCRIPT_DIR/qdrant.log"

if [ ! -x "$QDRANT_BIN" ]; then
  echo "ERROR: Qdrant binary not found at $QDRANT_BIN"
  exit 1
fi

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "Qdrant is already running (PID $(cat "$PID_FILE"))"
  exit 0
fi

mkdir -p "$STORAGE_DIR"

echo "Starting Qdrant (storage: $STORAGE_DIR)..."
export QDRANT__STORAGE__STORAGE_PATH="$STORAGE_DIR"
nohup "$QDRANT_BIN" > "$LOG_FILE" 2>&1 &
echo $! > "$PID_FILE"
sleep 2

if kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "Qdrant started (PID $(cat "$PID_FILE")), port 6333"
else
  echo "ERROR: Qdrant failed to start. Check $LOG_FILE"
  cat "$LOG_FILE" | tail -5
  exit 1
fi
