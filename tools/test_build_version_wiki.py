import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("build-version-wiki.py")
SPEC = importlib.util.spec_from_file_location("build_version_wiki", MODULE_PATH)
wiki = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(wiki)


class BuildVersionWikiTest(unittest.TestCase):
    def test_short_requirement_is_kept_but_coordination_question_is_skipped(self):
        requirement = {
            "filename": "返回规则调整.html",
            "text": "返回规则调整\n操作完成后，应返回功能首页而不是入口页",
        }
        question = {
            "filename": "版本问题.xlsx#待确认",
            "text": "模块：依赖资源\n问题：版本界面什么时候提供",
        }

        self.assertTrue(wiki.actionable(requirement))
        self.assertFalse(wiki.actionable(question))

    def test_numbered_sections_become_business_process_boundary_and_acceptance_knowledge(self):
        entry = {
            "filename": "记录功能.html",
            "text": """
记录功能
一、基础说明
1、在详情页增加记录入口
2、点击入口后打开记录页
3、退出活动后清空记录
二、记录描述
编号
类别
标题
内容
1
获取记录
领取奖励
获得100积分
""",
        }

        page = wiki.parse_requirement(entry)

        self.assertIn("在详情页增加记录入口", page["productRules"])
        self.assertIn("点击入口后打开记录页", page["processSteps"])
        self.assertIn("退出活动后清空记录", page["boundaryConditions"])
        self.assertIn("获取记录 / 领取奖励 / 获得100积分", page["dataImpacts"])
        self.assertTrue(page["acceptanceCriteria"])
        self.assertEqual([], page["testPoints"])

    def test_feature_page_is_traceable_and_never_claims_test_execution(self):
        source = {"projectId": "demo", "version": "2.0", "codeCommit": "", "baseCodeCommit": ""}
        snapshot = {
            "documentId": "requirements",
            "requirementVersion": "2.0",
            "baseRequirementVersion": "1.9",
        }
        entry = {
            "entryId": "entry-1",
            "filename": "返回规则调整.html",
            "parentOrder": 3,
            "contentHash": "abc123",
            "text": "返回规则调整\n操作完成后，应返回功能首页而不是入口页",
        }

        page = wiki.page_for_entry(source, snapshot, entry, Path("."), "", "", {})

        self.assertTrue(page["featureId"].startswith("requirement-"))
        self.assertEqual("REQUIREMENT_VERIFIED", page["status"])
        self.assertEqual("NOT_AVAILABLE", page["testKnowledge"]["executionStatus"])
        self.assertEqual("没有真实执行快照", page["testKnowledge"]["summary"])
        self.assertEqual(1, page["quality"]["requirementEvidenceCount"])
        self.assertEqual("abc123", page["requirementSources"][0]["contentHash"])
        self.assertEqual("REQUIREMENT", page["evidence"][0]["type"])

    def test_code_link_requires_exact_title_or_multiple_terms(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=repo, check=True)
            (repo / "src").mkdir()
            (repo / "src/RelevantService.java").write_text(
                "// 返回规则调整\nclass RelevantService { void applyRule() {} }\n", encoding="utf-8"
            )
            (repo / "src/NoiseService.java").write_text(
                "class NoiseService { String role_level; }\n", encoding="utf-8"
            )
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-qm", "fixture"], cwd=repo, check=True)
            commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()

            entries, _, _ = wiki.code_for_requirement(
                repo, commit, "", "返回规则调整", "role_level applyRule", {}
            )

            self.assertEqual(["src/RelevantService.java"], [entry["filePath"] for entry in entries])

    def test_enriched_version_contains_overview_and_requirement_pages_only(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = root / "repo"
            repo.mkdir()
            subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=repo, check=True)
            (repo / "pom.xml").write_text("<project/>", encoding="utf-8")
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-qm", "fixture"], cwd=repo, check=True)
            commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
            requirement_root = root / "requirements/demo"
            requirement_root.mkdir(parents=True)
            (requirement_root / "2.0.json").write_text(json.dumps({
                "projectId": "demo",
                "documentId": "requirements",
                "requirementVersion": "2.0",
                "baseRequirementVersion": "1.9",
                "entries": [{
                    "entryId": "entry-1",
                    "filename": "返回规则调整.html",
                    "parentOrder": 1,
                    "contentHash": "hash-1",
                    "text": "返回规则调整\n操作完成后，应返回功能首页而不是入口页",
                }],
            }, ensure_ascii=False), encoding="utf-8")
            source = {
                "schemaVersion": 1,
                "projectId": "demo",
                "projectName": "Demo",
                "version": "2.0",
                "requirementVersion": "2.0",
                "baseCodeCommit": "",
                "codeCommit": commit,
                "generatedAt": "2026-07-27T00:00:00+08:00",
                "pages": [{"featureId": "version-2.0-module-api"}],
            }

            enriched = wiki.enrich_source(source, repo, root / "requirements")

            self.assertEqual(2, enriched["schemaVersion"])
            self.assertEqual(2, len(enriched["pages"]))
            self.assertTrue(any(page["featureId"] == "version-2.0-overview" for page in enriched["pages"]))
            self.assertFalse(any("module" in page["featureId"] for page in enriched["pages"]))


if __name__ == "__main__":
    unittest.main()
