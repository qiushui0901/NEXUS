#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/tools"
APP_PID="$TOOLS/app.pid"
QDRANT_PID="$TOOLS/qdrant.pid"
APP_LOG="$TOOLS/app.log"
QDRANT_LOG="$TOOLS/qdrant.log"
mkdir -p "$TOOLS"

load_env() {
  if [[ -f "$ROOT/.env" ]]; then
    set -a
    # shellcheck disable=SC1091
    source "$ROOT/.env"
    set +a
  fi
  export SERVER_ADDRESS="${SERVER_ADDRESS:-127.0.0.1}"
  export SERVER_PORT="${SERVER_PORT:-8080}"
  export KNOWLEDGE_BOOTSTRAP_ENABLED="${KNOWLEDGE_BOOTSTRAP_ENABLED:-false}"
}

is_java21() {
  local home="$1"
  [[ -x "$home/bin/java" ]] || return 1
  "$home/bin/java" -version 2>&1 | head -1 | grep -Eq 'version "21([.]|")'
}

java_home() {
  local candidates=() detected="" candidate=""
  [[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME")
  if [[ -x /usr/libexec/java_home ]]; then
    detected="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    [[ -n "$detected" ]] && candidates+=("$detected")
  fi
  candidates+=("$HOME/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home")

  for candidate in "${candidates[@]}"; do
    if is_java21 "$candidate"; then
      echo "$candidate"
      return
    fi
  done

  echo "未找到 JDK 21；当前 JAVA_HOME=${JAVA_HOME:-未设置}。请安装 JDK 21 或修正 JAVA_HOME。" >&2
  exit 1
}

app_url() { echo "http://${SERVER_ADDRESS}:${SERVER_PORT}"; }

alive() { [[ -f "$1" ]] && kill -0 "$(cat "$1")" 2>/dev/null; }
http_ok() { curl -fsS --max-time 2 "$1" >/dev/null 2>&1; }
qdrant_url() { echo "${QDRANT_URL:-http://127.0.0.1:6333}"; }

qdrant_port() {
  local url; url="$(qdrant_url)"
  if [[ "$url" =~ ^https?://(127\.0\.0\.1|localhost):([0-9]+)(/.*)?$ ]]; then
    echo "${BASH_REMATCH[2]}"
  elif [[ "$url" =~ ^https?://(127\.0\.0\.1|localhost)(/.*)?$ ]]; then
    echo 6333
  fi
}

listener_pid_for_port() {
  local port="$1"
  [[ -n "$port" ]] || return 1
  command -v lsof >/dev/null 2>&1 || return 1
  lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | head -1
}

listener_pid() { listener_pid_for_port "$(qdrant_port)"; }

project_qdrant_pid() {
  local pid="$1" command=""
  [[ -n "$pid" ]] || return 1
  command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  [[ "$command" == "$TOOLS/qdrant"* ]]
}

terminate_process() {
  local pid="$1"
  kill -0 "$pid" 2>/dev/null || return 0
  kill "$pid" 2>/dev/null || true
  for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || return 0; sleep .25; done
  kill -9 "$pid" 2>/dev/null || true
}

recover_unhealthy_qdrant() {
  local tracked="" listener=""
  if alive "$QDRANT_PID"; then tracked="$(cat "$QDRANT_PID")"; fi
  listener="$(listener_pid || true)"

  if [[ -n "$tracked" ]] && project_qdrant_pid "$tracked"; then
    echo "检测到项目 Qdrant 进程异常，正在自动恢复…"
    terminate_process "$tracked"
  elif [[ -n "$listener" ]] && project_qdrant_pid "$listener"; then
    echo "检测到未登记的项目 Qdrant 异常进程（PID $listener），正在自动恢复…"
    terminate_process "$listener"
  elif [[ -n "$listener" ]]; then
    echo "Qdrant 接口不可用，且本机端口 $(qdrant_port) 已被其他进程占用（PID $listener）。" >&2
    echo "为避免误停其他服务，NEXUS 未自动处理该进程。" >&2
    return 1
  fi
  rm -f "$QDRANT_PID"
}

start_qdrant() {
  local url; url="$(qdrant_url)"
  if http_ok "$url/collections"; then echo "Qdrant 已运行"; return; fi
  [[ -x "$TOOLS/qdrant" ]] || { echo "缺少 tools/qdrant；也可以先运行 ./scripts/start-qdrant.sh" >&2; exit 1; }
  recover_unhealthy_qdrant || exit 1

  : > "$QDRANT_LOG"
  QDRANT__SERVICE__HOST=127.0.0.1 QDRANT__STORAGE__STORAGE_PATH="$ROOT/qdrant-storage" \
    nohup "$TOOLS/qdrant" >>"$QDRANT_LOG" 2>&1 < /dev/null &
  echo $! > "$QDRANT_PID"
  for _ in $(seq 1 60); do
    if http_ok "$url/collections"; then echo "Qdrant 已启动"; return; fi
    alive "$QDRANT_PID" || break
    sleep 1
  done

  if alive "$QDRANT_PID"; then terminate_process "$(cat "$QDRANT_PID")"; fi
  rm -f "$QDRANT_PID"
  echo "Qdrant 启动失败，请查看 $QDRANT_LOG" >&2
  tail -30 "$QDRANT_LOG" >&2
  exit 1
}

build_jar() {
  local jar needs_build=false
  jar="$(find "$ROOT/target" -maxdepth 1 -type f -name 'NEXUS-*.jar' ! -name '*.original' 2>/dev/null | sort | tail -1 || true)"
  if [[ -z "$jar" || "$ROOT/pom.xml" -nt "$jar" ]]; then
    needs_build=true
  elif find "$ROOT/src" -type f -newer "$jar" -print -quit | grep -q .; then
    needs_build=true
  fi

  if [[ "$needs_build" == true ]]; then
    local jh; jh="$(java_home)"
    echo "检测到代码有更新，正在构建 NEXUS…" >&2
    JAVA_HOME="$jh" PATH="$jh/bin:$PATH" "$ROOT/mvnw" -q -DskipTests package
    jar="$(find "$ROOT/target" -maxdepth 1 -type f -name 'NEXUS-*.jar' ! -name '*.original' | sort | tail -1)"
  fi
  [[ -n "$jar" ]] || { echo "NEXUS 构建完成但未找到可运行 JAR" >&2; exit 1; }
  echo "$jar"
}

project_app_pid() {
  local pid="$1" command=""
  [[ -n "$pid" ]] || return 1
  command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  [[ "$command" == *"$ROOT/target/NEXUS-"*".jar"* ]]
}

recover_unhealthy_app() {
  local tracked="" listener=""
  if alive "$APP_PID"; then tracked="$(cat "$APP_PID")"; fi
  listener="$(listener_pid_for_port "$SERVER_PORT" || true)"

  if [[ -n "$tracked" ]] && project_app_pid "$tracked"; then
    echo "检测到项目 NEXUS 进程异常，正在自动恢复…"
    terminate_process "$tracked"
  elif [[ -n "$listener" ]] && project_app_pid "$listener"; then
    echo "检测到未登记的 NEXUS 异常进程（PID $listener），正在自动恢复…"
    terminate_process "$listener"
  elif [[ -n "$listener" ]]; then
    echo "NEXUS 接口不可用，且端口 ${SERVER_PORT} 已被其他进程占用（PID $listener）。" >&2
    echo "为避免误停其他服务，NEXUS 未自动处理该进程。" >&2
    return 1
  fi
  rm -f "$APP_PID"
}

start_app() {
  local base; base="$(app_url)"
  if http_ok "$base/api/runtime/status"; then echo "NEXUS 已运行：$base"; return; fi
  recover_unhealthy_app || exit 1

  local jh jar; jh="$(java_home)"; jar="$(build_jar)"
  : > "$APP_LOG"
  nohup "$jh/bin/java" -jar "$jar" >>"$APP_LOG" 2>&1 < /dev/null &
  echo $! > "$APP_PID"
  for _ in $(seq 1 60); do
    if http_ok "$base/actuator/health"; then
      echo "NEXUS 已启动：$base"
      return
    fi
    if ! alive "$APP_PID"; then break; fi
    sleep 1
  done

  if alive "$APP_PID"; then terminate_process "$(cat "$APP_PID")"; fi
  rm -f "$APP_PID"
  echo "NEXUS 启动失败，请查看 $APP_LOG" >&2
  tail -30 "$APP_LOG" >&2
  exit 1
}

stop_app() {
  local pid=""
  if alive "$APP_PID"; then
    pid="$(cat "$APP_PID")"
  else
    pid="$(listener_pid_for_port "$SERVER_PORT" || true)"
  fi
  if [[ -n "$pid" ]] && project_app_pid "$pid"; then terminate_process "$pid"; fi
  rm -f "$APP_PID"
  echo "NEXUS 已停止"
}

stop_qdrant() {
  local pid=""
  if alive "$QDRANT_PID"; then
    pid="$(cat "$QDRANT_PID")"
  else
    pid="$(listener_pid || true)"
  fi
  if [[ -n "$pid" ]] && project_qdrant_pid "$pid"; then terminate_process "$pid"; fi
  rm -f "$QDRANT_PID"
  echo "Qdrant 已停止"
}

show_status() {
  local base; base="$(app_url)"
  if http_ok "$base/api/runtime/status"; then
    echo "NEXUS: 运行中（$base）"
  elif http_ok "$base/actuator/health"; then
    echo "NEXUS: 端口 ${SERVER_PORT} 被旧版或其他应用占用"
  else
    echo "NEXUS: 未运行"
  fi
  echo "Qdrant: $(http_ok "$(qdrant_url)/collections" && echo 运行中 || echo 未运行)"
  echo "Ollama: $(http_ok "${OLLAMA_BASE_URL:-http://127.0.0.1:11434}/api/tags" && echo 运行中 || echo 未运行)"
  # 注意：BGE 这里指【重排器 Reranker】（端口 8081，/rerank），不是嵌入模型。
  # 向量化（Embedding）走 OpenAI 兼容网关 ai-gateway（text-embedding-v4），见下一行。
  echo "Reranker-BGE(:8081): $(http_ok "${BGE_RERANK_URL:-http://127.0.0.1:8081}/health" && echo 运行中 || echo 未运行（可降级）)"
  local embed_model="${OPENAI_EMBEDDING_MODEL:-text-embedding-v4}"
  local embed_gw="${OPENAI_BASE_URL:-http://ai-gateway.momo.com}"
  echo "Embedding(${embed_model}@网关): $(http_ok "${embed_gw}" && echo 运行中 || echo 未运行/未确认)"
  if http_ok "$base/api/runtime/status"; then curl -fsS "$base/api/runtime/status"; echo; fi
}

load_env
case "${1:-start}" in
  start) start_qdrant; start_app; show_status ;;
  stop) stop_app; stop_qdrant ;;
  restart) "$0" stop; "$0" start ;;
  status) show_status ;;
  logs) tail -f "$APP_LOG" ;;
  *) echo "用法：$0 {start|stop|restart|status|logs}" >&2; exit 2 ;;
esac
