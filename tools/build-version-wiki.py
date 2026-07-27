#!/usr/bin/env python3
"""Build evidence-bound, version-scoped Wiki artifacts.

Requirement snapshots are optional local inputs and are never copied as files.
The generated Wiki stores bounded excerpts, hashes and Git locations only. It
never reads or writes Qdrant/vector data and never claims a test run exists
unless an explicit execution reference is supplied by a reviewed source.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Iterable

MAX_FILES = 160
MAX_SYMBOLS_PER_FILE = 8
MAX_CODE_ENTRIES = 6
MAX_EVIDENCE = 24
MAX_SECTION_ITEMS = 12
FORBIDDEN = re.compile(
    r"(?i)(api[_-]?key|password|secret|token|authorization|credential|embedding|vector|qdrant|wal)"
)
COMMIT = re.compile(r"^[0-9a-fA-F]{7,64}$")
JAVA_TYPE = re.compile(r"\b(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)")
JAVA_METHOD = re.compile(
    r"\b(?:public|protected|private|static|final|synchronized|default|abstract|native|\s)+"
    r"[\w<>,.?\[\] ]+\s+([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*(?:throws\s+[\w., ]+)?\s*[{;]"
)
CONTROL = {"if", "for", "while", "switch", "catch", "return", "new", "assert", "do"}
MAJOR_HEADING = re.compile(
    r"^(?:[一二三四五六七八九十]+、|\d+(?:\.\d+)+[、.．)]?\s*)"
)
ITEM_MARKER = re.compile(
    r"^(?:[•·●▪◦*-]|\(?\d+\)?[、.．)]|[a-zA-Z][、.．)]|[oO]\s+)\s*"
)
ASCII_TERM = re.compile(r"\b[A-Za-z][A-Za-z0-9_]{3,}\b")
ASCII_STOP = {
    "http", "https", "html", "true", "false", "null", "string", "boolean", "system", "document",
    "mobile", "page", "button", "title", "version", "interface", "return", "config", "data",
}
BOUNDARY_WORDS = ("不可", "不能", "不得", "仅", "只", "未", "不再", "清空", "重复", "非法", "满", "限制", "幂等", "条件")
DATA_WORDS = ("配置", "数据", "状态", "奖励", "消耗", "记录", "货币", "道具", "资源", "字段", "表", "存储")
PROCESS_WORDS = ("入口", "流程", "操作", "页面", "界面", "点击", "打开", "返回", "领取", "购买", "升级")
QUESTION_WORDS = ("问题：", "问题:", "什么时候", "是否提供", "待确认", "待补充", "待定")
ACTION_WORDS = ("应", "需要", "需", "新增", "添加", "调整", "改为", "显示", "展示", "返回", "消耗", "清空", "不再", "仅", "支持")
TABLE_HEADERS = {"编号", "序号", "类别", "类型", "标题", "内容", "说明"}


def run_git(repo: Path, *args: str, allow_failure: bool = False) -> str:
    process = subprocess.run(
        ["git", *args], cwd=repo, text=True, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, check=False, timeout=30,
    )
    if process.returncode and not allow_failure:
        raise RuntimeError(f"git {' '.join(args)} failed: {process.stderr.strip()[:300]}")
    return process.stdout


def safe(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "-", str(value or "")).strip("-") or "version"


def clean_text(value: str, limit: int | None = None) -> str:
    normalized = re.sub(r"\s+", " ", str(value or "")).strip()
    if FORBIDDEN.search(normalized):
        return ""
    if limit and len(normalized) > limit:
        return normalized[:limit].rstrip() + "…"
    return normalized


def stable_id(value: str) -> str:
    return hashlib.sha256(str(value or "").encode("utf-8")).hexdigest()[:16]


def is_relevant(path: str) -> bool:
    normalized = path.replace("\\", "/")
    lower = normalized.lower()
    if any(part in lower.split("/") for part in (".git", "target", "node_modules", "build", ".idea")):
        return False
    if any(part.startswith(".") for part in normalized.split("/") if part not in (".", "..")):
        return False
    return lower.endswith((
        ".java", ".kt", ".groovy", ".xml", ".yml", ".yaml", ".properties", ".sql", ".json",
        "pom.xml", "gradlew", "build.gradle", "settings.gradle",
    )) and not any(x in lower for x in ("/generated/", "/resources/static/", "/resources/public/"))


def is_test(path: str) -> bool:
    lower = path.lower()
    return "/test/" in lower or lower.endswith(("test.java", "tests.java", "test.kt", "tests.kt"))


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
        changes = [parsed for line in output.splitlines() if (parsed := parse_status(line))]
    else:
        output = run_git(repo, "ls-tree", "-r", "--name-only", commit)
        changes = [("SNAPSHOT", path, None) for path in output.splitlines() if path]
    priority = {"ADDED": 0, "MODIFIED": 1, "RENAMED": 2, "SNAPSHOT": 3, "DELETED": 4}
    selected = [item for item in changes if is_relevant(item[1])]
    selected.sort(key=lambda item: (priority.get(item[0], 9), item[1]))
    return selected[:MAX_FILES]


def symbols_for(repo: Path, commit: str, path: str) -> list[str]:
    if not path.lower().endswith((".java", ".kt", ".groovy")):
        return []
    text = run_git(repo, "show", f"{commit}:{path}", allow_failure=True)
    if not text:
        return []
    values: list[str] = []
    for match in JAVA_TYPE.finditer(text):
        if match.group(1) not in values:
            values.append(match.group(1))
    for match in JAVA_METHOD.finditer(text):
        name = match.group(1)
        if name not in CONTROL and name not in values:
            values.append(name)
    return values[:MAX_SYMBOLS_PER_FILE]


def commit_meta(repo: Path, commit: str) -> tuple[str, str]:
    line = run_git(repo, "show", "-s", "--format=%ad%x09%s", "--date=iso-strict", commit).strip()
    authored, _, subject = line.partition("\t")
    return authored, clean_text(subject or authored) or "未提供提交说明"


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


def requirement_snapshot(requirement_root: Path, project: str, version: str) -> dict | None:
    path = requirement_root / safe(project) / f"{safe(version)}.json"
    if not path.is_file():
        return None
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("projectId") != project or value.get("requirementVersion") != version:
        raise ValueError(f"需求快照与 Wiki 版本不匹配：{project} {version}")
    return value


def normalized_lines(text: str) -> list[str]:
    result: list[str] = []
    for raw in str(text or "").replace("\ufeff", "").splitlines():
        line = re.sub(r"\s+", " ", raw).strip()
        if not line or FORBIDDEN.search(line):
            continue
        if not result or result[-1] != line:
            result.append(line)
    return result


def strip_marker(value: str) -> str:
    return clean_text(ITEM_MARKER.sub("", MAJOR_HEADING.sub("", value)).strip("：: "))


def requirement_title(entry: dict) -> str:
    filename = str(entry.get("filename") or "").replace("\\", "/").split("/")[-1]
    filename = filename.split("#")[-1]
    title = re.sub(r"\.(?:html?|xlsx?|docx?|pdf|md|txt)$", "", filename, flags=re.I)
    title = re.sub(r"^(?:需求|策划|福利)[-_—：: ]+", "", title).strip()
    if title:
        return title
    lines = normalized_lines(entry.get("text") or "")
    return strip_marker(lines[0]) if lines else "未命名需求"


def actionable(entry: dict) -> bool:
    """Return true for a requirement fact, including a short declarative change.

    Short coordination questions are intentionally excluded, but a single precise
    requirement sentence is valid and must not disappear merely because it has no
    numbered section.
    """
    lines = normalized_lines(entry.get("text") or "")
    title = requirement_title(entry)
    body = [line for line in lines if line != title and not line.endswith(title)]
    text = clean_text(" ".join(body))
    if len(text) < 12:
        return False
    if any(word in text for word in QUESTION_WORDS) and not any(word in text for word in ACTION_WORDS):
        return False
    if any(MAJOR_HEADING.match(line) for line in body):
        return True
    return any(word in text for word in ACTION_WORDS) or len(text) >= 80


def informative(line: str) -> bool:
    value = strip_marker(line)
    if not value or value in TABLE_HEADERS:
        return False
    if re.fullmatch(r"[\d.%+\-:/（）()]+", value):
        return False
    if value.lower().startswith(("flowchart ", "graph ")) or "-->" in value:
        return False
    return len(value) >= 3


def extract_table_rows(lines: list[str]) -> tuple[set[int], list[str]]:
    """Extract simple OCR/HTML tables emitted as one cell per line."""
    consumed: set[int] = set()
    statements: list[str] = []
    try:
        header = next(i for i, line in enumerate(lines) if line in {"编号", "序号"})
    except StopIteration:
        return consumed, statements
    index = header
    while index < len(lines) and lines[index] in TABLE_HEADERS:
        consumed.add(index)
        index += 1
    while index < len(lines):
        if not re.fullmatch(r"\d+", lines[index]):
            break
        consumed.add(index)
        index += 1
        cells: list[str] = []
        while index < len(lines) and not re.fullmatch(r"\d+", lines[index]):
            if MAJOR_HEADING.match(lines[index]):
                break
            consumed.add(index)
            if informative(lines[index]):
                cells.append(strip_marker(lines[index]))
            index += 1
        if cells:
            append_unique(statements, " / ".join(cells), MAX_SECTION_ITEMS)
    return consumed, statements


def bucket_for(heading: str, statement: str) -> str:
    context = heading + " " + statement
    if any(word in heading for word in ("异常", "边界", "限制", "红点", "提醒")):
        return "boundaryConditions"
    if any(word in statement for word in BOUNDARY_WORDS):
        return "boundaryConditions"
    if any(word in heading for word in ("入口", "流程", "页面", "界面", "展示", "操作", "外显")):
        return "processSteps"
    if any(word in heading for word in ("配置", "数据", "奖励", "消耗", "状态", "记录描述")):
        return "dataImpacts"
    if any(word in heading for word in ("基础", "规则", "说明", "目标")):
        if any(word in statement for word in ("点击", "打开", "返回", "领取", "购买")):
            return "processSteps"
        return "productRules"
    if any(word in context for word in DATA_WORDS):
        return "dataImpacts"
    if any(word in context for word in PROCESS_WORDS):
        return "processSteps"
    return "productRules"


def append_unique(target: list[str], value: str, limit: int = MAX_SECTION_ITEMS) -> None:
    cleaned = clean_text(value, 260)
    if cleaned and cleaned not in target and len(target) < limit:
        target.append(cleaned)


def parse_requirement(entry: dict) -> dict:
    title = requirement_title(entry)
    lines = normalized_lines(entry.get("text") or "")
    sections = {key: [] for key in (
        "productRules", "processSteps", "dataImpacts", "boundaryConditions",
    )}
    table_indices, table_rows = extract_table_rows(lines)
    has_major_heading = any(MAJOR_HEADING.match(line) for line in lines)
    current_heading = ""
    started = not has_major_heading
    for index, line in enumerate(lines):
        if index in table_indices or line == title or line.endswith(title):
            continue
        if MAJOR_HEADING.match(line):
            current_heading = strip_marker(line)
            started = True
            continue
        if not started or not informative(line):
            continue
        statement = strip_marker(line)
        if statement == current_heading:
            continue
        append_unique(sections[bucket_for(current_heading, statement)], statement)

    for statement in table_rows:
        append_unique(sections[bucket_for("记录描述", statement)], statement)

    facts = sections["productRules"] + sections["processSteps"] + sections["dataImpacts"]
    summary_parts = [item for item in facts if len(item) >= 8][:2]
    summary = clean_text("；".join(summary_parts), 360)
    if not summary:
        candidates = [strip_marker(line) for line in lines if informative(line) and line != title]
        summary = clean_text("；".join(candidates[:2]), 360)
    acceptance: list[str] = []
    for statement in (sections["productRules"] + sections["processSteps"]
                      + sections["dataImpacts"] + sections["boundaryConditions"]):
        append_unique(acceptance, "确认：" + statement, 12)
    # Structured acceptance criteria are the review contract. Keep testPoints empty
    # until a reviewer supplies a genuinely distinct test design; never duplicate
    # requirement facts as if they were additional test knowledge.
    tests: list[str] = []
    return {
        "title": title,
        "summary": summary or "该需求已进入当前版本知识范围，详细规则见需求证据。",
        **sections,
        "acceptanceCriteria": acceptance,
        "testPoints": tests,
    }


def feature_category(title: str, text: str) -> str:
    value = title + " " + text[:300]
    if any(word in value for word in ("页面", "界面", "交互", "展示")):
        return "界面与交互"
    if any(word in value for word in ("数据", "字段", "配置", "存储")):
        return "数据与配置"
    if any(word in value for word in ("接口", "服务", "流程", "状态")):
        return "业务流程"
    if any(word in value for word in ("消息", "通知", "提醒", "记录")):
        return "通知与记录"
    return "需求功能"


def search_terms(title: str, text: str) -> list[str]:
    terms: list[str] = []
    compact_title = re.sub(r"[\s_\-—：:（）()]+", "", title)
    if len(compact_title) >= 4:
        terms.append(compact_title)
    for term in ASCII_TERM.findall(text):
        if term.lower() not in ASCII_STOP and term not in terms:
            terms.append(term)
    return terms[:8]


def grep_code(repo: Path, commit: str, term: str) -> list[tuple[str, int]]:
    output = run_git(
        repo, "grep", "-I", "-n", "-F", "-i", "-e", term, commit, "--",
        "*.java", "*.kt", "*.groovy", "*.xml", "*.yml", "*.yaml", "*.properties", "*.sql",
        allow_failure=True,
    )
    matches: list[tuple[str, int]] = []
    prefix = commit + ":"
    for line in output.splitlines():
        value = line[len(prefix):] if line.startswith(prefix) else line
        path, separator, rest = value.partition(":")
        if not separator or not is_relevant(path):
            continue
        number, separator, _ = rest.partition(":")
        if separator and number.isdigit():
            matches.append((path, int(number)))
    return matches


def code_role(path: str, symbols: list[str]) -> str:
    value = (path + " " + " ".join(symbols)).lower()
    if any(word in value for word in ("controller", "moaservice", "api", "handler")):
        return "接口或操作入口"
    if any(word in value for word in ("config", "properties", "xml", "yml")):
        return "配置或数据定义"
    if any(word in value for word in ("service", "manager")):
        return "业务服务"
    if is_test(path):
        return "静态测试代码"
    return "相关实现"


def code_for_requirement(repo: Path, commit: str, base: str, title: str, text: str,
                         change_by_path: dict[str, str]) -> tuple[list[dict], list[dict], list[str]]:
    scores: dict[str, dict] = {}
    for term in search_terms(title, text):
        for path, line in grep_code(repo, commit, term)[:40]:
            item = scores.setdefault(path, {"terms": set(), "lines": []})
            item["terms"].add(term)
            if line not in item["lines"]:
                item["lines"].append(line)
    compact_title = re.sub(r"[\s_\-—：:（）()]+", "", title)
    candidates = [
        item for item in scores.items()
        if compact_title in item[1]["terms"] or len(item[1]["terms"]) >= 2
    ]
    ranked = sorted(
        candidates,
        key=lambda item: (compact_title not in item[1]["terms"], -len(item[1]["terms"]), item[0]),
    )[:MAX_CODE_ENTRIES]
    entries: list[dict] = []
    evidence_items: list[dict] = []
    symbols_text: list[str] = []
    for path, match in ranked:
        symbols = symbols_for(repo, commit, path)
        symbol = ", ".join(symbols)
        change_type = change_by_path.get(path, "UNCHANGED")
        role = code_role(path, symbols)
        entries.append({
            "role": role,
            "filePath": path,
            "symbol": symbol,
            "commit": commit,
            "changeType": change_type,
            "verificationStatus": "VERIFIED",
        })
        label = f"{role}：{path}" + (f" :: {symbol}" if symbol else "")
        append_unique(symbols_text, label, MAX_CODE_ENTRIES)
        lines = ", ".join(str(line) for line in match["lines"][:5])
        terms = "、".join(sorted(match["terms"]))
        evidence_items.append({
            "type": "CODE",
            "title": f"代码证据：{path}",
            "source": "Git",
            "version": "",
            "location": f"lines={lines}" if lines else path,
            "excerpt": clean_text(f"在目标 commit 中由关键词“{terms}”定位到该文件；仅证明静态位置存在，不等同于运行行为。"),
            "commit": commit,
            "filePath": path,
            "symbol": symbol,
            "verificationStatus": "VERIFIED",
        })
    return entries, evidence_items, symbols_text


def requirement_source(snapshot: dict, entry: dict) -> dict:
    return {
        "documentId": snapshot.get("documentId", ""),
        "entryId": entry.get("entryId", ""),
        "filename": entry.get("filename", ""),
        "version": snapshot.get("requirementVersion", ""),
        "location": f"parentOrder={entry.get('parentOrder', 0)}",
        "contentHash": entry.get("contentHash", ""),
        "verificationStatus": "SOURCE_CAPTURED",
    }


def requirement_evidence(snapshot: dict, entry: dict, title: str) -> dict:
    return {
        "type": "REQUIREMENT",
        "title": f"需求来源：{title}",
        "source": entry.get("filename", ""),
        "version": snapshot.get("requirementVersion", ""),
        "location": f"parentOrder={entry.get('parentOrder', 0)}",
        "excerpt": clean_text(entry.get("text", ""), 720),
        "commit": "",
        "filePath": "",
        "symbol": "",
        "verificationStatus": "SOURCE_CAPTURED",
    }


def page_for_entry(source: dict, snapshot: dict, entry: dict, repo: Path, commit: str, base: str,
                   change_by_path: dict[str, str]) -> dict:
    parsed = parse_requirement(entry)
    requirement = requirement_source(snapshot, entry)
    code_entries, code_evidence, code_symbols = code_for_requirement(
        repo, commit, base, parsed["title"], entry.get("text", ""), change_by_path,
    ) if COMMIT.fullmatch(commit or "") else ([], [], [])
    missing = []
    if not code_entries:
        missing.append("代码证据")
    missing.append("真实测试执行快照")
    status = "CODE_VERIFIED" if code_entries else "REQUIREMENT_VERIFIED"
    feature_identity = "|".join((
        str(snapshot.get("requirementVersion") or ""),
        str(entry.get("filename") or ""),
        str(entry.get("contentHash") or entry.get("entryId") or parsed["title"]),
    ))
    feature_id = "requirement-" + stable_id(feature_identity)
    version = source["version"]
    return {
        "featureId": feature_id,
        "title": parsed["title"],
        "category": feature_category(parsed["title"], entry.get("text", "")),
        "introducedVersion": version,
        "status": status,
        "aliases": [],
        "summary": parsed["summary"],
        "requirementSources": [requirement],
        "productRules": parsed["productRules"],
        "processSteps": parsed["processSteps"],
        "codeEntries": code_entries,
        "codeSymbols": code_symbols,
        "dataImpacts": parsed["dataImpacts"],
        "boundaryConditions": parsed["boundaryConditions"],
        "acceptanceCriteria": parsed["acceptanceCriteria"],
        "testPoints": parsed["testPoints"],
        "testKnowledge": {
            "executionStatus": "NOT_AVAILABLE",
            "executionReference": "",
            "summary": "没有真实执行快照",
            "cases": [],
        },
        "versionChange": {
            "changeType": "ADDED",
            "baseVersion": snapshot.get("baseRequirementVersion", ""),
            "version": version,
            "summary": f"{version} 增量需求新增；未明确标注的内容不解释为删除。",
        },
        "quality": {
            "reviewStatus": "PENDING_REVIEW",
            "requirementEvidenceCount": 1,
            "codeEvidenceCount": len(code_entries),
            "realTestExecution": False,
            "missing": missing,
        },
        "risks": (["尚未关联到可核验的代码入口。"] if not code_entries else [])
                 + ["测试建议来自需求事实，不代表测试已经执行。"],
        "relations": [],
        "evidence": [requirement_evidence(snapshot, entry, parsed["title"]), *code_evidence],
    }


def overview_page(source: dict, snapshot: dict | None, pages: list[dict], boundary: dict | None,
                  files: list[tuple[str, str, str | None]], subject: str, authored: str) -> dict:
    version = source["version"]
    requirement_pages = [page for page in pages if page.get("requirementSources")]
    code_pages = [page for page in requirement_pages if page.get("codeEntries")]
    skipped = int((snapshot or {}).get("skippedEntries", 0))
    summary = (
        f"{version} 已形成 {len(requirement_pages)} 个需求功能页，其中 {len(code_pages)} 个已关联静态代码证据。"
        "页面以需求事实为主体，Git 只用于证明代码位置；测试执行结果尚未接入。"
    )
    code_commit = source.get("codeCommit", "")
    base = source.get("baseCodeCommit", "")
    code_symbols = []
    if code_commit:
        code_symbols.append(f"代码边界：{base[:12] + ' → ' if base else ''}{code_commit[:12]}")
        code_symbols.append(f"受控代码/配置变化文件：{len(files)} 个")
    evidence_items = [boundary] if boundary else []
    missing = ["真实测试执行快照"]
    if len(code_pages) < len(requirement_pages):
        missing.append(f"{len(requirement_pages) - len(code_pages)} 个功能的代码证据")
    return {
        "featureId": f"version-{safe(version)}-overview",
        "title": f"{version} 版本知识概览",
        "category": "版本概览",
        "introducedVersion": version,
        "status": "REQUIREMENT_VERIFIED" if requirement_pages else "DRAFT",
        "aliases": [f"V{version}"],
        "summary": summary,
        "requirementSources": [],
        "productRules": [
            f"{version} 需求按增量口径记录新增内容；没有明确标注时不推断为删除。",
            "功能页必须至少保留一条需求证据，代码和测试缺失时必须明确展示缺口。",
        ],
        "processSteps": ["从左侧选择具体需求功能页。", "先阅读需求与业务规则，再核验代码入口和测试状态。"],
        "codeEntries": [],
        "codeSymbols": code_symbols,
        "dataImpacts": [],
        "boundaryConditions": [],
        "acceptanceCriteria": ["每个有效需求条目均生成独立页面。", "相近名称的不同需求不得自动合并。"],
        "testPoints": [],
        "testKnowledge": {
            "executionStatus": "NOT_AVAILABLE",
            "executionReference": "",
            "summary": "没有真实执行快照",
            "cases": [],
        },
        "versionChange": {
            "changeType": "VERSION_INCREMENT",
            "baseVersion": (snapshot or {}).get("baseRequirementVersion", ""),
            "version": version,
            "summary": f"本版本收录 {len(requirement_pages)} 个新增需求功能页。",
        },
        "quality": {
            "reviewStatus": "PENDING_REVIEW",
            "requirementEvidenceCount": len(requirement_pages),
            "codeEvidenceCount": sum(len(page.get("codeEntries") or []) for page in requirement_pages),
            "realTestExecution": False,
            "missing": missing + ([f"{skipped} 个非功能条目未发布"] if skipped else []),
        },
        "risks": [
            "静态代码命中只证明文件和符号存在，不证明运行时行为。",
            "业务规则仍需产品、开发和测试共同审核。",
        ],
        "relations": [],
        "evidence": evidence_items,
    }


def enrich_source(source: dict, repo: Path, requirement_root: Path | None = None) -> dict:
    version = source["version"]
    commit = source.get("codeCommit") or ""
    base = source.get("baseCodeCommit") or ""
    files: list[tuple[str, str, str | None]] = []
    boundary = None
    subject = ""
    authored = ""
    change_by_path: dict[str, str] = {}
    if COMMIT.fullmatch(commit):
        run_git(repo, "cat-file", "-e", f"{commit}^{{commit}}")
        if base:
            run_git(repo, "cat-file", "-e", f"{base}^{{commit}}")
        authored, subject = commit_meta(repo, commit)
        files = files_for_version(repo, base, commit)
        change_by_path = {path: status for status, path, _ in files}
        boundary = git_evidence(source["projectId"], version, commit, base, subject, files)

    snapshot = requirement_snapshot(requirement_root, source["projectId"], version) if requirement_root else None
    pages: list[dict] = []
    if snapshot:
        entries = [entry for entry in snapshot.get("entries", []) if actionable(entry)]
        skipped = len(snapshot.get("entries", [])) - len(entries)
        snapshot = dict(snapshot)
        snapshot["skippedEntries"] = skipped
        pages = [page_for_entry(source, snapshot, entry, repo, commit, base, change_by_path) for entry in entries]
    else:
        generated_prefix = f"version-{safe(version)}-module-"
        pages = [dict(page) for page in source.get("pages", [])
                 if page.get("featureId") != f"version-{safe(version)}-code-structure"
                 and not str(page.get("featureId", "")).startswith(generated_prefix)
                 and not str(page.get("featureId", "")).startswith("version-")]

    overview = overview_page(source, snapshot, pages, boundary, files, subject, authored)
    result = dict(source)
    result["schemaVersion"] = 2
    result["pages"] = sorted([overview, *pages], key=lambda page: (page.get("category", ""), page.get("title", "")))
    return result


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
        temporary = Path(handle.name)
    temporary.replace(path)


def yaml(value: str) -> str:
    return '"' + str(value or "").replace("\\", "\\\\").replace('"', '\\"') + '"'


def markdown_section(out: list[str], title: str, values: Iterable[str], empty: str = "暂无已核验内容") -> None:
    items = [str(value) for value in (values or []) if str(value).strip()]
    out.append(f"## {title}\n\n")
    out.extend(f"- {item}\n" for item in items)
    out.append("\n" if items else f"- {empty}\n\n")


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
    markdown_section(out, "业务规则", page.get("productRules") or [])
    markdown_section(out, "处理流程", page.get("processSteps") or [])
    markdown_section(out, "数据与配置影响", page.get("dataImpacts") or [])
    markdown_section(out, "异常与边界条件", page.get("boundaryConditions") or [])
    markdown_section(out, "代码入口", page.get("codeSymbols") or [], "尚未关联代码实现")
    markdown_section(out, "测试与验收", page.get("acceptanceCriteria") or [])
    test = page.get("testKnowledge") or {}
    markdown_section(out, "测试执行状态", [test.get("summary") or "没有真实执行快照"])
    markdown_section(out, "风险与存疑", page.get("risks") or [])
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
                value = str(item.get(key, "") or "").strip()
                if value:
                    out.append(f"- {label}：{value}\n")
            excerpt = str(item.get("excerpt", "") or "")
            if excerpt.strip():
                out.append("\n> " + excerpt.replace("\n", "\n> ") + "\n")
            out.append("\n")
    return "".join(out).rstrip() + "\n"


def render_artifacts(source: dict, root: Path) -> int:
    project = safe(source["projectId"])
    version = safe(source["version"])
    target = root / project / version
    target.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f".{version}.next-", dir=target.parent))
    backup = target.with_name(f".{version}.old")
    try:
        pages_dir = staging / "pages"
        pages_dir.mkdir(parents=True, exist_ok=True)
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
                **page,
                "markdownPath": f"pages/{page['featureId']}.md",
            }
            write_json(pages_dir / f"{page['featureId']}.json", page_copy)
            (pages_dir / f"{page['featureId']}.md").write_text(render_markdown(source, page), encoding="utf-8")
            summaries.append({
                "featureId": page["featureId"], "title": page.get("title", "") or "",
                "category": page.get("category") or "未分类",
                "introducedVersion": page.get("introducedVersion") or version,
                "status": page.get("status") or "DRAFT", "summary": page.get("summary", "") or "",
                "aliases": page.get("aliases") or [], "evidenceCount": len(page.get("evidence") or []),
            })
        summaries.sort(key=lambda item: (item["category"], item["title"]))
        index = {
            "schemaVersion": source.get("schemaVersion", 2), "projectId": source["projectId"],
            "projectName": source.get("projectName", ""), "version": source["version"],
            "requirementVersion": source.get("requirementVersion", ""),
            "baseCodeCommit": source.get("baseCodeCommit", ""), "codeCommit": source.get("codeCommit", ""),
            "generatedAt": source.get("generatedAt", ""), "pages": summaries,
        }
        write_json(staging / "index.json", index)
        if backup.exists():
            shutil.rmtree(backup)
        if target.exists():
            target.replace(backup)
        staging.replace(target)
        if backup.exists():
            shutil.rmtree(backup)
        return len(summaries)
    except Exception:
        if staging.exists():
            shutil.rmtree(staging)
        if not target.exists() and backup.exists():
            backup.replace(target)
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--sources", type=Path, default=Path("data/wiki-sources"))
    parser.add_argument("--wiki-root", type=Path, default=Path("data/wiki"))
    parser.add_argument("--requirement-root", type=Path, default=Path("data/requirement-snapshots"))
    parser.add_argument("--version")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    repo = args.repo.expanduser().resolve()
    sources = args.sources.resolve()
    wiki_root = args.wiki_root.resolve()
    requirement_root = args.requirement_root.resolve()
    if not (repo / ".git").is_dir():
        raise SystemExit(f"Git 仓库不存在：{repo}")
    paths = sorted(sources.glob("*-v*.json"))
    if args.version:
        paths = [path for path in paths
                 if json.loads(path.read_text(encoding="utf-8")).get("version") == args.version]
    if not paths:
        raise SystemExit("没有找到待构建的 Wiki 源定义")
    total_pages = 0
    for path in paths:
        source = json.loads(path.read_text(encoding="utf-8"))
        enriched = enrich_source(source, repo, requirement_root)
        if not args.dry_run:
            write_json(path, enriched)
            total_pages += render_artifacts(enriched, wiki_root)
        else:
            total_pages += len(enriched.get("pages", []))
        linked = sum(bool(page.get("codeEntries")) for page in enriched.get("pages", [])
                     if page.get("requirementSources"))
        requirements = sum(bool(page.get("requirementSources")) for page in enriched.get("pages", []))
        print(f"{source['version']}: pages={len(enriched.get('pages', []))} requirements={requirements} code-linked={linked}")
    print(f"built versions={len(paths)} pages={total_pages}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
