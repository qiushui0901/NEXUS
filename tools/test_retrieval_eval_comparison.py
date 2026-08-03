from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

MODULE_PATH = Path(__file__).with_name("retrieval-eval-comparison.py")
SPEC = importlib.util.spec_from_file_location("retrieval_eval_comparison", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class RetrievalEvalComparisonTest(unittest.TestCase):
    def test_executable_path_preserves_virtualenv_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            base_python = root / "base-python"
            base_python.write_text("", encoding="utf-8")
            venv_python = root / ".venv" / "bin" / "python"
            venv_python.parent.mkdir(parents=True)
            venv_python.symlink_to(base_python)

            executable = MODULE.executable_path(venv_python)

        self.assertEqual(venv_python.absolute(), executable)
        self.assertNotEqual(base_python.resolve(), executable)

    def test_evaluation_source_paths_cover_executed_reranker_runtime(self):
        paths = {
            str(path.relative_to(Path("/repo")))
            for path in MODULE.evaluation_source_paths(Path("/repo"))
        }

        self.assertTrue(
            {
                "src/main/java/com/example/requirementrag/rerank/HttpBgeReranker.java",
                "src/main/resources/application-shiguang-eval.yml",
                "scripts/run-shiguang-eval.sh",
                "tools/bge-reranker-service.py",
                "tools/check-bge-reranker.py",
                "tools/requirements-bge-reranker.txt",
                "tools/start-bge-reranker.sh",
            }.issubset(paths)
        )

    def test_required_package_version_returns_runtime_version(self):
        with patch.object(MODULE, "command", return_value="2.11.0") as mocked_command:
            version = MODULE.required_package_version(Path("/venv/bin/python"), "torch")

        self.assertEqual("2.11.0", version)
        mocked_command.assert_called_once()

    def test_required_package_version_rejects_missing_dependency(self):
        with patch.object(MODULE, "command", side_effect=OSError("missing interpreter")):
            with self.assertRaisesRegex(
                ValueError, r"Required reranker package 'transformers' is unavailable"
            ):
                MODULE.required_package_version(Path("/venv/bin/python"), "transformers")

    def test_required_package_version_rejects_empty_version(self):
        with patch.object(MODULE, "command", return_value=""):
            with self.assertRaisesRegex(
                ValueError, r"Required reranker package 'torch' returned an empty version"
            ):
                MODULE.required_package_version(Path("/venv/bin/python"), "torch")

    def test_metric_change_handles_zero_baseline(self):
        self.assertEqual(
            {"baseline": 0, "rerank": 1, "absolute": 1, "relative": None},
            MODULE.metric_change(0, 1),
        )

    def test_non_regression_check_accepts_equal_or_better_and_rejects_worse(self):
        equal = MODULE.non_regression_check(
            "MRR@10 does not regress", {"mrrAt10": 0.75}, {"mrrAt10": 0.75}, "mrrAt10"
        )
        better = MODULE.non_regression_check(
            "MRR@10 does not regress", {"mrrAt10": 0.75}, {"mrrAt10": 0.80}, "mrrAt10"
        )
        worse = MODULE.non_regression_check(
            "MRR@10 does not regress", {"mrrAt10": 0.75}, {"mrrAt10": 0.74}, "mrrAt10"
        )
        self.assertTrue(equal["passed"])
        self.assertTrue(better["passed"])
        self.assertFalse(worse["passed"])
        self.assertEqual("0.750000 -> 0.740000", worse["detail"])


    def test_healthy_bge_decisions_accepts_verified_singleton_fast_path(self):
        summary = {
            "totalCases": 162,
            "bgeCalls": 0,
            "bgeSuccesses": 0,
            "bgeDegradations": 0,
            "bgeNoCandidateSkips": 18,
            "bgeSingletonSkips": 144,
        }
        self.assertTrue(MODULE.healthy_bge_decisions(summary, True))
        self.assertFalse(MODULE.healthy_bge_decisions(summary, False))
        summary["bgeSingletonSkips"] = 143
        self.assertFalse(MODULE.healthy_bge_decisions(summary, True))

    def test_healthy_bge_decisions_accepts_real_calls_and_rejects_degradation(self):
        summary = {
            "totalCases": 162,
            "bgeCalls": 144,
            "bgeSuccesses": 144,
            "bgeDegradations": 0,
            "bgeNoCandidateSkips": 18,
            "bgeSingletonSkips": 0,
        }
        self.assertTrue(MODULE.healthy_bge_decisions(summary, False))
        summary["bgeDegradations"] = 1
        self.assertFalse(MODULE.healthy_bge_decisions(summary, True))

    def test_report_contract_requires_formal_repeated_report(self):
        report = {
            "mode": "0.7-baseline",
            "classification": "formal",
            "datasetCaseCount": 54,
            "cutoff": 10,
            "repetitions": 3,
            "summary": {"totalCases": 162, "infrastructureFailureCases": 0},
            "profiles": {
                "DEVELOPMENT_PLAN": {"totalCases": 90},
                "REQUIREMENT_REVIEW": {"totalCases": 36},
                "WIKI_BUILD": {"totalCases": 36},
            },
        }
        self.assertEqual([], MODULE.report_contract(report, "0.7-baseline", 3))
        report["classification"] = "calibration"
        self.assertIn("classification must be formal", MODULE.report_contract(report, "0.7-baseline", 3))

    def test_quality_report_contract_requires_stage_diagnostics_and_failure_attribution(self):
        diagnostic_case = {
            "success": False,
            "expectsDocuments": True,
            "expectsCode": True,
            "documentTraceAvailable": True,
            "codeTraceAvailable": True,
            "documentRawRank": None,
            "documentRerankInputRank": None,
            "documentRerankedRank": None,
            "documentRank": None,
            "documentRankMovement": "MISSING",
            "documentOrderChanged": False,
            "codeRawRank": 2,
            "codeRankedRank": None,
            "codeRank": None,
            "codeRankMovement": "LOST",
            "codeOrderChanged": True,
            "documentRawCandidateCount": 40,
            "documentRerankCandidateCount": 20,
            "documentRerankedCandidateCount": 10,
            "codeRawCandidateCount": 50,
            "codeRankedCandidateCount": 10,
            "failureAttributions": [
                "DOCUMENT_CANDIDATE_RECALL_MISS", "CODE_RERANK_LOSS"
            ],
        }
        report = {
            "mode": "0.8.1-quality",
            "classification": "formal",
            "datasetCaseCount": 54,
            "cutoff": 10,
            "repetitions": 1,
            "summary": {
                "totalCases": 54, "infrastructureFailureCases": 0,
                "bgeCalls": 0, "bgeSuccesses": 0, "bgeDegradations": 0,
                "bgeNoCandidateSkips": 6, "bgeSingletonSkips": 48,
            },
            "profiles": {
                "DEVELOPMENT_PLAN": {"totalCases": 30},
                "REQUIREMENT_REVIEW": {"totalCases": 12},
                "WIKI_BUILD": {"totalCases": 12},
            },
            "failureAttributions": {"DOCUMENT_CANDIDATE_RECALL_MISS": 54},
            "cases": [dict(diagnostic_case) for _ in range(54)],
        }

        self.assertEqual([], MODULE.report_contract(report, "0.8.1-quality", 1))
        report["cases"][0]["failureAttributions"] = []
        self.assertIn(
            "cases[0] failed without stage attribution",
            MODULE.report_contract(report, "0.8.1-quality", 1),
        )

    def test_parallel_benchmark_contract_accepts_recomputed_thirty_percent_reduction(self):
        report = {
            "schemaVersion": 1,
            "classification": "controlled-fake-dependency",
            "profile": "DEVELOPMENT_PLAN",
            "branchCount": 3,
            "branchDelayMs": 100,
            "warmupRuns": 2,
            "repetitions": 10,
            "sequentialP95Ms": 314,
            "parallelP95Ms": 107,
            "reduction": 1.0 - 107 / 314,
            "requiredReduction": 0.30,
            "passed": True,
        }

        self.assertEqual([], MODULE.parallel_benchmark_contract(report))
        summary = MODULE.parallel_benchmark_summary(report)
        self.assertAlmostEqual(1.0 - 107 / 314, summary["reduction"])

    def test_parallel_benchmark_contract_recomputes_reduction_instead_of_trusting_passed(self):
        report = {
            "schemaVersion": 1,
            "classification": "controlled-fake-dependency",
            "profile": "DEVELOPMENT_PLAN",
            "branchCount": 3,
            "branchDelayMs": 100,
            "warmupRuns": 2,
            "repetitions": 10,
            "sequentialP95Ms": 300,
            "parallelP95Ms": 250,
            "reduction": 0.50,
            "requiredReduction": 0.30,
            "passed": True,
        }

        errors = MODULE.parallel_benchmark_contract(report)
        self.assertIn("recomputed P95 reduction must be at least 30%", errors)
        self.assertIn("reduction must match the value recomputed from P95", errors)

    def test_dataset_validation_checks_sha_profiles_tags_and_uniqueness(self):
        rows = []
        distribution = {"DEVELOPMENT_PLAN": 30, "REQUIREMENT_REVIEW": 12, "WIKI_BUILD": 12}
        tags = sorted(MODULE.REQUIRED_TAGS)
        index = 0
        for profile, count in distribution.items():
            for _ in range(count):
                rows.append({
                    "id": f"case-{index}",
                    "query": f"query {index}",
                    "profile": profile,
                    "tags": ["shiguang-real", tags[index % len(tags)]],
                })
                index += 1
        payload = "".join(json.dumps(row) + "\n" for row in rows).encode()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "dataset.jsonl"
            path.write_bytes(payload)
            result = MODULE.validate_dataset(path, MODULE.sha256_bytes(payload))
        self.assertEqual(54, result["caseCount"])
        self.assertEqual(distribution, result["profiles"])

    def test_markdown_uses_acceptance_state_and_relative_change(self):
        comparison = {
            "classification": "formal",
            "datasetSha256": "abc",
            "repetitions": 3,
            "overall": {
                key: MODULE.metric_change(1, 0.5)
                for key in (
                    "documentRecallAt10", "codeRecallAt10", "mrrAt10", "mixedBothHitRate",
                    "noResultAccuracy", "p50LatencyMs", "p95LatencyMs", "failedCases",
                    "bgeCalls", "bgeSuccesses", "bgeDegradations", "bgeNoCandidateSkips",
                    "bgeSingletonSkips",
                )
            },
            "profileP95": {
                profile: MODULE.metric_change(100, 60) for profile in MODULE.EXPECTED_PROFILES
            },
            "parallelRecallBenchmark": {
                "classification": "controlled-fake-dependency",
                "profile": "DEVELOPMENT_PLAN",
                "branchCount": 3,
                "branchDelayMs": 100,
                "warmupRuns": 2,
                "repetitions": 10,
                "sequentialP95Ms": 300,
                "parallelP95Ms": 100,
                "reduction": 2 / 3,
                "requiredReduction": 0.30,
            },
            "acceptance": {
                "passed": True,
                "checks": [{"name": "contract", "passed": True, "detail": "valid"}],
            },
        }
        markdown = MODULE.render_markdown(comparison)
        self.assertIn("Acceptance: `PASS`", markdown)
        self.assertIn("-40.00%", markdown)
        self.assertIn("## Controlled parallel recall benchmark", markdown)
        self.assertIn("66.67%", markdown)
        self.assertIn("[x] contract", markdown)


if __name__ == "__main__":
    unittest.main()
