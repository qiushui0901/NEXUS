#!/usr/bin/env python3
"""Generate a 200-document + 500-code Fengshen retrieval evaluation set."""

from __future__ import annotations

import argparse
import json
import re
import zipfile
from collections import deque
from pathlib import Path
from xml.etree import ElementTree


NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
REL_NS = {"r": "http://schemas.openxmlformats.org/package/2006/relationships"}
METHOD = re.compile(
    r"(?P<comment>/\*\*.*?\*/\s*)?"
    r"(?:@[\w.]+(?:\([^)]*\))?\s*)*"
    r"(?:public|protected)\s+(?:static\s+)?(?:final\s+)?"
    r"(?:<[^>{}]+>\s+)?[\w.$<>?, \[\]]+\s+"
    r"(?P<name>[A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*(?:throws\s+[^{]+)?\{",
    re.DOTALL,
)
CLASS = re.compile(r"\b(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)")
CAMEL = re.compile(r"(?<=[a-z0-9])(?=[A-Z])")
SKIP_METHODS = {
    "build", "clear", "clone", "equals", "hashCode", "newBuilder", "toBuilder", "toString",
    "getClass", "wait", "notify", "notifyAll",
}
BUSINESS_WORDS = {
    "activity": "活动", "announce": "公告", "artifact": "神器", "battle": "战斗",
    "build": "建筑", "cancel": "取消", "chat": "聊天", "collect": "采集",
    "city": "城池", "compact": "同盟", "crazy": "疯狂", "destiny": "天命",
    "dig": "挖宝", "discount": "特价礼包", "fight": "战斗", "fund": "基金",
    "grow": "成长", "fire": "火力", "focus": "集火", "goods": "物资", "guard": "守护",
    "handler": "处理", "hero": "英雄", "home": "大本营", "hunyuan": "混元", "item": "道具",
    "league": "联盟", "limit": "限时", "link": "链接", "linkage": "联动",
    "mail": "邮件", "march": "行军", "maze": "迷宫", "money": "摇钱树",
    "mine": "矿点", "mount": "坐骑", "pet": "仙宠", "plugin": "逻辑", "rank": "排行", "recharge": "充值",
    "role": "主角", "server": "服务器", "shop": "商店", "spirit": "仙灵",
    "standard": "达标活动", "task": "任务", "title": "称号", "union": "联盟",
    "vip": "VIP", "warehouse": "仓库", "war": "战场", "world": "大世界",
}


def cell_text(cell: ElementTree.Element, shared: list[str]) -> str:
    if cell.attrib.get("t") == "s":
        value = cell.findtext("m:v", default="", namespaces=NS)
        return shared[int(value)] if value.isdigit() and int(value) < len(shared) else ""
    return "".join(node.text or "" for node in cell.findall(".//m:t", NS)).strip()


def workbook_rows(path: Path) -> list[tuple[str, list[tuple[str, str, str]]]]:
    with zipfile.ZipFile(path) as archive:
        shared = []
        if "xl/sharedStrings.xml" in archive.namelist():
            root = ElementTree.fromstring(archive.read("xl/sharedStrings.xml"))
            shared = ["".join(t.text or "" for t in item.findall(".//m:t", NS))
                      for item in root.findall("m:si", NS)]
        workbook = ElementTree.fromstring(archive.read("xl/workbook.xml"))
        relations = ElementTree.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
        targets = {item.attrib["Id"]: item.attrib["Target"] for item in relations.findall("r:Relationship", REL_NS)}
        result = []
        for sheet in workbook.findall("m:sheets/m:sheet", NS):
            rel_id = sheet.attrib["{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id"]
            target = targets[rel_id].lstrip("/")
            target = target if target.startswith("xl/") else "xl/" + target
            root = ElementTree.fromstring(archive.read(target))
            rows = []
            for row in root.findall("m:sheetData/m:row", NS):
                values = {re.sub(r"\d+$", "", cell.attrib.get("r", "")): cell_text(cell, shared)
                          for cell in row.findall("m:c", NS)}
                question = normalize(values.get("B", ""))
                if not question or question in {"问题", "功能点", "QA存疑"}:
                    continue
                rows.append((row.attrib.get("r", ""), question, normalize(values.get("C", ""))))
            if rows:
                result.append((sheet.attrib["name"], rows))
        return result


def normalize(value: str) -> str:
    return re.sub(r"\s+", " ", value or "").strip()


def document_records(workbook: Path, limit: int = 200) -> list[dict]:
    queues = deque((sheet, deque(rows)) for sheet, rows in workbook_rows(workbook))
    records = []
    seen = set()
    while queues and len(records) < limit:
        sheet, rows = queues.popleft()
        row, source_question, answer = rows.popleft()
        key = normalize(source_question).lower()
        if key not in seen:
            seen.add(key)
            query = source_question if source_question.endswith(("？", "?")) else source_question + "的业务规则是什么？"
            pending = not answer or any(marker in answer for marker in ("待定", "待确认", "暂无", "未提供"))
            records.append({
                "id": f"fengshen-doc-{len(records) + 1:03d}",
                "type": "DOCUMENT",
                "queryMode": "REQUIREMENT" if len(source_question) > 12 else "BUSINESS_TERM",
                "query": f"【{sheet}】{query}",
                "answerStatus": "PENDING_PRODUCT_CONFIRMATION" if pending else "ANSWERED",
                "answer": (f"待产品确认：源需求表记录为“{answer}”。" if answer else
                           "待产品确认：源需求表仅记录了存疑问题，未提供产品解答。") if pending else answer,
                "goldDocument": {"workbook": workbook.name, "sheet": sheet, "cell": f"B{row}"},
            })
        if rows:
            queues.append((sheet, rows))
    if len(records) != limit:
        raise RuntimeError(f"Only generated {len(records)} document records; expected {limit}")
    return records


def business_label(class_name: str) -> str:
    words = [word.lower() for word in CAMEL.split(re.sub(r"(?:Moa)?Service(?:Impl)?$", "", class_name)) if word]
    translated = [BUSINESS_WORDS.get(word, word) for word in words]
    return "".join(translated) or class_name


def method_summary(comment: str, method_name: str) -> str:
    text = re.sub(r"^\s*\*+/?\s?", "", comment or "", flags=re.MULTILINE)
    text = re.sub(r"@(?:param|return|throws|see|link)\b.*", "", text)
    text = normalize(text.replace("/**", "").replace("*/", ""))
    if text:
        sentence = re.split(r"[。！？]", text)[0].strip()
        if 3 <= len(sentence) <= 100:
            return sentence
    return f"执行 {method_name} 对应的业务处理"


def code_symbols(repository: Path) -> list[dict]:
    candidates = []
    for path in sorted(repository.rglob("*.java")):
        relative = path.relative_to(repository).as_posix()
        if "/target/" in f"/{relative}/" or "/src/test/" in f"/{relative}/":
            continue
        if not any(part in relative for part in ("/service/", "/moa/", "/plugin/", "/handler/")):
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        class_match = CLASS.search(text)
        if not class_match:
            continue
        class_name = class_match.group(1)
        if class_name.startswith("D") or not class_name.endswith(
                ("Service", "ServiceImpl", "Handler", "Plugin", "PluginCommon")):
            continue
        for match in METHOD.finditer(text):
            method_name = match.group("name")
            if method_name in SKIP_METHODS or method_name == class_name or method_name.startswith(("get", "set", "is")):
                continue
            candidates.append({
                "filePath": relative,
                "className": class_name,
                "symbolName": method_name,
                "business": business_label(class_name),
                "summary": method_summary(match.group("comment") or "", method_name),
            })
    unique = {(item["className"], item["symbolName"]): item for item in candidates}
    result = sorted(unique.values(), key=lambda item: (item["business"], item["filePath"], item["symbolName"]))
    if len(result) < 125:
        raise RuntimeError(f"Only found {len(result)} business methods; expected at least 125")
    return result


def code_records(repository: Path, limit: int = 500) -> list[dict]:
    all_symbols = code_symbols(repository)
    symbol_count = limit // 4
    symbols = [all_symbols[index * len(all_symbols) // symbol_count] for index in range(symbol_count)]
    records = []
    templates = (
        ("BUSINESS_TERM", lambda s: f"“{s['business']}”这个业务名词对应的核心代码中，{s['className']}.{s['symbolName']} 实现在哪里？"),
        ("REQUIREMENT_TO_CODE", lambda s: f"需求中提到“{s['summary']}”，在 {s['className']} 中由哪个方法实现？"),
        ("SYMBOL", lambda s: f"查找 {s['className']}.{s['symbolName']} 的实现位置。"),
        ("BEHAVIOR", lambda s: f"{s['business']}业务需要执行“{s['summary']}”时，应召回 {s['className']} 的哪个代码符号？"),
    )
    for mode, template in templates:
        for symbol in symbols:
            index = len(records) + 1
            records.append({
                "id": f"fengshen-code-{index:03d}",
                "type": "CODE",
                "queryMode": mode,
                "query": template(symbol),
                "answerStatus": "ANSWERED",
                "answer": f"当前代码实现：{symbol['filePath']}#{symbol['symbolName']}（{symbol['className']}）。",
                "goldCode": [{
                    "projectId": "immortal-game-service",
                    "filePath": symbol["filePath"],
                    "className": symbol["className"],
                    "symbolName": symbol["symbolName"],
                    "role": "PRIMARY",
                }],
            })
    if len({item["query"] for item in records}) != limit:
        raise RuntimeError("Code retrieval queries must be unique")
    return records


def write_outputs(records: list[dict], jsonl: Path, markdown: Path, title: str) -> None:
    jsonl.parent.mkdir(parents=True, exist_ok=True)
    jsonl.write_text("".join(json.dumps(item, ensure_ascii=False, separators=(",", ":")) + "\n"
                               for item in records), encoding="utf-8")
    lines = [
        f"# {title}", "",
        "> `待产品确认` 表示原始需求表只有存疑问题、没有产品解答；不得把当前代码行为当作需求答案。", "",
    ]
    for item in records:
        lines.append(f"## {item['id']} [{item['queryMode']}]")
        lines.append("")
        lines.append(f"- 问题：{item['query']}")
        lines.append(f"- 答案状态：{item['answerStatus']}")
        lines.append(f"- 标准答案：{item['answer']}")
        source = item.get("goldDocument") or item["goldCode"][0]
        if item["type"] == "DOCUMENT":
            lines.append(f"- 来源：{source['workbook']} / {source['sheet']} / {source['cell']}")
        else:
            lines.append(f"- Gold：{source['filePath']}#{source['symbolName']}")
        lines.append("")
    markdown.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workbook", type=Path, required=True)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--document-jsonl", type=Path,
                        default=Path("evaluation/fengshen-document-retrieval-eval-200.jsonl"))
    parser.add_argument("--document-markdown", type=Path,
                        default=Path("evaluation/fengshen-document-retrieval-eval-200.md"))
    parser.add_argument("--code-jsonl", type=Path,
                        default=Path("evaluation/fengshen-code-retrieval-eval-500.jsonl"))
    parser.add_argument("--code-markdown", type=Path,
                        default=Path("evaluation/fengshen-code-retrieval-eval-500.md"))
    args = parser.parse_args()
    documents = document_records(args.workbook)
    code = code_records(args.repository)
    records = documents + code
    if len(records) != 700 or len({item["id"] for item in records}) != 700:
        raise RuntimeError("Evaluation records must contain exactly 700 unique IDs")
    write_outputs(documents, args.document_jsonl, args.document_markdown, "封神需求文档召回评估题库（200 题）")
    write_outputs(code, args.code_jsonl, args.code_markdown, "封神代码召回评估题库（500 题）")
    print("Generated 200 document and 500 code retrieval records.")


if __name__ == "__main__":
    main()
