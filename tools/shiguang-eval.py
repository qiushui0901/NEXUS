#!/usr/bin/env python3
"""Prepare and smoke-test the sanitized Shiguang evaluation project."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path


PROJECT_ID = "shiguang-eval"
DOCUMENT_ID = "shiguang-eval-requirements"
VERSION = "shiguang-eval-v1"
SOURCE_FILE = (
    "shiguang-auth/src/main/java/com/quanshiguang/shiguang/auth/"
    "service/impl/AuthServiceImpl.java"
)
ROOT = Path(__file__).resolve().parents[1]
REQUIREMENTS = ROOT / "evaluation/shiguang/shiguang-eval-requirements.md"
DATASET = ROOT / "src/test/resources/evaluation/retrieval-eval-shiguang-v1.jsonl"


def request(
    url: str,
    api_key: str,
    *,
    body: bytes | None = None,
    content_type: str = "application/json",
    session_id: str | None = None,
    timeout: int = 120,
) -> tuple[int, dict[str, str], bytes]:
    headers = {
        "X-API-Key": api_key,
        "Accept": "application/json, text/event-stream",
    }
    if body is not None:
        headers["Content-Type"] = content_type
    if session_id:
        headers["Mcp-Session-Id"] = session_id
    method = "POST" if body is not None else "GET"
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return response.status, dict(response.headers.items()), response.read()
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {error.code} from {url}: {detail[:500]}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"Cannot reach {url}: {error.reason}") from error


def multipart(fields: dict[str, str], file_path: Path) -> tuple[str, bytes]:
    boundary = f"----nexus-{uuid.uuid4().hex}"
    chunks: list[bytes] = []
    for name, value in fields.items():
        chunks.extend(
            [
                f"--{boundary}\r\n".encode(),
                f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode(),
                value.encode("utf-8"),
                b"\r\n",
            ]
        )
    chunks.extend(
        [
            f"--{boundary}\r\n".encode(),
            (
                f'Content-Disposition: form-data; name="file"; '
                f'filename="{file_path.name}"\r\n'
            ).encode(),
            b"Content-Type: text/markdown; charset=utf-8\r\n\r\n",
            file_path.read_bytes(),
            b"\r\n",
            f"--{boundary}--\r\n".encode(),
        ]
    )
    return f"multipart/form-data; boundary={boundary}", b"".join(chunks)


def json_body(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False).encode("utf-8")


def parse_json_or_sse(payload: bytes) -> dict:
    text = payload.decode("utf-8", errors="replace").strip()
    if not text:
        return {}
    if text.startswith("{"):
        return json.loads(text)
    for line in reversed(text.splitlines()):
        if line.startswith("data:"):
            return json.loads(line.removeprefix("data:").strip())
    raise RuntimeError(f"Unexpected MCP response: {text[:500]}")


def required_environment(args: argparse.Namespace) -> tuple[str, str, Path]:
    api_key = os.environ.get("NEXUS_API_KEY", "")
    if not api_key:
        raise RuntimeError("Set NEXUS_API_KEY in the current shell")
    repository_value = args.repository or os.environ.get("SHIGUANG_REPOSITORY_PATH", "")
    if not repository_value:
        raise RuntimeError("Set SHIGUANG_REPOSITORY_PATH or pass --repository")
    repository = Path(repository_value).expanduser().resolve()
    if not (repository / ".git").is_dir() or not (repository / SOURCE_FILE).is_file():
        raise RuntimeError(f"Not a compatible Shiguang repository: {repository}")
    validate_gold_labels(repository)
    return args.base_url.rstrip("/"), api_key, repository


def validate_gold_labels(repository: Path) -> None:
    for line_number, line in enumerate(DATASET.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        case = json.loads(line)
        for gold in case.get("goldCode", []):
            relative = str(gold.get("filePath", ""))
            candidate = (repository / relative).resolve()
            if repository not in candidate.parents or not candidate.is_file():
                raise RuntimeError(
                    f"Invalid Gold code path at dataset line {line_number}: {relative}"
                )
            if "/resources/" in f"/{relative.replace(os.sep, '/')}":
                raise RuntimeError(
                    f"Forbidden resource/config Gold path at dataset line {line_number}"
                )
            symbol = str(gold.get("symbolName", ""))
            source = candidate.read_text(encoding="utf-8", errors="replace")
            if not re.search(rf"\b{re.escape(symbol)}\s*\(", source):
                raise RuntimeError(
                    f"Gold symbol not found at dataset line {line_number}: {symbol}"
                )


def prepare(base_url: str, api_key: str) -> None:
    content_type, payload = multipart(
        {
            "projectId": PROJECT_ID,
            "documentId": DOCUMENT_ID,
            "version": VERSION,
        },
        REQUIREMENTS,
    )
    request(
        f"{base_url}/api/requirements/documents",
        api_key,
        body=payload,
        content_type=content_type,
        timeout=180,
    )
    print(f"Requirement corpus uploaded: {DOCUMENT_ID}@{VERSION}")
    _, _, response = request(
        f"{base_url}/api/code/index?projectId={PROJECT_ID}",
        api_key,
        body=b"",
        timeout=1800,
    )
    indexed = json.loads(response.decode("utf-8")) if response else {}
    print(
        "Code index completed: "
        f"files={indexed.get('filesIndexed', indexed.get('indexedFiles', 'unknown'))}"
    )


def mcp_call(
    base_url: str,
    api_key: str,
    session_id: str,
    request_id: int,
    method: str,
    params: dict,
) -> dict:
    _, _, payload = request(
        f"{base_url}/mcp",
        api_key,
        body=json_body(
            {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}
        ),
        session_id=session_id,
    )
    response = parse_json_or_sse(payload)
    if "error" in response:
        raise RuntimeError(f"MCP {method} failed: {response['error']}")
    return response


def response_values(value: object):
    """Yield scalar response values, decoding JSON embedded in MCP text content."""
    if isinstance(value, dict):
        for nested in value.values():
            yield from response_values(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from response_values(nested)
    elif isinstance(value, str):
        yield value
        stripped = value.strip()
        if stripped.startswith(("{", "[")):
            try:
                decoded = json.loads(stripped)
            except json.JSONDecodeError:
                return
            yield from response_values(decoded)


def evidence_ids(response: dict, prefix: str) -> list[str]:
    pattern = re.compile(rf"{re.escape(prefix)}:[A-Za-z0-9._:-]+")
    return list(dict.fromkeys(match.group(0) for value in response_values(response)
                              for match in pattern.finditer(value)))


def warning_codes(*responses: dict) -> list[str]:
    pattern = re.compile(r"(?:BGE|MCP|EMBEDDING|RERANK|RETRIEVAL)_[A-Z0-9_]+")
    return list(dict.fromkeys(match.group(0) for response in responses
                              for value in response_values(response)
                              for match in pattern.finditer(value)))


def write_smoke_evidence(requirement_id: str, code_id: str, source_id: str,
                         source_path: str, warnings: list[str]) -> Path:
    output = ROOT / "target/retrieval-evaluation/mcp-smoke.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    summary = {
        "projectId": PROJECT_ID,
        "requirementEvidenceId": requirement_id,
        "codeEvidenceId": code_id,
        "sourceEvidenceId": source_id,
        "sourcePath": source_path,
        "warningCodes": warnings,
    }
    output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return output


def smoke(base_url: str, api_key: str) -> None:
    _, headers, payload = request(
        f"{base_url}/mcp",
        api_key,
        body=json_body(
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2025-06-18",
                    "capabilities": {},
                    "clientInfo": {"name": "shiguang-eval", "version": "1.0"},
                },
            }
        ),
    )
    initialized = parse_json_or_sse(payload)
    if "result" not in initialized:
        raise RuntimeError("MCP initialize did not return a result")
    session_id = next(
        (value for key, value in headers.items() if key.lower() == "mcp-session-id"),
        "",
    )
    if not session_id:
        raise RuntimeError("MCP initialize did not return Mcp-Session-Id")
    request(
        f"{base_url}/mcp",
        api_key,
        body=json_body(
            {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}}
        ),
        session_id=session_id,
    )

    requirement = mcp_call(
        base_url,
        api_key,
        session_id,
        2,
        "tools/call",
        {
            "name": "nexus_search_requirements",
            "arguments": {
                "query": "登录注册共用入口如何校验验证码并签发令牌",
                "projectId": PROJECT_ID,
                "documentId": DOCUMENT_ID,
                "version": VERSION,
                "limit": 5,
            },
        },
    )
    code = mcp_call(
        base_url,
        api_key,
        session_id,
        3,
        "tools/call",
        {
            "name": "nexus_search_code",
            "arguments": {
                "query": "手机号验证码登录注册并签发访问令牌",
                "projectId": PROJECT_ID,
                "limit": 5,
            },
        },
    )
    source = mcp_call(
        base_url,
        api_key,
        session_id,
        4,
        "tools/call",
        {
            "name": "nexus_get_source",
            "arguments": {
                "projectId": PROJECT_ID,
                "filePath": SOURCE_FILE,
                "startLine": 45,
                "endLine": 75,
            },
        },
    )

    requirement_ids = evidence_ids(requirement, "requirement")
    code_ids = evidence_ids(code, "code")
    source_ids = evidence_ids(source, "code")
    source_paths = [value for value in response_values(source) if SOURCE_FILE in value]
    if not requirement_ids:
        raise RuntimeError("Requirement smoke did not return a requirement:* evidence ID")
    if not code_ids or not source_ids:
        raise RuntimeError("Code smoke did not return a code:* evidence ID")
    if not source_paths:
        raise RuntimeError("Source smoke did not return the requested repository-relative path")

    warnings = warning_codes(requirement, code, source)
    evidence_file = write_smoke_evidence(
        requirement_ids[0], code_ids[0], source_ids[0], SOURCE_FILE, warnings
    )
    print(f"MCP smoke passed: requirement evidence={requirement_ids[0]}")
    print(f"MCP smoke passed: code evidence={code_ids[0]}")
    print(f"MCP smoke passed: source evidence={source_ids[0]} path={SOURCE_FILE}")
    print(f"MCP smoke evidence written to {evidence_file}")
    if warnings:
        print(f"MCP smoke warnings: {', '.join(warnings)}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("prepare", "smoke", "all"))
    parser.add_argument(
        "--base-url",
        default=os.environ.get("NEXUS_BASE_URL", "http://127.0.0.1:8080"),
    )
    parser.add_argument("--repository")
    args = parser.parse_args()
    try:
        base_url, api_key, _ = required_environment(args)
        if args.command in {"prepare", "all"}:
            prepare(base_url, api_key)
        if args.command in {"smoke", "all"}:
            smoke(base_url, api_key)
        return 0
    except (RuntimeError, OSError, json.JSONDecodeError) as error:
        print(f"shiguang-eval: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
