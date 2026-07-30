#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV_DIR="${BGE_RERANK_VENV_DIR:-${ROOT_DIR}/.venv-bge-reranker}"
REQUIREMENTS="${ROOT_DIR}/tools/requirements-bge-reranker.txt"
SERVICE="${ROOT_DIR}/tools/bge-reranker-service.py"

if [[ -n "${BGE_RERANK_PYTHON:-}" ]]; then
  PYTHON_BIN="${BGE_RERANK_PYTHON}"
elif command -v python3.11 >/dev/null 2>&1; then
  PYTHON_BIN="$(command -v python3.11)"
else
  echo "Python 3.11 is required. Set BGE_RERANK_PYTHON to a Python 3.10-3.13 executable." >&2
  exit 1
fi

"${PYTHON_BIN}" -c 'import sys; assert (3, 10) <= sys.version_info[:2] < (3, 14), "Python 3.10-3.13 is required"'

if [[ ! -x "${VENV_DIR}/bin/python" ]]; then
  echo "Creating reranker environment at ${VENV_DIR}"
  "${PYTHON_BIN}" -m venv "${VENV_DIR}"
fi

STAMP="${VENV_DIR}/.requirements.sha256"
EXPECTED_HASH="$(shasum -a 256 "${REQUIREMENTS}" | awk '{print $1}')"
CURRENT_HASH="$(cat "${STAMP}" 2>/dev/null || true)"
if [[ "${EXPECTED_HASH}" != "${CURRENT_HASH}" ]]; then
  "${VENV_DIR}/bin/python" -m pip install --upgrade pip
  "${VENV_DIR}/bin/python" -m pip install -r "${REQUIREMENTS}"
  printf '%s' "${EXPECTED_HASH}" > "${STAMP}"
fi

exec "${VENV_DIR}/bin/python" "${SERVICE}" "$@"
