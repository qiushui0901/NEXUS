#!/usr/bin/env python3
"""Generate a versioned, vector-free manifest for code and requirement sources."""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path
from typing import Iterable


def run(repo: Path, *args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=repo, text=True).strip()


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def changed_files(repo: Path, base: str, commit: str) -> list[dict[str, object]]:
    output = run(repo, "diff", "--name-status", "-M", base, commit)
    result: list[dict[str, object]] = []
    for line in output.splitlines():
        fields = line.split("\t")
        if len(fields) < 2:
            continue
        status = fields[0]
        path = fields[-1].replace("\\", "/")
        if not path.endswith(".java"):
            continue
        entry: dict[str, object] = {"status": status, "path": path}
        if status.startswith("R") and len(fields) == 3:
            entry["previousPath"] = fields[1].replace("\\", "/")
        if status[0] != "D":
            try:
                content = subprocess.check_output(
                    ["git", "show", f"{commit}:{path}"], cwd=repo
                )
                entry["contentHash"] = sha256_bytes(content)
                entry["bytes"] = len(content)
            except subprocess.CalledProcessError:
                entry["contentHash"] = None
        result.append(entry)
    return sorted(result, key=lambda item: str(item["path"]))


def requirement_files(root: Path, paths: Iterable[str]) -> list[dict[str, object]]:
    result = []
    for raw in paths:
        path = Path(raw).expanduser().resolve()
        if not path.is_file():
            raise SystemExit(f"requirement source does not exist: {path}")
        try:
            display_path = str(path.relative_to(root))
        except ValueError:
            display_path = str(path)
        result.append({
            "path": display_path,
            "contentHash": sha256_file(path),
            "bytes": path.stat().st_size,
        })
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--commit", default="HEAD")
    parser.add_argument("--project-id", default="immortal-game-service")
    parser.add_argument("--code-version", default="5.1")
    parser.add_argument("--code-collection", default="code_chunks_immortal_game_service_v5_1")
    parser.add_argument("--document-id", default="fengshen")
    parser.add_argument("--requirement-version", default="5.1")
    parser.add_argument("--requirement-source", action="append", default=[])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    repo = args.repo.expanduser().resolve()
    commit = run(repo, "rev-parse", args.commit)
    base = run(repo, "rev-parse", args.base)
    try:
        source_repository = run(repo, "remote", "get-url", "origin")
    except subprocess.CalledProcessError:
        source_repository = ""

    code_files = changed_files(repo, base, commit)
    counts = {
        "added": sum(str(item["status"]).startswith("A") for item in code_files),
        "modified": sum(str(item["status"]).startswith(("M", "R")) for item in code_files),
        "deleted": sum(str(item["status"]).startswith("D") for item in code_files),
    }
    rag_root = Path(__file__).resolve().parent.parent
    manifest = {
        "schemaVersion": 1,
        "projectId": args.project_id,
        "codeVersion": args.code_version,
        "sourceRepository": source_repository,
        "code": {
            "baseCommit": base,
            "sourceCommit": commit,
            "collection": args.code_collection,
            "incremental": True,
            "changedJavaFileCount": len(code_files),
            "changeCounts": counts,
            "files": code_files,
        },
        "requirements": {
            "documentId": args.document_id,
            "version": args.requirement_version,
            "sources": requirement_files(rag_root, args.requirement_source),
        },
        "featureBoundaries": {
            "growthFund": {
                "version": "5.1",
                "implementationSymbols": [
                    "IGrowFundMoaService.growFundIndex",
                    "IGrowFundMoaService.growFundBuy",
                    "GrowFundMoaServiceImpl.growFundIndex",
                    "GrowFundMoaServiceImpl.growFundBuy",
                    "GrowFundService.index",
                    "GrowFundService.canBuy",
                    "GrowFundService.buy",
                ],
                "implementationPathPrefixes": [
                    "immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/",
                    "immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/growFund/",
                ],
                "doNotConfuseWith": [
                    "GrowDiscountService",
                    "成长特价礼包",
                    "v5.0",
                ],
            },
            "growthDiscount": {
                "version": "5.0",
                "implementationSymbols": [
                    "GrowDiscountService.growDiscountBuy",
                    "GrowDiscountService.checkBuy",
                    "GrowDiscountService.doBuy",
                ],
                "doNotUseAsGrowthFundEvidence": True,
            },
        },
        "excludedFromManifest": [
            "Qdrant point payloads",
            "dense/sparse vectors",
            "qdrant-storage",
            "snapshots",
            "credentials",
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({
        "output": str(args.output),
        "baseCommit": base,
        "sourceCommit": commit,
        "changedJavaFileCount": len(code_files),
        "requirementSourceCount": len(args.requirement_source),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
