#!/usr/bin/env python3
"""封神需求文档召回评测(NEXUS 侧):上传合成快照语料,200 例查询走 MCP 检索,判定返回内容包含标准答案原文。"""
import json
import re
import statistics
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path("/Users/user/Documents/request-RAG")
BASE_URL = "http://127.0.0.1:8080"
API_KEY = sys.argv[1] if len(sys.argv) > 1 else ""
CORPUS = ROOT / "evaluation/fengshen-snapshots-merged.md"
DATASET = ROOT / "evaluation/fengshen-document-retrieval-eval-200.jsonl"
OUT = ROOT / "target/fengshen-retrieval/nexus-fengshen-report.json"
CHECKPOINT = ROOT / "target/fengshen-retrieval/nexus-fengshen-checkpoint.json"


def request(url, body=None, content_type="application/json", timeout=300, session_id=None):
    headers = {"X-API-Key": API_KEY, "Accept": "application/json, text/event-stream"}
    if body is not None:
        headers["Content-Type"] = content_type
    if session_id:
        headers["Mcp-Session-Id"] = session_id
    req = urllib.request.Request(url, data=body, headers=headers, method="POST" if body is not None else "GET")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, dict(resp.headers.items()), resp.read()


def multipart(fields: dict[str, str], file_path: Path) -> tuple[str, bytes]:
    boundary = "----nexus-eval-" + uuid.uuid4().hex
    parts = []
    for key, value in fields.items():
        parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{key}\"\r\n\r\n{value}\r\n".encode())
    parts.append(
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{file_path.name}\"\r\n"
        f"Content-Type: text/markdown\r\n\r\n".encode() + file_path.read_bytes() + b"\r\n"
    )
    parts.append(f"--{boundary}--\r\n".encode())
    return f"multipart/form-data; boundary={boundary}", b"".join(parts)


def parse_json_or_sse(payload: bytes) -> dict:
    text = payload.decode("utf-8")
    if text.strip().startswith("{"):
        return json.loads(text)
    data_lines = [line[5:] for line in text.splitlines() if line.startswith("data:")]
    for line in reversed(data_lines):
        try:
            return json.loads(line)
        except json.JSONDecodeError:
            continue
    return {"error": text[:200]}


def mcp_call(session_id, request_id, method, params):
    _, _, payload = request(
        f"{BASE_URL}/mcp",
        body=json.dumps({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}).encode(),
        session_id=session_id,
    )
    return parse_json_or_sse(payload)


def response_texts(value):
    if isinstance(value, dict):
        for nested in value.values():
            yield from response_texts(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from response_texts(nested)
    elif isinstance(value, str):
        yield value
        stripped = value.strip()
        if stripped.startswith(("{", "[")):
            try:
                decoded = json.loads(stripped)
            except json.JSONDecodeError:
                return
            yield from response_texts(decoded)


def ranked_evidence(response):
    """Return the ranked requirement evidence list from an MCP response."""
    if isinstance(response, dict):
        data = response.get("data")
        if isinstance(data, list) and all(isinstance(item, dict) for item in data):
            return data
        for nested in response.values():
            result = ranked_evidence(nested)
            if result:
                return result
    elif isinstance(response, list):
        for nested in response:
            result = ranked_evidence(nested)
            if result:
                return result
    elif isinstance(response, str):
        stripped = response.strip()
        if stripped.startswith(("{", "[")):
            try:
                return ranked_evidence(json.loads(stripped))
            except json.JSONDecodeError:
                return []
    return []


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    content_type, payload = multipart(
        {"projectId": "immortal-game-service", "documentId": "fengshen-doc-eval", "version": "all"},
        CORPUS,
    )
    print("uploading corpus...")
    request(f"{BASE_URL}/api/requirements/documents", body=payload, content_type=content_type)
    print("corpus uploaded, waiting for index settle")
    time.sleep(30)

    _, headers, payload = request(
        f"{BASE_URL}/mcp",
        body=json.dumps({
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                       "clientInfo": {"name": "fengshen-eval", "version": "1.0"}},
        }).encode(),
    )
    initialized = parse_json_or_sse(payload)
    if "result" not in initialized:
        raise RuntimeError(f"MCP initialize failed: {initialized}")
    session_id = next((v for k, v in headers.items() if k.lower() == "mcp-session-id"), "")
    if not session_id:
        raise RuntimeError("no Mcp-Session-Id")
    print("MCP session:", session_id[:12])

    cases = [json.loads(line) for line in DATASET.read_text(encoding="utf-8").splitlines() if line.strip()]
    results = []
    done_ids = set()
    if CHECKPOINT.exists():
        checkpoint = json.loads(CHECKPOINT.read_text(encoding="utf-8"))
        results = checkpoint.get("results", [])
        done_ids = {r["id"] for r in results}
        print(f"resuming from checkpoint: {len(results)} done", flush=True)
    for i, case in enumerate(cases, 1):
        if case["id"] in done_ids:
            continue
        t0 = time.perf_counter()
        try:
            response = mcp_call(session_id, 100 + i, "tools/call", {
                "name": "nexus_search_requirements",
                "arguments": {"query": case["query"], "projectId": "immortal-game-service",
                              "documentId": "fengshen-doc-eval", "version": "all", "limit": 10},
            })
            error = None
        except Exception as exc:
            response, error = {}, f"{type(exc).__name__}: {exc}"
        latency_ms = (time.perf_counter() - t0) * 1000
        combined = " ".join(response_texts(response))
        hit = not error and case["answer"] in combined
        evidence = ranked_evidence(response)
        rank = next(
            (index for index, item in enumerate(evidence, 1)
             if case["answer"] in " ".join(response_texts(item))),
            None,
        )
        results.append({
            "id": case["id"], "queryMode": case["queryMode"], "answerStatus": case["answerStatus"],
            "sheet": case["goldDocument"]["sheet"], "hit": hit,
            "latencyMs": round(latency_ms, 1), "rank": rank, "error": error,
            "answer": case["answer"][:60],
            "responseSnippet": combined[:200],
        })
        if len(results) % 10 == 0:
            CHECKPOINT.write_text(json.dumps({"results": results}, ensure_ascii=False), encoding="utf-8")
            print(f"progress {len(results)}/200", flush=True)

    answered = [r for r in results if r["answerStatus"] == "ANSWERED"]
    pending = [r for r in results if r["answerStatus"] != "ANSWERED"]
    latencies = [r["latencyMs"] for r in results if not r["error"]]
    reciprocal_ranks = [1 / r["rank"] for r in results if r["rank"] is not None]
    summary = {
        "total": len(results),
        "hits": sum(r["hit"] for r in results),
        "answeredTotal": len(answered),
        "answeredHits": sum(r["hit"] for r in answered),
        "answeredRate": sum(r["hit"] for r in answered) / len(answered),
        "pendingTotal": len(pending),
        "pendingHits": sum(r["hit"] for r in pending),
        "byQueryMode": {m: {"hits": sum(r["hit"] for r in results if r["queryMode"] == m),
                            "total": sum(1 for r in results if r["queryMode"] == m)}
                        for m in ("REQUIREMENT", "BUSINESS_TERM")},
        "latencyP50Ms": round(statistics.median(latencies), 1) if latencies else None,
        "latencyP95Ms": round(sorted(latencies)[int(len(latencies) * 0.95) - 1], 1) if len(latencies) >= 20 else (max(latencies) if latencies else None),
        "mrr": round(sum(reciprocal_ranks) / len(results), 4) if results else 0,
        "mrrHitCount": len(reciprocal_ranks),
        "rankedEvidenceCases": sum(1 for r in results if r["rank"] is not None),
        "errors": [r["id"] for r in results if r["error"]],
    }
    OUT.write_text(json.dumps({"summary": summary, "cases": results}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"report: {OUT}")


if __name__ == "__main__":
    main()
