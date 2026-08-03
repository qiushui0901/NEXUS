#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATASET_RESOURCE="evaluation/retrieval-eval-document-v2.jsonl"
FIXTURE_DIRECTORY="${ROOT_DIR}/src/test/resources/evaluation/document-v2"
OUTPUT_ROOT="${ROOT_DIR}/target/retrieval-evaluation/0.8.2-document-v2"
PROJECT_ID="document-v2-eval"
DOCUMENT_ID="document-v2-corpus"
VERSION="document-v2-v2"
BRANCH_TIMEOUT_MS="30000"
BGE_READ_TIMEOUT_MS="120000"
WARMUP_RUNS="${RETRIEVAL_EVAL_WARMUP_RUNS:-0}"
REPETITIONS="${RETRIEVAL_EVAL_REPETITIONS:-1}"

fail() {
  echo "ERROR: $*" >&2
  exit 2
}

JAVA_HOME_FIXED="$("/usr/libexec/java_home" -v 21)" || fail "Java 21 is required"
[[ -x "${JAVA_HOME_FIXED}/bin/java" ]] || fail "Java 21 runtime is missing"
[[ -d "${FIXTURE_DIRECTORY}" ]] || fail "Frozen v2 fixture directory is missing"
[[ -f "${ROOT_DIR}/src/test/resources/${DATASET_RESOURCE}" ]] || fail "Frozen v2 dataset is missing"

curl -fsS "http://127.0.0.1:6333/collections" >/dev/null \
  || fail "Qdrant is unavailable at 127.0.0.1:6333"
curl -fsS "http://127.0.0.1:11434/api/tags" >/dev/null \
  || fail "Ollama is unavailable at 127.0.0.1:11434"

export JAVA_HOME="${JAVA_HOME_FIXED}"
export PATH="${JAVA_HOME}/bin:${PATH}"
export SPRING_PROFILES_ACTIVE="shiguang-eval"
export SHIGUANG_REPOSITORY_PATH="${SHIGUANG_REPOSITORY_PATH:-${ROOT_DIR}}"
export QDRANT_URL="http://127.0.0.1:6333"
export OLLAMA_BASE_URL="http://127.0.0.1:11434"
export BGE_RERANK_URL="http://127.0.0.1:8081"
export BGE_RERANK_PATH="/rerank"
export BGE_RERANK_API_KEY=""
export BGE_RERANK_CONNECT_TIMEOUT_MS="2000"
export BGE_RERANK_READ_TIMEOUT_MS="${BGE_READ_TIMEOUT_MS}"
export BGE_RERANK_EXPECT_DEVICE="cpu"
export BGE_RERANK_EXPECT_MAX_LENGTH="384"
export BGE_RERANK_EXPECT_BATCH_SIZE="4"
export RETRIEVAL_BRANCH_TIMEOUT_MS="${BRANCH_TIMEOUT_MS}"

export APP_RAG_RETRIEVAL_DENSE_TOP_K="50"
export APP_RAG_RETRIEVAL_SPARSE_TOP_K="50"
export APP_RAG_RETRIEVAL_HYBRID_TOP_K="40"
export APP_RAG_RETRIEVAL_BGE_TOP_K="20"
export APP_RAG_RETRIEVAL_LLM_TOP_K="10"
export APP_RAG_RETRIEVAL_CHILD_FIRST_RERANK_ENABLED="true"
export APP_RAG_RETRIEVAL_ENRICHED_BGE_PASSAGE_ENABLED="true"
export APP_RAG_RETRIEVAL_CODE_QUERY_EXPANSION_ENABLED="true"
export LLM_RERANK_ENABLED="false"
export RETRIEVAL_CACHE_TTL_SECONDS="-1"
export RETRIEVAL_CACHE_MAX_ENTRIES="-1"
export EMBEDDING_CACHE_TTL_SECONDS="3600"
export EMBEDDING_CACHE_MAX_ENTRIES="512"

cd "${ROOT_DIR}"

echo "Checking the frozen BGE endpoint contract..."
/opt/homebrew/bin/python3.11 tools/check-bge-reranker.py

mkdir -p "${OUTPUT_ROOT}"

echo "Rebuilding the isolated document-v2 corpus..."
RUN_RETRIEVAL_EVAL_SETUP="true" \
RETRIEVAL_EVAL_SETUP_PROJECT_ID="${PROJECT_ID}" \
RETRIEVAL_EVAL_SETUP_DOCUMENT_ID="${DOCUMENT_ID}" \
RETRIEVAL_EVAL_SETUP_VERSION="${VERSION}" \
RETRIEVAL_EVAL_SETUP_FIXTURE="${FIXTURE_DIRECTORY}" \
RETRIEVAL_EVAL_SETUP_SKIP_CODE="true" \
RETRIEVAL_EVAL_SETUP_OUTPUT="${OUTPUT_ROOT}/setup.json" \
  ./mvnw -q -Dtest=RetrievalEvaluationSetupIT test

echo "Running the document-v2 calibration..."
RUN_RETRIEVAL_EVAL="true" \
RETRIEVAL_EVAL_DATASET_RESOURCE="${DATASET_RESOURCE}" \
RETRIEVAL_EVAL_MODE="0.8.2-document-v2" \
RETRIEVAL_EVAL_OUTPUT_DIRECTORY="${OUTPUT_ROOT}" \
RETRIEVAL_EVAL_WARMUP_RUNS="${WARMUP_RUNS}" \
RETRIEVAL_EVAL_REPETITIONS="${REPETITIONS}" \
RETRIEVAL_EVAL_BASELINE_RESOURCE="" \
  ./mvnw -q -Dtest=RetrievalEvaluationIT test

echo "Document-v2 calibration written to ${OUTPUT_ROOT}/report.md"
