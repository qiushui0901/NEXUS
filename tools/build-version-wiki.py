#!/usr/bin/env python3
"""Build substantive, version-scoped Wiki artifacts from Git snapshots.

This is the second-stage builder: human source JSON remains authoritative for
product statements, while this tool adds reproducible code-structure evidence
from the configured repository. It never reads or writes Qdrant/vector data.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import tempfile
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

MAX_FILES = 120
MAX_MODULES = 12
MAX_SYMBOLS_PER_FILE = 8
MAX_EVIDENCE = 24
FORBIDDEN = re.compile(r"(?i)(api[_-]?key|password|secret|token|authorization|credential|embedding|vector|qdrant|snapshot|wal)")
COMMIT = re.compile(r"^[0-9a-fA-F]{7,64}$")
JAVA_TYPE = re.compile(r"\b(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)")
JAVA_METHOD = re.compile(
    r"\b(?:public|protected|private|static|final|synchronized|default|abstract|native|\s)+"
    r"[\w<>,.?\[\] ]+\s+([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*(?:throws\s+[\w., ]+)?\s*[{;]"
)
CONTROL = {"if", "for", "while", "switch", "catch", "return", "new", "assert", "do"}
AUTO_SUMMARY_PREFIXES = (
    "Git 代码边界：", "Git 代码快照：", "本版本受控识别 ", "提交说明：", "提交时间：", "自动代码证据：",
)
AUTO_CODE_PREFIXES = ("Git 代码边界：", "Git 代码快照：", "本版本受控识别 ", "提交说明：")


def run_git(repo: Path, *args: str, allow_failure: bool = False) -> str:
    process = subprocess.run(
        ["git", *args], cwd=repo, text=True, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, check=False, timeout=30,
    )
    if process.returncode and not allow_failure:
        raise RuntimeError(f"git {' '.join(args)} failed: {process.stderr.strip()[:300]}")
    return process.stdout


def safe(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "-", value).strip("-") or "version"


def clean_text(value: str) -> str:
    value = re.sub(r"\s+", " ", value).strip()
    return "" if FORBIDDEN.search(value) else value


def is_relevant(path: str) -> bool:
    p = path.replace("\\", "/")
    lower = p.lower()
    if any(part in lower.split("/") for part in (".git", "target", "node_modules", "build", ".idea")):
        return False
    if lower.endswith((".java", ".kt", ".groovy", ".xml", ".yml", ".yaml", ".properties", ".sql", ".json")):
        return not any(x in lower for x in ("/generated/", "/resources/static/", "/resources/public/"))
    return lower.endswith(("pom.xml", "gradlew", "build.gradle", "settings.gradle"))


def is_test(path: str) -> bool:
    lower = path.lower()
    return "/test/" in lower or lower.endswith(("test.java", "tests.java", "test.kt", "tests.kt"))


def category(path: str) -> str:
    lower = path.lower()
    if lower.endswith(".java") or lower.endswith(".kt") or lower.endswith(".groovy"):
        return "测试代码" if is_test(path) else "业务代码"
    if lower.endswith((".yml", ".yaml", ".properties", ".xml", ".json", ".toml")):
        return "配置与构建"
    return "其他"


def module_name(path: str) -> str:
    parts = path.replace("\\", "/").split("/")
    if "src" in parts:
        index = parts.index("src")
        return "/".join(parts[:index]) or "root"
    return parts[0] if parts else "root"


def parse_status(line: str) -> tuple[str, str, str | None] | None:
    fields = line.rstrip("\n").split("\t")
    if len(fields) < 2:
        return None
    status = fields[0]
    if status.startswith("R") and len(fields) >= 3:
        return "RENAMED", fields[2], fields[1]
    path = fields[1]
    kind = {"A": "ADDED", "M": "MODIFIED", "T": "MODIFIED", "D": "DELETED"}.get(status[:1])
    return (kind, path, path) if kind else None


def files_for_version(repo: Path, base: str, commit: str) -> list[tuple[str, str, str | None]]:
    if base:
        output = run_git(repo, "diff", "--name-status", "-M", base, commit)
        changes = [x for line in output.splitlines() if (x := parse_status(line))]
    else:
        output = run_git(repo, "ls-tree", "-r", "--name-only", commit)
        changes = [("SNAPSHOT", path, None) for path in output.splitlines() if path]
    priority = {"ADDED": 0, "MODIFIED": 1, "RENAMED": 2, "SNAPSHOT": 3, "DELETED": 4}
    selected = [x for x in changes if is_relevant(x[1])]
    selected.sort(key=lambda x: (priority.get(x[0], 9), category(x[1]), x[1]))
    return selected[:MAX_FILES]


def symbols_for(repo: Path, commit: str, path: str) -> list[str]:
    if not path.lower().endswith((".java", ".kt", ".groovy")):
        return []
    text = run_git(repo, "show", f"{commit}:{path}", allow_failure=True)
    if not text:
        return []
    values: list[str] = []
    for match in JAVA_TYPE.finditer(text):
        name = match.group(1)
        if name not in values:
            values.append(name)
    for match in JAVA_METHOD.finditer(text):
        name = match.group(1)
        if name not in CONTROL and name not in values:
            values.append(name)
    return values[:MAX_SYMBOLS_PER_FILE]


def commit_meta(repo: Path, commit: str) -> tuple[str, str]:
    line = run_git(repo, "show", "-s", "--format=%ad%x09%s", "--date=iso-strict", commit).strip()
    if "\t" in line:
        authored, subject = line.split("\t", 1)
    else:
        authored, subject = "", line
    return authored, clean_text(subject) or "未提供提交说明"


def evidence(project: str, version: str, commit: str, path: str, symbols: Iterable[str]) -> dict:
    symbol_text = ", ".join(symbols)
    excerpt = f"Git {commit[:12]} 的版本证据：{path}"
    if symbol_text:
        excerpt += f"；识别到结构符号：{symbol_text}。"
    else:
        excerpt += "；仅记录文件路径和类型，未复制源码正文。"
    return {
        "type": "CODE",
        "title": f"代码文件：{path}",
        "source": project,
        "version": version,
        "location": path,
        "excerpt": clean_text(excerpt),
        "commit": commit,
        "filePath": path,
        "symbol": symbol_text,
        "verificationStatus": "VERIFIED",
    }


def git_evidence(project: str, version: str, commit: str, base: str, subject: str, files: list) -> dict:
    boundary = f"{base[:12]} 到 {commit[:12]}" if base else f"commit {commit[:12]} 的快照"
    return {
        "type": "GIT",
        "title": f"{version} 代码版本边界",
        "source": project,
        "version": version,
        "location": boundary,
        "excerpt": clean_text(f"{boundary}；提交说明：{subject}；纳入 {len(files)} 个受控代码/配置文件。"),
        "commit": commit,
        "filePath": "",
        "symbol": "",
        "verificationStatus": "VERIFIED",
    }


def merge_unique(items: list[str], additions: Iterable[str]) -> list[str]:
    result = list(items or [])
    for item in additions:
        if item and item not in result:
            result.append(item)
    return result


def without_prefixes(items: Iterable[str], prefixes: tuple[str, ...]) -> list[str]:
    return [item for item in (items or []) if not str(item).strip().startswith(prefixes)]


def human_summary(value: str) -> str:
    sentences = re.split(r"(?<=。)\s*", value or "")
    kept = [sentence.strip() for sentence in sentences
            if sentence.strip() and not sentence.strip().startswith(AUTO_SUMMARY_PREFIXES)]
    return " ".join(kept)


def make_page(feature_id: str, title: str, version: str, category_name: str, summary: str,
              product: list[str], code: list[str], tests: list[str], risks: list[str],
              evidence_items: list[dict], aliases: list[str] | None = None) -> dict:
    return {
        "featureId": feature_id,
        "title": title,
        "category": category_name,
        "introducedVersion": version,
        "status": "CODE_VERIFIED",
        "aliases": aliases or [],
        "summary": summary,
        "productRules": product,
        "codeSymbols": code,
        "testPoints": tests,
        "risks": risks,
        "relations": [],
        "evidence": evidence_items,
    }


def enrich_source(source: dict, repo: Path) -> dict:
    version = source["version"]
    commit = source.get("codeCommit") or ""
    base = source.get("baseCodeCommit") or ""
    if not COMMIT.fullmatch(commit):
        return source
    run_git(repo, "cat-file", "-e", f"{commit}^{{commit}}")
    if base:
        run_git(repo, "cat-file", "-e", f"{base}^{{commit}}")
    authored, subject = commit_meta(repo, commit)
    changes = files_for_version(repo, base, commit)
    enriched = dict(source)
    pages = [dict(page) for page in source.get("pages", [])]
    generated_prefix = f"version-{safe(version)}-module-"
    pages = [page for page in pages
             if page.get("featureId") != f"version-{version}-code-structure"
             and not str(page.get("featureId", "")).startswith(generated_prefix)]
    by_path: list[tuple[str, str, str | None, list[str]]] = []
    for status, path, old_path in changes:
        symbols = symbols_for(repo, commit, path)
        by_path.append((status, path, old_path, symbols))
    file_evidence = [evidence(source["projectId"], version, commit, path, symbols)
                     for _, path, _, symbols in by_path[:MAX_EVIDENCE]]
    boundary = git_evidence(source["projectId"], version, commit, base, subject, by_path)
    changed = len(by_path)
    java = sum(path.lower().endswith((".java", ".kt", ".groovy")) for _, path, _, _ in by_path)
    tests_count = sum(is_test(path) for _, path, _, _ in by_path)
    config_count = sum(category(path) == "配置与构建" for _, path, _, _ in by_path)
    modules: dict[str, list[tuple[str, str, str | None, list[str]]]] = defaultdict(list)
    for item in by_path:
        modules[module_name(item[1])].append(item)
    overview_id = f"version-{version}-overview"
    overview = next((p for p in pages if p.get("featureId") == overview_id), None)
    overview_additions = [
        f"Git 代码边界：{base[:12]} → {commit[:12]}。" if base else f"Git 代码快照：{commit[:12]}。",
        f"本版本受控识别 {changed} 个代码/配置文件，其中 Java/Kotlin {java} 个、测试文件 {tests_count} 个、配置文件 {config_count} 个。",
        f"提交说明：{subject}。" + (f" 提交时间：{authored}。" if authored else ""),
    ]
    overview_code = [x for x in overview_additions]
    overview_tests = [
        "没有真实执行快照；本页只记录 Git 版本边界和静态测试文件证据。",
        f"静态识别测试文件 {tests_count} 个；发布前需要关联真实测试报告。",
    ]
    if overview:
        summary = human_summary(overview.get("summary") or "")
        auto_summary = "自动代码证据：" + " ".join(overview_additions)
        overview["summary"] = clean_text(" ".join(item for item in (summary, auto_summary) if item))
        overview["codeSymbols"] = merge_unique(
            without_prefixes(overview.get("codeSymbols", []), AUTO_CODE_PREFIXES), overview_code)
        overview["testPoints"] = merge_unique(
            without_prefixes(overview.get("testPoints", []),
                             ("没有真实执行快照", "静态识别测试文件 ")), overview_tests)
        overview["risks"] = merge_unique(overview.get("risks", []), [
            "自动代码证据只证明文件和结构存在，不等同于业务规则或运行时行为。",
            "测试执行结果尚未关联到本版本 Wiki。",
        ])
        overview["evidence"] = [item for item in (overview.get("evidence") or [])
                                if not (item.get("type") == "GIT"
                                            and str(item.get("title", "")).endswith("代码版本边界"))] + [boundary]
    else:
        pages.append(make_page(
            overview_id, f"{version} 版本概览", version, "版本",
            " ".join(overview_additions),
            ["未读取需求原文，不从代码名称推断产品规则。"], overview_code,
            overview_tests,
            ["自动代码证据不替代需求和运行测试证据。"], [boundary], [f"V{version}"]))

    code_id = f"version-{version}-code-structure"
    code_lines = [f"{status}: {path}" + (f" ← {old}" if old and old != path else "")
                  for status, path, old, _ in by_path]
    symbols = [f"{path} :: {', '.join(item_symbols)}" for _, path, _, item_symbols in by_path if item_symbols]
    pages.append(make_page(
        code_id, f"{version} 代码结构与变更", version, "代码变化",
        f"这是由 Git 自动生成的版本代码证据页。{('比较基线 ' + base[:12] + ' 与目标 ' + commit[:12]) if base else ('目标快照为 ' + commit[:12])}；共纳入 {changed} 个受控文件。",
        ["本页不生成未经需求原文核验的产品规则。"],
        overview_code + code_lines[:80] + symbols[:40],
        ["没有真实执行快照。", f"静态识别 {tests_count} 个测试文件，需在发布流程关联执行报告。"],
        ["代码路径和结构证据不能证明运行时行为。", "文件列表按安全上限截断，完整源码仍以 Git commit 为准。"],
        [boundary] + file_evidence[:MAX_EVIDENCE], [f"V{version} code"]))

    for module, items in sorted(modules.items())[:MAX_MODULES]:
        module_id = f"version-{safe(version)}-module-{safe(module)}"
        module_files = [f"{status}: {path}" for status, path, _, _ in items]
        module_symbols = [f"{path} :: {', '.join(item_symbols)}" for _, path, _, item_symbols in items if item_symbols]
        module_evidence = [evidence(source["projectId"], version, commit, path, item_symbols)
                           for _, path, _, item_symbols in items[:MAX_EVIDENCE]]
        pages.append(make_page(
            module_id, f"{version} · {module} 模块", version, "代码模块",
            f"Git 版本 {version} 中，模块 {module} 受控识别 {len(items)} 个文件；内容来自 commit {commit[:12]} 的路径和结构扫描。",
            ["没有关联需求原文，因此不把类名解释为产品规则。"], module_files[:80] + module_symbols[:40],
            ["没有真实执行快照。", f"该模块静态识别测试文件 {sum(is_test(x[1]) for x in items)} 个。"],
            ["自动扫描只保留文件路径和有限结构符号，不复制源码正文。"],
            [boundary] + module_evidence[:MAX_EVIDENCE], [module]))

    enriched["pages"] = sorted(pages, key=lambda p: (p.get("category", ""), p.get("title", "")))
    return enriched


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
        temporary = Path(handle.name)
    temporary.replace(path)


def yaml(value: str) -> str:
    return '"' + str(value or "").replace("\\", "\\\\").replace('"', '\\"') + '"'


def render_markdown(source: dict, page: dict) -> str:
    out = [
        "---\n",
        f"featureId: {yaml(page.get('featureId', ''))}\n",
        f"projectId: {yaml(source.get('projectId', ''))}\n",
        f"version: {yaml(source.get('version', ''))}\n",
        f"status: {page.get('status', '')}\n",
        f"codeCommit: {yaml(source.get('codeCommit', ''))}\n",
        f"generatedAt: {yaml(source.get('generatedAt', ''))}\n",
        f"---\n\n# {page.get('title', '')}\n\n{page.get('summary', '') or ''}\n\n",
    ]
    for title, key in (("产品视角", "productRules"), ("开发视角", "codeSymbols"),
                       ("测试视角", "testPoints"), ("风险与存疑", "risks")):
        values = page.get(key) or []
        out.append(f"## {title}\n\n")
        if values:
            out.extend(f"- {value}\n" for value in values)
            out.append("\n")
        else:
            out.append("- 暂无已核验内容\n\n")
    relations = page.get("relations") or []
    if relations:
        out.append("## 关联功能\n\n")
        for relation in relations:
            out.append(f"- **{relation.get('label', '')}** (`{relation.get('targetFeatureId', '')}`)："
                       f"{relation.get('description', '') or ''}\n")
        out.append("\n")
    evidence_items = page.get("evidence") or []
    if evidence_items:
        out.append("## 原始证据\n\n")
        for item in evidence_items:
            out.append(f"### {item.get('title') or item.get('type') or ''}\n\n")
            out.append(f"- 类型：{item.get('type', '') or ''}\n")
            out.append(f"- 来源：{item.get('source', '') or ''}\n")
            out.append(f"- 版本：{item.get('version', '') or ''}\n")
            for label, key in (("位置", "location"), ("文件", "filePath"), ("符号", "symbol"),
                               ("Commit", "commit"), ("核验状态", "verificationStatus")):
                value = str(item.get(key, '') or '').strip()
                if value:
                    out.append(f"- {label}：{value}\n")
            excerpt = str(item.get("excerpt", "") or "")
            if excerpt.strip():
                out.append("\n> " + excerpt.replace("\n", "\n> ") + "\n")
            out.append("\n")
    return "".join(out)


def render_artifacts(source: dict, root: Path) -> int:
    project = safe(source["projectId"])
    version = safe(source["version"])
    target = root / project / version
    with tempfile.TemporaryDirectory(dir=root / project if (root / project).exists() else root) as staging_name:
        staging = Path(staging_name) / "pages"
        staging.mkdir(parents=True, exist_ok=True)
        summaries = []
        for page in source.get("pages", []):
            page_copy = {
                "projectId": source["projectId"],
                "projectName": source.get("projectName", "") or "",
                "version": source["version"],
                "requirementVersion": source.get("requirementVersion", "") or "",
                "baseCodeCommit": source.get("baseCodeCommit", "") or "",
                "codeCommit": source.get("codeCommit", "") or "",
                "generatedAt": source.get("generatedAt", "") or "",
                "featureId": page["featureId"],
                "title": page.get("title", "") or "",
                "category": page.get("category") or "未分类",
                "introducedVersion": page.get("introducedVersion") or version,
                "status": page["status"],
                "aliases": page.get("aliases") or [],
                "summary": page.get("summary", "") or "",
                "productRules": page.get("productRules") or [],
                "codeSymbols": page.get("codeSymbols") or [],
                "testPoints": page.get("testPoints") or [],
                "risks": page.get("risks") or [],
                "relations": page.get("relations") or [],
                "evidence": page.get("evidence") or [],
                "markdownPath": f"pages/{page['featureId']}.md",
            }
            write_json(staging / f"{page['featureId']}.json", page_copy)
            (staging / f"{page['featureId']}.md").write_text(render_markdown(source, page), encoding="utf-8")
            summaries.append({"featureId": page["featureId"], "title": page.get("title", "") or "",
                              "category": page.get("category") or "未分类",
                              "introducedVersion": page.get("introducedVersion") or version, "status": page["status"],
                              "summary": page.get("summary", "") or "", "aliases": page.get("aliases") or [],
                              "evidenceCount": len(page.get("evidence") or [])})
        summaries.sort(key=lambda item: (item["category"], item["title"]))
        index = {"schemaVersion": source.get("schemaVersion", 1), "projectId": source["projectId"],
                 "projectName": source.get("projectName", ""), "version": source["version"],
                 "requirementVersion": source.get("requirementVersion", ""),
                 "baseCodeCommit": source.get("baseCodeCommit", ""), "codeCommit": source.get("codeCommit", ""),
                 "generatedAt": source.get("generatedAt", ""), "pages": summaries}
        write_json(Path(staging.parent) / "index.json", index)
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            for child in target.iterdir():
                if child.is_dir():
                    for item in sorted(child.rglob("*"), reverse=True):
                        if item.is_file(): item.unlink()
                    child.rmdir()
                else:
                    child.unlink()
        target.mkdir(parents=True, exist_ok=True)
        for item in (staging.parent).iterdir():
            item.replace(target / item.name)
    return len(summaries)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path("/Users/user/Documents/immortal-game-service"))
    parser.add_argument("--sources", type=Path, default=Path("data/wiki-sources"))
    parser.add_argument("--wiki-root", type=Path, default=Path("data/wiki"))
    parser.add_argument("--version")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    repo = args.repo.expanduser().resolve()
    sources = args.sources.resolve()
    wiki_root = args.wiki_root.resolve()
    if not (repo / ".git").is_dir():
        raise SystemExit(f"Git 仓库不存在：{repo}")
    paths = sorted(sources.glob("*-v*.json"))
    if args.version:
        paths = [p for p in paths if json.loads(p.read_text(encoding="utf-8")).get("version") == args.version]
    if not paths:
        raise SystemExit("没有找到待构建的 Wiki 源定义")
    total_pages = 0
    for path in paths:
        source = json.loads(path.read_text(encoding="utf-8"))
        enriched = enrich_source(source, repo)
        if not args.dry_run:
            write_json(path, enriched)
            total_pages += render_artifacts(enriched, wiki_root)
        else:
            total_pages += len(enriched.get("pages", []))
        print(f"{source['version']}: {len(enriched.get('pages', []))} pages, code={source.get('codeCommit', '')[:12]}")
    print(f"built versions={len(paths)} pages={total_pages}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
