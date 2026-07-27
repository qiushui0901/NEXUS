#!/usr/bin/env python3
"""Build reviewable requirement snapshots from tracked XLSX facts and an optional product ZIP.

The output contains text, hashes and provenance only. It never writes embeddings, Qdrant points,
vector storage, credentials or the original source archive.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
from collections import defaultdict
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZipFile

PROJECT_ID = "immortal-game-service"
DOCUMENT_ID = "fengshen"
XLSX_PATH = Path("data/封神版本问题整理.xlsx")
ZIP_PATH = Path("data/产品文档.zip")
OUTPUT_ROOT = Path("data/requirement-snapshots")

# Only versions backed by an explicit worksheet or product-document folder are mapped.
VERSION_SOURCES = [
    ("1.1", None, ["封神1.1版本"], ["1.1.0", "1.1.1", "1.1.2", "1.1.3", "1.1.4"]),
    ("1.2", "1.1", ["封神1.2版本"], ["1.2.0"]),
    ("1.3", "1.2", ["封神1.3版本"], ["1.3.0", "1.3.1", "1.3.2"]),
    ("2.0", "1.3", ["封神2.0版本"], ["2.0.0", "2.0.1", "2.0.2"]),
    ("2.1", "2.0", ["封神2.1版本"], ["2.1.0"]),
    ("2.2", "2.1", ["封神2.2版本"], ["2.2.0"]),
    ("2.4", "2.2", ["封神2.4版本"], ["2.4.0"]),
    ("2.4.1", "2.4", ["封神2.4.1"], ["2.4.1", "2.4.2", "2.4.3"]),
    ("2.5", "2.4.1", ["封神2.5"], ["2.5.0"]),
    ("2.6", "2.5", ["封神2.6"], ["2.6.0", "2.6.1"]),
    ("2.7", "2.6", ["封神2.7"], ["2.7.0", "2.7.1"]),
    ("3.0", "2.7", ["封神3.0"], ["3.0.0", "3.0.1", "3.0.2"]),
    ("3.2", "3.0", ["封神3.2"], ["3.2.0"]),
    ("3.3", "3.2", ["封神3.3"], ["3.3.0", "3.3.2"]),
    ("3.4", "3.3", ["封神3.4"], ["3.4.0"]),
    ("3.5", "3.4", ["封神3.5"], ["3.5.0", "3.5.2", "3.5.3", "3.5.4", "3.5.5"]),
    ("4.0", "3.5", ["封神4.0"], ["4.0.0", "4.0.0.1", "4.0.1", "4.0.2"]),
    ("4.1", "4.0", ["封神4.1"], ["4.1.0", "4.1.1", "4.1.1.1", "4.1.1.2", "4.1.2", "4.1.3", "4.1.4", "4.1.5", "4.1.6"]),
    ("5.0", "4.1", ["封神5.0", "封神5.0-ai"], ["5.0.0", "5.0.1", "5.0.2"]),
    ("5.1", "5.0", ["封神5.1"], ["5.1"]),
]

MAIN_NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
REL_NS = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
PKG_REL_NS = "{http://schemas.openxmlformats.org/package/2006/relationships}"
SPACE = re.compile(r"\s+")
CELL_REF = re.compile(r"([A-Z]+)")


def digest_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def digest_text(value: str) -> str:
    return digest_bytes(value.encode("utf-8"))


def digest_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalize(value: str) -> str:
    return SPACE.sub(" ", html.unescape(value or "")).strip()


def repaired_zip_name(value: str) -> str:
    try:
        return value.encode("cp437").decode("utf-8")
    except (UnicodeEncodeError, UnicodeDecodeError):
        return value


class VisibleTextParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.hidden = 0
        self.parts: list[str] = []

    def handle_starttag(self, tag: str, attrs) -> None:
        if tag.lower() in {"script", "style", "noscript"}:
            self.hidden += 1
        elif tag.lower() in {"p", "div", "li", "br", "tr", "h1", "h2", "h3", "h4"}:
            self.parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() in {"script", "style", "noscript"} and self.hidden:
            self.hidden -= 1
        elif tag.lower() in {"p", "div", "li", "tr", "h1", "h2", "h3", "h4"}:
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        if not self.hidden:
            self.parts.append(data)

    def text(self) -> str:
        lines = [normalize(line) for line in "".join(self.parts).splitlines()]
        return "\n".join(line for line in lines if line)


def workbook_rows(path: Path) -> dict[str, list[dict[str, str]]]:
    with ZipFile(path) as archive:
        shared: list[str] = []
        if "xl/sharedStrings.xml" in archive.namelist():
            root = ET.fromstring(archive.read("xl/sharedStrings.xml"))
            for item in root.findall(MAIN_NS + "si"):
                shared.append("".join(node.text or "" for node in item.iter(MAIN_NS + "t")))
        workbook = ET.fromstring(archive.read("xl/workbook.xml"))
        relationships = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
        targets = {node.attrib["Id"]: node.attrib["Target"]
                   for node in relationships.findall(PKG_REL_NS + "Relationship")}
        result: dict[str, list[dict[str, str]]] = {}
        sheets_element = workbook.find(MAIN_NS + "sheets")
        for sheet in [] if sheets_element is None else sheets_element:
            name = sheet.attrib["name"]
            target = targets[sheet.attrib[REL_NS + "id"]].lstrip("/")
            target = target if target.startswith("xl/") else "xl/" + target
            xml = ET.fromstring(archive.read(target))
            rows: list[dict[str, str]] = []
            for row in xml.iter(MAIN_NS + "row"):
                values: dict[str, str] = {}
                for cell in row.findall(MAIN_NS + "c"):
                    match = CELL_REF.match(cell.attrib.get("r", ""))
                    if not match:
                        continue
                    kind = cell.attrib.get("t")
                    raw = cell.find(MAIN_NS + "v")
                    inline = cell.find(MAIN_NS + "is")
                    value = ""
                    if kind == "s" and raw is not None:
                        value = shared[int(raw.text or "0")]
                    elif kind == "inlineStr" and inline is not None:
                        value = "".join(node.text or "" for node in inline.iter(MAIN_NS + "t"))
                    elif raw is not None:
                        value = raw.text or ""
                    value = normalize(value)
                    if value:
                        values[match.group(1)] = value
                if values:
                    rows.append(values)
            result[name] = rows
        return result


def spreadsheet_entries(sheets: dict[str, list[dict[str, str]]], names: list[str]) -> tuple[list[dict], list[dict]]:
    entries: list[dict] = []
    sources: list[dict] = []
    occurrences: defaultdict[str, int] = defaultdict(int)
    order = 0
    for sheet_name in names:
        rows = sheets.get(sheet_name, [])
        if not rows:
            continue
        sources.append({"path": str(XLSX_PATH), "location": f"sheet={sheet_name}"})
        current_module = "未分类"
        for row in rows[1:]:
            module = normalize(row.get("A", ""))
            question = normalize(row.get("B", ""))
            answer = normalize(row.get("C", ""))
            if module:
                current_module = module
            if not question and not answer:
                continue
            parts = [f"模块：{current_module}"]
            if question:
                parts.append(f"问题：{question}")
            if answer:
                parts.append(f"产品解答：{answer}")
            text = "\n".join(parts)
            stable = normalize(current_module).lower() + "|" + normalize(question or answer).lower()
            occurrences[stable] += 1
            entry_id = "xlsx-" + digest_text(stable + f"|{occurrences[stable]}")[:24]
            entries.append({
                "entryId": entry_id,
                "filename": f"{XLSX_PATH.name}#{sheet_name}",
                "parentOrder": order,
                "text": text,
                "contentHash": digest_text(text),
            })
            order += 1
    return entries, sources


def zip_entries(path: Path, version: str, start_order: int) -> tuple[list[dict], list[dict]]:
    if version != "5.1" or not path.is_file():
        return [], []
    entries: list[dict] = []
    locations: list[str] = []
    with ZipFile(path) as archive:
        for info in archive.infolist():
            name = repaired_zip_name(info.filename.replace("\\", "/"))
            lower = name.lower()
            basename = Path(name).name.lower()
            if (not name.startswith("5.1/") or not lower.endswith(".html") or "/resources/" in lower
                    or basename == "index.html" or basename.startswith("start") or info.file_size < 800):
                continue
            raw = archive.read(info)
            parser = VisibleTextParser()
            parser.feed(raw.decode("utf-8", errors="replace"))
            text = parser.text()
            if not text:
                continue
            location = name
            locations.append(location)
            stable = Path(name).stem
            entries.append({
                "entryId": "html-" + digest_text(stable)[:24],
                "filename": stable + ".html",
                "parentOrder": start_order + len(entries),
                "text": text,
                "contentHash": digest_text(text),
            })
    source = [{"path": str(path), "location": "zip-folder=5.1"}] if entries else []
    return entries, source


def source_facts(sources: list[dict], xlsx_hash: str, xlsx_bytes: int,
                 zip_hash: str | None, zip_bytes: int | None) -> list[dict]:
    result: list[dict] = []
    for source in sources:
        is_zip = source["path"] == str(ZIP_PATH)
        result.append({
            **source,
            "contentHash": zip_hash if is_zip else xlsx_hash,
            "bytes": zip_bytes if is_zip else xlsx_bytes,
        })
    return result


def main() -> None:
    global XLSX_PATH, ZIP_PATH
    parser = argparse.ArgumentParser()
    parser.add_argument("--xlsx", type=Path, default=XLSX_PATH)
    parser.add_argument("--zip", type=Path, default=ZIP_PATH)
    parser.add_argument("--output", type=Path, default=OUTPUT_ROOT)
    parser.add_argument("--generated-at", help="ISO timestamp; unchanged snapshots preserve their existing value")
    args = parser.parse_args()

    XLSX_PATH, ZIP_PATH = args.xlsx, args.zip
    if not XLSX_PATH.is_file():
        raise SystemExit(f"XLSX source missing: {XLSX_PATH}")
    sheets = workbook_rows(XLSX_PATH)
    xlsx_bytes = XLSX_PATH.stat().st_size
    xlsx_hash = digest_file(XLSX_PATH)
    zip_bytes = ZIP_PATH.stat().st_size if ZIP_PATH.is_file() else None
    zip_hash = digest_file(ZIP_PATH) if ZIP_PATH.is_file() else None
    target = args.output / PROJECT_ID
    target.mkdir(parents=True, exist_ok=True)

    written = 0
    for version, base, sheet_names, aliases in VERSION_SOURCES:
        entries, sources = spreadsheet_entries(sheets, sheet_names)
        extra_entries, extra_sources = zip_entries(ZIP_PATH, version, len(entries))
        entries.extend(extra_entries)
        sources.extend(extra_sources)
        if not entries:
            continue
        output = target / f"{version}.json"
        snapshot = {
            "schemaVersion": 1,
            "projectId": PROJECT_ID,
            "documentId": DOCUMENT_ID,
            "requirementVersion": version,
            "baseRequirementVersion": base,
            "aliases": aliases,
            "generatedAt": args.generated_at or datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "sources": source_facts(sources, xlsx_hash, xlsx_bytes, zip_hash, zip_bytes),
            "entries": entries,
        }
        if args.generated_at is None and output.is_file():
            existing = json.loads(output.read_text(encoding="utf-8"))
            existing_without_time = {key: value for key, value in existing.items() if key != "generatedAt"}
            snapshot_without_time = {key: value for key, value in snapshot.items() if key != "generatedAt"}
            if existing_without_time == snapshot_without_time and existing.get("generatedAt"):
                snapshot["generatedAt"] = existing["generatedAt"]
        output.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        written += 1
        print(f"{version}: {len(entries)} entries -> {output}")
    print(f"wrote {written} requirement snapshots")


if __name__ == "__main__":
    main()
