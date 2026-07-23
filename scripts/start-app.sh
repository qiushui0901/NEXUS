#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi
JAVA_HOME="${JAVA_HOME:-/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home}"
JAR="$ROOT/target/requirement-rag-0.0.1-SNAPSHOT.jar"
PID_FILE="$ROOT/tools/app.pid"
LOG_FILE="$ROOT/tools/app.log"

mkdir -p "$ROOT/tools"

if [[ ! -f "$JAR" ]]; then
  echo "JAR not found, building..." >&2
  "$JAVA_HOME/bin/java" -version >/dev/null 2>&1 || { echo "Java not found at $JAVA_HOME" >&2; exit 1; }
  JAVA_HOME="$JAVA_HOME" mvn package -DskipTests -q
fi

if [[ -f "$PID_FILE" ]]; then
  old_pid="$(cat "$PID_FILE")"
  if kill -0 "$old_pid" 2>/dev/null; then
    echo "App already running (pid $old_pid) — http://localhost:8080"
    exit 0
  fi
  rm -f "$PID_FILE"
fi

export KNOWLEDGE_BOOTSTRAP_ENABLED="${KNOWLEDGE_BOOTSTRAP_ENABLED:-false}"

nohup "$JAVA_HOME/bin/java" -jar "$JAR" >> "$LOG_FILE" 2>&1 < /dev/null &
echo $! > "$PID_FILE"

for _ in $(seq 1 30); do
  if curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; then
    echo "App started on http://localhost:8080 (pid $(cat "$PID_FILE"))"
    echo "Log: $LOG_FILE"
    exit 0
  fi
  sleep 1
done

echo "App failed to become ready; see $LOG_FILE" >&2
tail -20 "$LOG_FILE" >&2
exit 1
