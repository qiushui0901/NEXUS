#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME_FIXED="/Users/user/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home"
PYTHON_FIXED="/opt/homebrew/bin/python3.11"
RERANKER_PYTHON_FIXED="${ROOT_DIR}/.venv-bge-reranker/bin/python"
BGE_RERANK_URL_FIXED="http://127.0.0.1:8081"
BGE_RERANK_PATH_FIXED="/rerank"
SHIGUANG_REPOSITORY_FIXED="/Users/user/Documents/qiushui-shiguang"
SHIGUANG_COMMIT_FIXED="d29f32589c5bd7c190a23eb3a84f27f0069f312f"
DATASET_RESOURCE="evaluation/retrieval-eval-shiguang-v1.jsonl"
DATASET_FILE="${ROOT_DIR}/src/test/resources/${DATASET_RESOURCE}"
DATASET_SHA256="1ff996579588bfc5b859b5a483427c255325265b211e452af5eaff6471a61b18"
OUTPUT_ROOT="${ROOT_DIR}/target/retrieval-evaluation"
WARMUP_RUNS=1
REPETITIONS=3

fail() {
  echo "ERROR: $*" >&2
  exit 2
}

[[ -x "${JAVA_HOME_FIXED}/bin/java" ]] || fail "Fixed Java 21 runtime is missing: ${JAVA_HOME_FIXED}"
[[ -x "${PYTHON_FIXED}" ]] || fail "Fixed Python 3.11 runtime is missing: ${PYTHON_FIXED}"
[[ -x "${RERANKER_PYTHON_FIXED}" ]] || fail "Fixed reranker virtualenv is missing: ${RERANKER_PYTHON_FIXED}"
[[ -d "${SHIGUANG_REPOSITORY_FIXED}/.git" ]] || fail "Fixed Shiguang repository is missing: ${SHIGUANG_REPOSITORY_FIXED}"
[[ -f "${DATASET_FILE}" ]] || fail "Frozen dataset is missing: ${DATASET_FILE}"

actual_shiguang_commit="$(git -C "${SHIGUANG_REPOSITORY_FIXED}" rev-parse HEAD)"
[[ "${actual_shiguang_commit}" == "${SHIGUANG_COMMIT_FIXED}" ]] || \
  fail "Shiguang commit mismatch: expected ${SHIGUANG_COMMIT_FIXED}, got ${actual_shiguang_commit}"

actual_dataset_sha="$(shasum -a 256 "${DATASET_FILE}" | awk '{print $1}')"
[[ "${actual_dataset_sha}" == "${DATASET_SHA256}" ]] || \
  fail "Dataset SHA-256 mismatch: expected ${DATASET_SHA256}, got ${actual_dataset_sha}"

export JAVA_HOME="${JAVA_HOME_FIXED}"
export PATH="${JAVA_HOME}/bin:${PATH}"
export SHIGUANG_REPOSITORY_PATH="${SHIGUANG_REPOSITORY_FIXED}"
export SPRING_PROFILES_ACTIVE="shiguang-eval"
export RUN_RETRIEVAL_EVAL="true"
export RETRIEVAL_EVAL_DATASET_RESOURCE="${DATASET_RESOURCE}"
export RETRIEVAL_EVAL_WARMUP_RUNS="${WARMUP_RUNS}"
export RETRIEVAL_EVAL_REPETITIONS="${REPETITIONS}"
export RETRIEVAL_EVAL_BASELINE_RESOURCE=""
export BGE_RERANK_URL="${BGE_RERANK_URL_FIXED}"
export BGE_RERANK_PATH="${BGE_RERANK_PATH_FIXED}"
export BGE_RERANK_API_KEY=""
export BGE_RERANK_CONNECT_TIMEOUT_MS="2000"
export BGE_RERANK_READ_TIMEOUT_MS="10000"

# Frozen retrieval configuration. Spring's environment property source overrides application.yml.
export APP_RAG_RETRIEVAL_DENSE_TOP_K="50"
export APP_RAG_RETRIEVAL_SPARSE_TOP_K="50"
export APP_RAG_RETRIEVAL_HYBRID_TOP_K="40"
export APP_RAG_RETRIEVAL_BGE_TOP_K="20"
export APP_RAG_RETRIEVAL_LLM_TOP_K="10"
export LLM_RERANK_ENABLED="false"
export RETRIEVAL_CACHE_TTL_SECONDS="-1"
export RETRIEVAL_CACHE_MAX_ENTRIES="-1"
export EMBEDDING_CACHE_TTL_SECONDS="-1"
export EMBEDDING_CACHE_MAX_ENTRIES="-1"

cd "${ROOT_DIR}"

echo "Checking the fixed Python/Transformers BGE endpoint..."
"${PYTHON_FIXED}" tools/check-bge-reranker.py

echo "Checking the production Java -> BGE HTTP contract..."
RUN_BGE_LIVE_CONTRACT=true ./mvnw -q -Dtest=HttpBgeRerankerLiveIT test

echo "Measuring the controlled sequential -> parallel recall P95 benchmark..."
RETRIEVAL_PARALLEL_BENCHMARK_OUTPUT="${OUTPUT_ROOT}/parallel-recall-benchmark.json" \
  ./mvnw -q -Dtest=RetrievalPipelineTest#parallelRecallP95BeatsSequentialBaselineByThirtyPercent test

rm -rf "${OUTPUT_ROOT}/0.7-baseline" "${OUTPUT_ROOT}/0.8-rerank"
mkdir -p "${OUTPUT_ROOT}"

run_mode() {
  local mode="$1"
  local output="${OUTPUT_ROOT}/${mode}"
  echo "Running ${mode} in an isolated Maven/JVM process..."
  RETRIEVAL_EVAL_MODE="${mode}" \
  RETRIEVAL_EVAL_OUTPUT_DIRECTORY="${output}" \
    ./mvnw -q -Dtest=RetrievalEvaluationIT test
}

# Do not combine these invocations: process-local caches, circuit breakers, and Spring beans must be isolated.
run_mode "0.7-baseline"
run_mode "0.8-rerank"

"${PYTHON_FIXED}" "${ROOT_DIR}/tools/retrieval-eval-comparison.py" \
  --root "${ROOT_DIR}" \
  --output-root "${OUTPUT_ROOT}" \
  --shiguang-repository "${SHIGUANG_REPOSITORY_FIXED}" \
  --shiguang-commit "${SHIGUANG_COMMIT_FIXED}" \
  --dataset "${DATASET_FILE}" \
  --dataset-resource "${DATASET_RESOURCE}" \
  --dataset-sha256 "${DATASET_SHA256}" \
  --java-home "${JAVA_HOME_FIXED}" \
  --reranker-python "${RERANKER_PYTHON_FIXED}" \
  --parallel-benchmark "${OUTPUT_ROOT}/parallel-recall-benchmark.json" \
  --warmup-runs "${WARMUP_RUNS}" \
  --repetitions "${REPETITIONS}"

echo "Formal comparison written to ${OUTPUT_ROOT}/comparison.md"
