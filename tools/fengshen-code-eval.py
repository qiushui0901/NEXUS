#!/usr/bin/env python3
"""封神代码召回评测(NEXUS 侧):500 例走 nexus_search_code,判定 symbolName/filePath 命中。"""
import json
import statistics
import time
import urllib.request
import uuid
from pathlib import Path

ROOT = Path("/Users/user/Documents/request-RAG")
BASE_URL = "http://127.0.0.1:8080"
API_KEY = ""
DATASET = ROOT / "evaluation/fengshen-code-retrieval-eval-500.jsonl"
OUT = ROOT / "target/fengshen-retrieval/nexus-code-report.json"
CHECKPOINT = ROOT / "target/fengshen-retrieval/nexus-code-checkpoint.json"


def request(url, body=None, content_type="application/json", timeout=300, session_id=None):
    headers = {"X-API-Key": API_KEY, "Accept": "application/json, text/event-stream"}
    if body is not None:
        headers["Content-Type"] = content_type
    if session_id:
        headers["Mcp-Session-Id"] = session_id
    req = urllib.request.Request(url, data=body, headers=headers, method="POST" if body is not None else "GET")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, dict(resp.headers.items()), resp.read()


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


def ranked_code_results(response):
    """Return the ranked code result list from an MCP response."""
    if isinstance(response, dict):
        data = response.get("data")
        if isinstance(data, list) and all(isinstance(item, dict) for item in data):
            return data
        for nested in response.values():
            result = ranked_code_results(nested)
            if result:
                return result
    elif isinstance(response, list):
        for nested in response:
            result = ranked_code_results(nested)
            if result:
                return result
    elif isinstance(response, str):
        stripped = response.strip()
        if stripped.startswith(("{", "[")):
            try:
                return ranked_code_results(json.loads(stripped))
            except json.JSONDecodeError:
                return []
    return []


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    _, headers, payload = request(
        f"{BASE_URL}/mcp",
        body=json.dumps({
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                       "clientInfo": {"name": "fengshen-code-eval", "version": "1.0"}},
        }).encode(),
    )
    session_id = next((v for k, v in headers.items() if k.lower() == "mcp-session-id"), "")
    if not session_id:
        raise RuntimeError("no Mcp-Session-Id")

    cases = [json.loads(line) for line in DATASET.read_text(encoding="utf-8").splitlines() if line.strip()]
    results = []
    done_ids = set()
    if CHECKPOINT.exists():
        results = json.loads(CHECKPOINT.read_text(encoding="utf-8")).get("results", [])
        done_ids = {r["id"] for r in results}
        print(f"resuming: {len(results)} done", flush=True)

    for i, case in enumerate(cases, 1):
        if case["id"] in done_ids:
            continue
        gold = case["goldCode"][0]
        t0 = time.perf_counter()
        try:
            response = mcp_call(session_id, 100 + i, "tools/call", {
                "name": "nexus_search_code",
                "arguments": {"query": case["query"], "projectId": "immortal-game-service", "limit": 10},
            })
            error = None
        except Exception as exc:
            response, error = {}, f"{type(exc).__name__}: {exc}"
        latency_ms = (time.perf_counter() - t0) * 1000
        combined = " ".join(response_texts(response))
        hit_symbol = not error and gold["symbolName"] in combined
        hit_file = not error and gold["filePath"] in combined
        ranked = ranked_code_results(response)
        rank = next(
            (index for index, item in enumerate(ranked, 1)
             if gold["symbolName"] in " ".join(response_texts(item))
             and gold["filePath"] in " ".join(response_texts(item))),
            None,
        )
        results.append({
            "id": case["id"], "hitSymbol": hit_symbol, "hitFile": hit_file,
            "latencyMs": round(latency_ms, 1), "rank": rank, "error": error,
            "symbol": gold["symbolName"], "file": gold["filePath"],
            "responseSnippet": combined[:200],
        })
        if len(results) % 10 == 0:
            CHECKPOINT.write_text(json.dumps({"results": results}, ensure_ascii=False), encoding="utf-8")
            print(f"progress {len(results)}/500", flush=True)

    latencies = [r["latencyMs"] for r in results if not r["error"]]
    reciprocal_ranks = [1 / r["rank"] for r in results if r["rank"] is not None]
    summary = {
        "total": len(results),
        "symbolHits": sum(r["hitSymbol"] for r in results),
        "symbolRate": sum(r["hitSymbol"] for r in results) / len(results),
        "fileHits": sum(r["hitFile"] for r in results),
        "fileRate": sum(r["hitFile"] for r in results) / len(results),
        "bothHits": sum(r["hitSymbol"] and r["hitFile"] for r in results),
        "latencyP50Ms": round(statistics.median(latencies), 1) if latencies else None,
        "latencyP95Ms": round(sorted(latencies)[int(len(latencies) * 0.95) - 1], 1) if len(latencies) >= 20 else (max(latencies) if latencies else None),
        "mrr": round(sum(reciprocal_ranks) / len(results), 4) if results else 0,
        "mrrHitCount": len(reciprocal_ranks),
        "errors": [r["id"] for r in results if r["error"]],
    }
    OUT.write_text(json.dumps({"summary": summary, "cases": results}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"report: {OUT}")


if __name__ == "__main__":
    main()
