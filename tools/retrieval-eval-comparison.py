#!/usr/bin/env python3
"""Validate frozen retrieval reports and produce a reproducible 0.7 -> 0.8 comparison."""

from __future__ import annotations

import argparse
import hashlib
import json
import platform
import subprocess
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

EXPECTED_PROFILES = {
    "DEVELOPMENT_PLAN": 30,
    "REQUIREMENT_REVIEW": 12,
    "WIKI_BUILD": 12,
}
REQUIRED_TAGS = {
    "normal-recall",
    "version-leakage",
    "similar-feature",
    "empty-result",
    "dependency-degradation",
    "cross-project-contamination",
}
BASELINE_MODE = "0.7-baseline"
RERANK_MODE = "0.8-rerank"
QUALITY_MODE = "0.8.1-quality"
QUALITY_DOCUMENT_RECALL_MIN = 0.504167
QUALITY_CODE_RECALL_MIN = 0.788095
QUALITY_MRR_MIN = 0.525617
QUALITY_P95_MAX_MS = 5131
MODEL = "BAAI/bge-reranker-v2-m3"
PARALLEL_BENCHMARK_CLASSIFICATION = "controlled-fake-dependency"
PARALLEL_BENCHMARK_PROFILE = "DEVELOPMENT_PLAN"
PARALLEL_BENCHMARK_BRANCH_COUNT = 3
PARALLEL_BENCHMARK_BRANCH_DELAY_MS = 100
REQUIRED_PARALLEL_P95_REDUCTION = 0.30


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"Cannot read JSON {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ValueError(f"Expected a JSON object in {path}")
    return value


def command(*args: str, cwd: Path | None = None) -> str:
    completed = subprocess.run(
        args, cwd=cwd, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT
    )
    return completed.stdout.strip()


def validate_dataset(path: Path, expected_sha: str) -> dict[str, Any]:
    payload = path.read_bytes()
    actual_sha = sha256_bytes(payload)
    if actual_sha != expected_sha:
        raise ValueError(f"Dataset SHA-256 mismatch: expected {expected_sha}, got {actual_sha}")
    rows: list[dict[str, Any]] = []
    for line_number, line in enumerate(payload.decode("utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"Invalid dataset JSON at line {line_number}: {exc}") from exc
        if not isinstance(value, dict):
            raise ValueError(f"Dataset line {line_number} is not an object")
        rows.append(value)
    profiles = Counter(str(row.get("profile", "")) for row in rows)
    tags = Counter(tag for row in rows for tag in row.get("tags", []))
    ids = [str(row.get("id", "")) for row in rows]
    queries = [str(row.get("query", "")) for row in rows]
    errors: list[str] = []
    if len(rows) != 54:
        errors.append(f"expected 54 cases, got {len(rows)}")
    if dict(profiles) != EXPECTED_PROFILES:
        errors.append(f"profile distribution mismatch: {dict(profiles)}")
    missing_tags = sorted(REQUIRED_TAGS.difference(tags))
    if missing_tags:
        errors.append(f"missing required tags: {', '.join(missing_tags)}")
    if len(ids) != len(set(ids)):
        errors.append("case IDs are not unique")
    if len(queries) != len(set(queries)):
        errors.append("queries are not unique")
    if any("shiguang-real" not in row.get("tags", []) for row in rows):
        errors.append("every case must contain the shiguang-real tag")
    if errors:
        raise ValueError("Frozen dataset contract failed: " + "; ".join(errors))
    return {
        "path": str(path),
        "sha256": actual_sha,
        "caseCount": len(rows),
        "profiles": dict(sorted(profiles.items())),
        "requiredTags": sorted(REQUIRED_TAGS),
    }


def report_contract(report: dict[str, Any], mode: str, repetitions: int) -> list[str]:
    errors: list[str] = []
    if report.get("mode") != mode:
        errors.append(f"mode must be {mode}")
    if report.get("classification") != "formal":
        errors.append("classification must be formal")
    if report.get("datasetCaseCount") != 54:
        errors.append("datasetCaseCount must be 54")
    if report.get("cutoff") != 10:
        errors.append("cutoff must be 10")
    if report.get("repetitions") != repetitions:
        errors.append(f"repetitions must be {repetitions}")
    summary = report.get("summary") or {}
    if summary.get("totalCases") != 54 * repetitions:
        errors.append(f"summary.totalCases must be {54 * repetitions}")
    if summary.get("infrastructureFailureCases") != 0:
        errors.append("infrastructureFailureCases must be 0")
    profiles = report.get("profiles") or {}
    for profile, count in EXPECTED_PROFILES.items():
        total = (profiles.get(profile) or {}).get("totalCases")
        if total != count * repetitions:
            errors.append(f"{profile}.totalCases must be {count * repetitions}")
    if mode == "0.8.1-quality":
        for field in ("bgeCalls", "bgeSuccesses", "bgeDegradations",
                      "bgeNoCandidateSkips", "bgeSingletonSkips"):
            if not isinstance(summary.get(field), int):
                errors.append(f"summary.{field} must be an integer")
        cases = report.get("cases")
        if not isinstance(cases, list) or len(cases) != 54 * repetitions:
            errors.append(f"cases must contain {54 * repetitions} stage-diagnostic rows")
        else:
            required = {
                "documentRawRank", "documentRerankInputRank", "documentRerankedRank",
                "documentRank", "documentRankMovement", "documentOrderChanged",
                "codeRawRank", "codeRankedRank", "codeRank", "codeRankMovement",
                "codeOrderChanged", "documentRawCandidateCount",
                "documentRerankCandidateCount", "documentRerankedCandidateCount",
                "codeRawCandidateCount", "codeRankedCandidateCount",
                "failureAttributions",
            }
            for index, case in enumerate(cases):
                missing = sorted(required.difference(case))
                if missing:
                    errors.append(f"cases[{index}] missing diagnostics: {', '.join(missing)}")
                    continue
                if case.get("expectsDocuments") and not case.get("documentTraceAvailable"):
                    errors.append(f"cases[{index}] document trace must be available")
                if case.get("expectsCode") and not case.get("codeTraceAvailable"):
                    errors.append(f"cases[{index}] code trace must be available")
                attributions = case.get("failureAttributions")
                if not case.get("success") and (not isinstance(attributions, list) or not attributions):
                    errors.append(f"cases[{index}] failed without stage attribution")
        if not isinstance(report.get("failureAttributions"), dict):
            errors.append("failureAttributions summary must be an object")
    return errors


def parallel_benchmark_contract(report: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if report.get("schemaVersion") != 1:
        errors.append("schemaVersion must be 1")
    if report.get("classification") != PARALLEL_BENCHMARK_CLASSIFICATION:
        errors.append(f"classification must be {PARALLEL_BENCHMARK_CLASSIFICATION}")
    if report.get("profile") != PARALLEL_BENCHMARK_PROFILE:
        errors.append(f"profile must be {PARALLEL_BENCHMARK_PROFILE}")
    if report.get("branchCount") != PARALLEL_BENCHMARK_BRANCH_COUNT:
        errors.append(f"branchCount must be {PARALLEL_BENCHMARK_BRANCH_COUNT}")
    if report.get("branchDelayMs") != PARALLEL_BENCHMARK_BRANCH_DELAY_MS:
        errors.append(f"branchDelayMs must be {PARALLEL_BENCHMARK_BRANCH_DELAY_MS}")

    warmup_runs = report.get("warmupRuns")
    if not isinstance(warmup_runs, int) or isinstance(warmup_runs, bool) or warmup_runs < 1:
        errors.append("warmupRuns must be an integer >= 1")
    repetitions = report.get("repetitions")
    if not isinstance(repetitions, int) or isinstance(repetitions, bool) or repetitions < 5:
        errors.append("repetitions must be an integer >= 5")

    required_reduction = report.get("requiredReduction")
    if (
        not isinstance(required_reduction, (int, float))
        or isinstance(required_reduction, bool)
        or abs(float(required_reduction) - REQUIRED_PARALLEL_P95_REDUCTION) > 1e-12
    ):
        errors.append(f"requiredReduction must be {REQUIRED_PARALLEL_P95_REDUCTION:.2f}")

    sequential_p95 = report.get("sequentialP95Ms")
    parallel_p95 = report.get("parallelP95Ms")
    valid_p95 = True
    if (
        not isinstance(sequential_p95, (int, float))
        or isinstance(sequential_p95, bool)
        or float(sequential_p95) <= 0
    ):
        errors.append("sequentialP95Ms must be a positive number")
        valid_p95 = False
    if (
        not isinstance(parallel_p95, (int, float))
        or isinstance(parallel_p95, bool)
        or float(parallel_p95) <= 0
    ):
        errors.append("parallelP95Ms must be a positive number")
        valid_p95 = False

    if valid_p95:
        recomputed_reduction = 1.0 - float(parallel_p95) / float(sequential_p95)
        if recomputed_reduction + 1e-12 < REQUIRED_PARALLEL_P95_REDUCTION:
            errors.append(
                "recomputed P95 reduction must be at least "
                f"{REQUIRED_PARALLEL_P95_REDUCTION * 100:.0f}%"
            )
        serialized_reduction = report.get("reduction")
        if (
            not isinstance(serialized_reduction, (int, float))
            or isinstance(serialized_reduction, bool)
            or abs(float(serialized_reduction) - recomputed_reduction) > 1e-6
        ):
            errors.append("reduction must match the value recomputed from P95")
    if report.get("passed") is not True:
        errors.append("passed must be true")
    return errors


def parallel_benchmark_summary(report: dict[str, Any]) -> dict[str, Any]:
    sequential_p95 = report.get("sequentialP95Ms", 0)
    parallel_p95 = report.get("parallelP95Ms", 0)
    reduction = None
    if (
        isinstance(sequential_p95, (int, float))
        and not isinstance(sequential_p95, bool)
        and float(sequential_p95) > 0
        and isinstance(parallel_p95, (int, float))
        and not isinstance(parallel_p95, bool)
    ):
        reduction = 1.0 - float(parallel_p95) / float(sequential_p95)
    return {
        "classification": report.get("classification"),
        "profile": report.get("profile"),
        "branchCount": report.get("branchCount"),
        "branchDelayMs": report.get("branchDelayMs"),
        "warmupRuns": report.get("warmupRuns"),
        "repetitions": report.get("repetitions"),
        "sequentialP95Ms": sequential_p95,
        "parallelP95Ms": parallel_p95,
        "reduction": reduction,
        "requiredReduction": REQUIRED_PARALLEL_P95_REDUCTION,
    }


def metric_change(before: float | int, after: float | int) -> dict[str, Any]:
    absolute = after - before
    relative = None if before == 0 else absolute / before
    return {"baseline": before, "rerank": after, "absolute": absolute, "relative": relative}


def non_regression_check(
    name: str,
    baseline: dict[str, Any],
    rerank: dict[str, Any],
    metric: str,
) -> dict[str, Any]:
    before = float(baseline.get(metric, 0))
    after = float(rerank.get(metric, 0))
    return {
        "name": name,
        "passed": after + 1e-12 >= before,
        "detail": f"{before:.6f} -> {after:.6f}",
    }


def healthy_bge_decisions(summary: dict[str, Any], live_contract_verified: bool) -> bool:
    def count(name: str, default: int = 0) -> int:
        value = summary.get(name, default)
        return value if isinstance(value, int) and not isinstance(value, bool) else default

    calls = count("bgeCalls")
    successes = count("bgeSuccesses")
    degradations = count("bgeDegradations")
    no_candidate_skips = count("bgeNoCandidateSkips")
    singleton_skips = count("bgeSingletonSkips")
    total_cases = count("totalCases", -1)
    exercised_or_proven = calls > 0 or singleton_skips > 0 and live_contract_verified
    return (
        successes == calls
        and degradations == 0
        and calls + no_candidate_skips + singleton_skips == total_cases
        and exercised_or_proven
    )


def summarize_changes(baseline: dict[str, Any], rerank: dict[str, Any]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    keys = (
        "documentRecallAt10",
        "codeRecallAt10",
        "mrrAt10",
        "mixedBothHitRate",
        "noResultAccuracy",
        "p50LatencyMs",
        "p95LatencyMs",
        "failedCases",
        "bgeCalls",
        "bgeSuccesses",
        "bgeDegradations",
        "bgeNoCandidateSkips",
        "bgeSingletonSkips",
    )
    for key in keys:
        result[key] = metric_change(baseline.get(key, 0), rerank.get(key, 0))
    return result


def executable_path(path: Path) -> Path:
    """Return an absolute executable path without dereferencing virtualenv symlinks."""
    return path.expanduser().absolute()


def required_package_version(python: Path, package: str) -> str:
    script = (
        "import importlib.metadata as m; "
        f"print(m.version({package!r}))"
    )
    try:
        version = command(str(python), "-c", script)
    except (OSError, subprocess.CalledProcessError) as exc:
        raise ValueError(
            f"Required reranker package {package!r} is unavailable in {python}"
        ) from exc
    if not version:
        raise ValueError(
            f"Required reranker package {package!r} returned an empty version in {python}"
        )
    return version


def evaluation_source_paths(root: Path) -> list[Path]:
    return [
        root / "src/test/java/com/example/requirementrag/evaluation/RetrievalEvaluationIT.java",
        root / "src/test/java/com/example/requirementrag/evaluation/RetrievalEvaluationSetupIT.java",
        root / "src/test/java/com/example/requirementrag/evaluation/RetrievalEvaluationMatcher.java",
        root / "src/test/java/com/example/requirementrag/evaluation/RetrievalEvaluationReport.java",
        root / "src/test/java/com/example/requirementrag/evaluation/RetrievalEvaluationSettings.java",
        root / "src/test/java/com/example/requirementrag/retrieval/pipeline/RetrievalPipelineTest.java",
        root / "src/test/java/com/example/requirementrag/rerank/HttpBgeRerankerLiveIT.java",
        root / "src/main/java/com/example/requirementrag/code/CodeQdrantStore.java",
        root / "src/main/java/com/example/requirementrag/config/AiConfiguration.java",
        root / "src/main/java/com/example/requirementrag/config/RagProperties.java",
        root / "src/main/java/com/example/requirementrag/rerank/HttpBgeReranker.java",
        root / "src/main/java/com/example/requirementrag/retrieval/pipeline/DefaultRequirementReranker.java",
        root / "src/main/java/com/example/requirementrag/retrieval/pipeline/RetrievalPipeline.java",
        root / "src/main/resources/application.yml",
        root / "src/main/resources/application-shiguang-eval.yml",
        root / "src/test/resources/evaluation/retrieval-eval-shiguang-v1.jsonl",
        root / "scripts/run-shiguang-eval.sh",
        root / "tools/bge-reranker-service.py",
        root / "tools/check-bge-reranker.py",
        root / "tools/requirements-bge-reranker.txt",
        root / "tools/retrieval-eval-comparison.py",
        root / "tools/start-bge-reranker.sh",
    ]


def source_state(root: Path) -> dict[str, Any]:
    commit = command("git", "rev-parse", "HEAD", cwd=root)
    status = command("git", "status", "--porcelain", cwd=root)
    relevant = evaluation_source_paths(root)
    digest = hashlib.sha256()
    file_hashes: dict[str, str] = {}
    for path in relevant:
        data = path.read_bytes()
        relative = str(path.relative_to(root))
        file_hashes[relative] = sha256_bytes(data)
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(data)
    return {
        "gitCommit": commit,
        "workingTreeDirty": bool(status),
        "evaluationSourceSha256": digest.hexdigest(),
        "files": file_hashes,
    }


def build_manifest(args: argparse.Namespace, dataset: dict[str, Any]) -> dict[str, Any]:
    root = args.root.resolve()
    java_version = command(str(args.java_home / "bin/java"), "-version").splitlines()[0]
    python_executable = executable_path(args.reranker_python)
    if not python_executable.is_file():
        raise ValueError(f"Reranker Python executable does not exist: {python_executable}")
    shiguang_actual = command("git", "rev-parse", "HEAD", cwd=args.shiguang_repository)
    if shiguang_actual != args.shiguang_commit:
        raise ValueError(
            f"Shiguang commit mismatch: expected {args.shiguang_commit}, got {shiguang_actual}"
        )
    return {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "dataset": {**dataset, "resource": args.dataset_resource},
        "source": source_state(root),
        "corpus": {
            "repository": str(args.shiguang_repository.resolve()),
            "gitCommit": shiguang_actual,
            "projectId": "shiguang-eval",
            "documentId": "shiguang-eval-requirements",
            "version": "shiguang-eval-v1",
            "requirementCollection": "requirements_shiguang_eval",
            "codeCollection": "code_shiguang_eval",
        },
        "runtime": {
            "os": platform.platform(),
            "javaHome": str(args.java_home),
            "javaVersion": java_version,
            "pythonExecutable": str(python_executable),
            "pythonVersion": command(str(python_executable), "--version"),
            "torchVersion": required_package_version(python_executable, "torch"),
            "transformersVersion": required_package_version(python_executable, "transformers"),
            "rerankerModel": MODEL,
            "rerankerDevice": args.bge_device,
        },
        "configuration": {
            "common": {
                "profiles": sorted(EXPECTED_PROFILES),
                "finalTopK": 10,
                "denseTopK": 50,
                "sparseTopK": 50,
                "hybridTopK": 40,
                "bgeTopK": 20,
                "llmTopK": 10,
                "llmRerankEnabled": False,
                "retrievalCacheTtlSeconds": -1,
                "retrievalCacheMaxEntries": -1,
                "embeddingCacheTtlSeconds": args.embedding_cache_ttl_seconds,
                "embeddingCacheMaxEntries": args.embedding_cache_max_entries,
                "warmupRuns": args.warmup_runs,
                "repetitions": args.repetitions,
                "bgeBaseUrl": "http://127.0.0.1:8081",
                "bgePath": "/rerank",
                "bgeConnectTimeoutMs": 2000,
                "bgeReadTimeoutMs": args.bge_read_timeout_ms,
                "bgeLiveContractVerified": args.bge_live_contract_verified,
                "bgeAuthentication": "none",
                "bgeDevice": args.bge_device,
                "bgeMaxLength": args.bge_max_length,
                "bgeBatchSize": args.bge_batch_size,
            },
            "variants": {
                args.baseline_mode: {
                    "requirementReranker": (
                        "passthrough" if args.baseline_mode == BASELINE_MODE
                        else "DefaultRequirementReranker"
                    ),
                    "bgeExpected": args.baseline_mode != BASELINE_MODE,
                    "childFirstRerankEnabled": args.baseline_mode == QUALITY_MODE,
                    "enrichedBgePassageEnabled": args.baseline_mode == QUALITY_MODE,
                    "codeQueryExpansionEnabled": args.baseline_mode == QUALITY_MODE,
                },
                args.candidate_mode: {
                    "requirementReranker": "DefaultRequirementReranker",
                    "bgeExpected": True,
                    "singletonBgeSkipEnabled": args.candidate_mode == QUALITY_MODE,
                    "childFirstRerankEnabled": args.candidate_mode == QUALITY_MODE,
                    "enrichedBgePassageEnabled": args.candidate_mode == QUALITY_MODE,
                    "codeQueryExpansionEnabled": args.candidate_mode == QUALITY_MODE,
                },
            },
        },
        "setup": read_json(args.setup_manifest) if args.setup_manifest else None,
        "secretsRecorded": False,
    }


def render_markdown(comparison: dict[str, Any]) -> str:
    acceptance = comparison["acceptance"]
    lines = [
        f"# Retrieval Evaluation {comparison.get('baselineMode', BASELINE_MODE)} → "
        f"{comparison.get('candidateMode', RERANK_MODE)}",
        "",
        f"- Classification: `{comparison['classification']}`",
        f"- Acceptance: `{'PASS' if acceptance['passed'] else 'FAIL'}`",
        f"- Dataset SHA-256: `{comparison['datasetSha256']}`",
        f"- Repetitions: {comparison['repetitions']}",
        "",
        "## Overall",
        "",
        f"| Metric | {comparison.get('baselineMode', BASELINE_MODE)} | "
        f"{comparison.get('candidateMode', RERANK_MODE)} | Absolute | Relative |",
        "|---|---:|---:|---:|---:|",
    ]
    labels = {
        "documentRecallAt10": "Document Recall@10",
        "codeRecallAt10": "Code Recall@10",
        "mrrAt10": "MRR@10",
        "mixedBothHitRate": "Mixed both-hit rate",
        "noResultAccuracy": "No-result accuracy",
        "p50LatencyMs": "P50 latency (ms)",
        "p95LatencyMs": "P95 latency (ms)",
        "failedCases": "Failed cases",
        "bgeCalls": "BGE calls",
        "bgeSuccesses": "BGE successes",
        "bgeDegradations": "BGE degradations",
        "bgeNoCandidateSkips": "BGE no-candidate skips",
        "bgeSingletonSkips": "BGE singleton skips",
    }
    for key, label in labels.items():
        change = comparison["overall"][key]
        relative = "n/a" if change["relative"] is None else f"{change['relative'] * 100:.2f}%"
        lines.append(
            f"| {label} | {change['baseline']:.3f} | {change['rerank']:.3f} | "
            f"{change['absolute']:.3f} | {relative} |"
        )
    lines.extend(["", "## Per profile P95", "",
                  f"| Profile | {comparison.get('baselineMode', BASELINE_MODE)} (ms) | "
                  f"{comparison.get('candidateMode', RERANK_MODE)} (ms) | Change |",
                  "|---|---:|---:|---:|"])
    for profile, change in comparison["profileP95"].items():
        relative = "n/a" if change["relative"] is None else f"{change['relative'] * 100:.2f}%"
        lines.append(f"| {profile} | {change['baseline']} | {change['rerank']} | {relative} |")
    benchmark = comparison["parallelRecallBenchmark"]
    benchmark_reduction = benchmark.get("reduction")
    reduction_text = "n/a" if benchmark_reduction is None else f"{benchmark_reduction * 100:.2f}%"
    lines.extend(
        [
            "",
            "## Controlled parallel recall benchmark",
            "",
            "| Sequential P95 (ms) | Parallel P95 (ms) | Reduction | Required |",
            "|---:|---:|---:|---:|",
            f"| {benchmark.get('sequentialP95Ms')} | {benchmark.get('parallelP95Ms')} | "
            f"{reduction_text} | {benchmark.get('requiredReduction', 0) * 100:.2f}% |",
        ]
    )
    lines.extend(["", "## Acceptance checks", ""])
    for check in acceptance["checks"]:
        lines.append(f"- [{'x' if check['passed'] else ' '}] {check['name']}: {check['detail']}")
    return "\n".join(lines) + "\n"


def compare(args: argparse.Namespace) -> tuple[dict[str, Any], dict[str, Any]]:
    dataset = validate_dataset(args.dataset, args.dataset_sha256)
    baseline_path = args.output_root / args.baseline_mode / "report.json"
    rerank_path = args.output_root / args.candidate_mode / "report.json"
    baseline = read_json(baseline_path)
    rerank = read_json(rerank_path)
    parallel_benchmark = read_json(args.parallel_benchmark)
    baseline_errors = report_contract(baseline, args.baseline_mode, args.repetitions)
    rerank_errors = report_contract(rerank, args.candidate_mode, args.repetitions)
    benchmark_errors = parallel_benchmark_contract(parallel_benchmark)
    if baseline.get("dataset") != args.dataset_resource or rerank.get("dataset") != args.dataset_resource:
        baseline_errors.append("both reports must use the frozen dataset resource")
    if baseline.get("warmupRuns") != args.warmup_runs or rerank.get("warmupRuns") != args.warmup_runs:
        baseline_errors.append("both reports must use the frozen warm-up count")

    baseline_summary = baseline.get("summary") or {}
    rerank_summary = rerank.get("summary") or {}
    benchmark_summary = parallel_benchmark_summary(parallel_benchmark)
    benchmark_reduction = benchmark_summary["reduction"]
    benchmark_detail = (
        f"{benchmark_summary['sequentialP95Ms']} ms -> {benchmark_summary['parallelP95Ms']} ms "
        f"({benchmark_reduction * 100:.2f}% reduction)"
        if not benchmark_errors and benchmark_reduction is not None
        else "; ".join(benchmark_errors)
    )
    checks = [
        {
            "name": "baseline report contract",
            "passed": not baseline_errors,
            "detail": "valid" if not baseline_errors else "; ".join(baseline_errors),
        },
        {
            "name": "rerank report contract",
            "passed": not rerank_errors,
            "detail": "valid" if not rerank_errors else "; ".join(rerank_errors),
        },
        {
            "name": "baseline BGE behavior matches the selected mode",
            "passed": (
                baseline_summary.get("bgeCalls") == 0
                if args.baseline_mode == BASELINE_MODE
                else baseline_summary.get("bgeCalls", 0) > 0
                and baseline_summary.get("bgeSuccesses") == baseline_summary.get("bgeCalls")
                and baseline_summary.get("bgeDegradations") == 0
            ),
            "detail": (
                f"attempts/success/degradation={baseline_summary.get('bgeCalls')}/"
                f"{baseline_summary.get('bgeSuccesses')}/{baseline_summary.get('bgeDegradations')}"
            ),
        },
        {
            "name": "rerank BGE decisions are healthy and fully accounted",
            "passed": healthy_bge_decisions(
                rerank_summary, args.bge_live_contract_verified
            ),
            "detail": (
                f"attempts/success/degradation/no-candidate/singleton-skips="
                f"{rerank_summary.get('bgeCalls')}/{rerank_summary.get('bgeSuccesses')}/"
                f"{rerank_summary.get('bgeDegradations')}/"
                f"{rerank_summary.get('bgeNoCandidateSkips')}/"
                f"{rerank_summary.get('bgeSingletonSkips')}; "
                f"live-contract-verified={args.bge_live_contract_verified}"
            ),
        },
        non_regression_check(
            "document Recall@10 does not regress",
            baseline_summary,
            rerank_summary,
            "documentRecallAt10",
        ),
        non_regression_check(
            "code Recall@10 does not regress",
            baseline_summary,
            rerank_summary,
            "codeRecallAt10",
        ),
        non_regression_check(
            "MRR@10 does not regress",
            baseline_summary,
            rerank_summary,
            "mrrAt10",
        ),
        {
            "name": "controlled parallel recall P95 decreases by at least 30%",
            "passed": not benchmark_errors,
            "detail": benchmark_detail,
        },
    ]
    if args.candidate_mode == QUALITY_MODE:
        checks.extend([
            {
                "name": "0.8.1 Document Recall@10 reaches the quality threshold",
                "passed": rerank_summary.get("documentRecallAt10", 0) >= QUALITY_DOCUMENT_RECALL_MIN,
                "detail": (
                    f"{rerank_summary.get('documentRecallAt10', 0):.6f} >= "
                    f"{QUALITY_DOCUMENT_RECALL_MIN:.6f}"
                ),
            },
            {
                "name": "0.8.1 Code Recall@10 reaches the quality threshold",
                "passed": rerank_summary.get("codeRecallAt10", 0) >= QUALITY_CODE_RECALL_MIN,
                "detail": (
                    f"{rerank_summary.get('codeRecallAt10', 0):.6f} >= "
                    f"{QUALITY_CODE_RECALL_MIN:.6f}"
                ),
            },
            {
                "name": "0.8.1 MRR@10 reaches the quality threshold",
                "passed": rerank_summary.get("mrrAt10", 0) >= QUALITY_MRR_MIN,
                "detail": f"{rerank_summary.get('mrrAt10', 0):.6f} >= {QUALITY_MRR_MIN:.6f}",
            },
            {
                "name": "0.8.1 no-result accuracy remains perfect",
                "passed": rerank_summary.get("noResultAccuracy") == 1.0,
                "detail": f"accuracy={rerank_summary.get('noResultAccuracy')}",
            },
            {
                "name": "0.8.1 real P95 stays within the 0.8 budget",
                "passed": rerank_summary.get("p95LatencyMs", QUALITY_P95_MAX_MS + 1)
                <= QUALITY_P95_MAX_MS,
                "detail": (
                    f"{rerank_summary.get('p95LatencyMs')} ms <= {QUALITY_P95_MAX_MS} ms"
                ),
            },
        ])
        if args.setup_manifest:
            setup = read_json(args.setup_manifest)
            checks.append({
                "name": "frozen corpus was rebuilt and fingerprinted",
                "passed": setup.get("schemaVersion") == 1
                and setup.get("codeCommit") == args.shiguang_commit
                and setup.get("requirementChunksWritten") == setup.get("requirementChunksPersisted")
                and setup.get("codeChunksWritten") == setup.get("codeChunksPersisted"),
                "detail": (
                    f"requirements={setup.get('requirementChunksPersisted')}, "
                    f"code={setup.get('codeChunksPersisted')}, commit={setup.get('codeCommit')}"
                ),
            })

    profile_p95 = {
        profile: metric_change(
            ((baseline.get("profiles") or {}).get(profile) or {}).get("p95LatencyMs", 0),
            ((rerank.get("profiles") or {}).get(profile) or {}).get("p95LatencyMs", 0),
        )
        for profile in EXPECTED_PROFILES
    }
    passed = all(check["passed"] for check in checks)
    comparison = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "classification": "formal" if passed else "formal-not-accepted",
        "datasetSha256": dataset["sha256"],
        "baselineMode": args.baseline_mode,
        "candidateMode": args.candidate_mode,
        "repetitions": args.repetitions,
        "overall": summarize_changes(baseline_summary, rerank_summary),
        "profileP95": profile_p95,
        "parallelRecallBenchmark": benchmark_summary,
        "acceptance": {"passed": passed, "checks": checks},
        "reports": {
            args.baseline_mode: str(baseline_path),
            args.candidate_mode: str(rerank_path),
            "parallelRecallBenchmark": str(args.parallel_benchmark),
        },
    }
    return build_manifest(args, dataset), comparison


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument("--shiguang-repository", type=Path, required=True)
    parser.add_argument("--shiguang-commit", required=True)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--dataset-resource", required=True)
    parser.add_argument("--dataset-sha256", required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--reranker-python", type=Path, required=True)
    parser.add_argument("--parallel-benchmark", type=Path, required=True)
    parser.add_argument("--warmup-runs", type=int, required=True)
    parser.add_argument("--repetitions", type=int, required=True)
    parser.add_argument("--embedding-cache-ttl-seconds", type=int, required=True)
    parser.add_argument("--embedding-cache-max-entries", type=int, required=True)
    parser.add_argument("--bge-device", required=True)
    parser.add_argument("--bge-max-length", type=int, required=True)
    parser.add_argument("--bge-batch-size", type=int, required=True)
    parser.add_argument("--bge-read-timeout-ms", type=int, required=True)
    parser.add_argument("--bge-live-contract-verified", action="store_true")
    parser.add_argument("--baseline-mode", default=BASELINE_MODE)
    parser.add_argument("--candidate-mode", default=RERANK_MODE)
    parser.add_argument("--setup-manifest", type=Path)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        manifest, comparison = compare(args)
        args.output_root.mkdir(parents=True, exist_ok=True)
        (args.output_root / "manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        (args.output_root / "comparison.json").write_text(
            json.dumps(comparison, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        (args.output_root / "comparison.md").write_text(
            render_markdown(comparison), encoding="utf-8"
        )
    except (OSError, ValueError, subprocess.CalledProcessError) as exc:
        print(f"comparison: FAIL: {exc}", file=sys.stderr)
        return 2
    print(
        "comparison: " + ("PASS" if comparison["acceptance"]["passed"] else "NOT ACCEPTED")
    )
    return 0 if comparison["acceptance"]["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
