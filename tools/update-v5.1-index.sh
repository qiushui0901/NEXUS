#!/usr/bin/env bash
set -euo pipefail

# 只生成版本清单；真正写入 Qdrant 需显式指定 RUN_INDEX=1，避免误上传/误覆盖数据。
RAG_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="${CODE_REPOSITORY_PATH:-/Users/user/Documents/immortal-game-service}"
BASE_SHA="${V5_1_BASE_SHA:-origin/V5.0.2}"
NEW_SHA="${V5_1_NEW_SHA:-HEAD}"
PROJECT_ID="${CODE_PROJECT_ID:-immortal-game-service}"
OUTPUT="${MANIFEST_OUTPUT:-$RAG_ROOT/data/knowledge-manifests/immortal-game-service-v5.1.json}"

python3 "$RAG_ROOT/tools/generate-knowledge-manifest.py" \
  --repo "$REPO" \
  --base "$BASE_SHA" \
  --commit "$NEW_SHA" \
  --project-id "$PROJECT_ID" \
  --code-version 5.1 \
  --code-collection "${CODE_QDRANT_COLLECTION:-code_chunks_immortal_game_service_v5_1}" \
  --document-id "${KNOWLEDGE_DOCUMENT_ID:-fengshen}" \
  --requirement-version 5.1 \
  --requirement-source "$RAG_ROOT/data/产品文档.zip" \
  --requirement-source "$RAG_ROOT/data/封神版本问题整理.xlsx" \
  --output "$OUTPUT"

if [[ "${RUN_INDEX:-0}" == "1" ]]; then
  : "${RAG_URL:=http://localhost:8080}"
  curl --fail-with-body -X POST \
    "$RAG_URL/api/code/incremental-index" \
    --data-urlencode "projectId=$PROJECT_ID" \
    --data-urlencode "oldSha=$BASE_SHA" \
    --data-urlencode "newSha=$NEW_SHA"
else
  printf '%s\n' "Manifest generated. Set RUN_INDEX=1 only after Qdrant and embedding services are ready."
fi
