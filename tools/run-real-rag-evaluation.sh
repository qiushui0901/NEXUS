#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

: "${SPRING_PROFILES_ACTIVE:=shiguang-eval}"
: "${RUN_RETRIEVAL_EVAL:=true}"
: "${RETRIEVAL_EVAL_DATASET_RESOURCE:=evaluation/retrieval-eval-enterprise-v2.jsonl}"
: "${RETRIEVAL_EVAL_BASELINE_RESOURCE:=evaluation/retrieval-threshold-enterprise-v0.8.6.json}"
: "${RETRIEVAL_EVAL_MODE:=0.8.6-enterprise}"

export SPRING_PROFILES_ACTIVE
export RUN_RETRIEVAL_EVAL
export RETRIEVAL_EVAL_DATASET_RESOURCE
export RETRIEVAL_EVAL_BASELINE_RESOURCE
export RETRIEVAL_EVAL_MODE

if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  export JAVA_HOME
fi
if ! command -v java >/dev/null 2>&1; then
  echo "未找到 Java，请安装 JDK 21 或设置 JAVA_HOME。" >&2
  exit 1
fi

echo "开始真实 RAG 评测：${RETRIEVAL_EVAL_MODE}"
echo "数据集：${RETRIEVAL_EVAL_DATASET_RESOURCE}"
echo "报告目录：${RETRIEVAL_EVAL_OUTPUT_DIRECTORY:-target/retrieval-evaluation/${RETRIEVAL_EVAL_MODE}}"

./mvnw -B -Dtest=RetrievalEvaluationIT test
