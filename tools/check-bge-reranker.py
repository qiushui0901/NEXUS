#!/usr/bin/env python3
"""Run a non-sensitive health and contract check against the local BGE reranker."""
from __future__ import annotations

import json
import os
import urllib.error
import urllib.request

BASE_URL = os.getenv("BGE_RERANK_URL", "http://127.0.0.1:8081").rstrip("/")
PATH = os.getenv("BGE_RERANK_PATH", "/rerank")
API_KEY = os.getenv("BGE_RERANK_API_KEY", "")


def request(path: str, body: dict[str, object] | None = None) -> tuple[int, object]:
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode("utf-8")
    if API_KEY:
        headers["Authorization"] = f"Bearer {API_KEY}"
    req = urllib.request.Request(BASE_URL + path, data=data, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {error.code}: {detail[:300]}") from error


def main() -> None:
    health_status, health = request("/health")
    rerank_status, ranked = request(
        PATH,
        {
            "query": "local health check",
            "texts": ["local health check", "unrelated text"],
            "truncate": True,
        },
    )
    if health_status != 200 or rerank_status != 200:
        raise RuntimeError("reranker health check failed")
    if not isinstance(ranked, list) or len(ranked) != 2:
        raise RuntimeError("reranker response must contain two score entries")
    if any(not isinstance(item, dict) or "index" not in item or "score" not in item for item in ranked):
        raise RuntimeError("reranker response does not match the NEXUS contract")
    best = max(ranked, key=lambda item: float(item["score"]))
    if best["index"] != 0:
        raise RuntimeError("reranker did not rank the matching passage first")
    print(
        json.dumps(
            {
                "status": "UP",
                "model": health.get("model") if isinstance(health, dict) else None,
                "device": health.get("device") if isinstance(health, dict) else None,
                "contract": "PASS",
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
