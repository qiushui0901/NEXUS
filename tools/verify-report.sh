#!/usr/bin/env bash
# 生成机器可读的发布验证摘要
#
# 用法：./tools/verify-report.sh
# 输出：docs/verification/<version>-<git-commit>.json + 更新 docs/verification/latest.json
#
# 依赖：JDK 21（Enforcer 要求）、Python 3、bash
set -euo pipefail

WORKDIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$WORKDIR"

if [ -n "$(git status --porcelain=v1 --untracked-files=all)" ]; then
  echo "Refusing to generate a release verification report from a dirty workspace." >&2
  echo "Commit, stash, or remove all staged, tracked, and untracked changes first." >&2
  exit 3
fi

VERSION=$(python3 - "$WORKDIR/pom.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
namespace = root.tag.partition("}")[0].lstrip("{")
prefix = f"{{{namespace}}}" if namespace else ""
version = root.findtext(f"{prefix}version")
if not version or not version.strip():
    raise SystemExit("project.version is missing from pom.xml")
print(version.strip())
PY
)
COMMIT=$(git rev-parse HEAD)
if [ -x /usr/libexec/java_home ]; then
  JDK_HOME="$(/usr/libexec/java_home -v 21)"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JDK_HOME="$JAVA_HOME"
else
  echo "JDK 21 not found. Set JAVA_HOME to a JDK 21 installation." >&2
  exit 2
fi

VERIFY_LOG=$(mktemp)
JAVA_VERSION_FILE=$(mktemp)
TEST_SUMMARY_FILE=$(mktemp)
trap 'rm -f "$VERIFY_LOG" "$JAVA_VERSION_FILE" "$TEST_SUMMARY_FILE"' EXIT

echo "verify with JDK 21 (Enforcer enabled)..."
"$JDK_HOME/bin/java" -version 2>&1 | head -1 > "$JAVA_VERSION_FILE"
set +e
JAVA_HOME="$JDK_HOME" ./mvnw clean verify > "$VERIFY_LOG" 2>&1
VERIFY_RC=$?
set -e

# 聚合 Surefire XML。即使报告损坏，也要保留 Maven 原始退出码并生成失败报告。
python3 - "$TEST_SUMMARY_FILE" <<'PY'
import glob
import json
import sys
import xml.etree.ElementTree as ET

totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
paths = sorted(glob.glob("target/surefire-reports/TEST-*.xml"))
parse_errors = []
for path in paths:
    try:
        root = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(root.attrib.get(key, 0))
    except (ET.ParseError, OSError, TypeError, ValueError) as exception:
        parse_errors.append({
            "file": path,
            "errorType": type(exception).__name__,
        })

status = "PARSED"
if not paths:
    status = "MISSING"
elif parse_errors:
    status = "PARTIAL" if len(parse_errors) < len(paths) else "INVALID"

with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump({
        **totals,
        "reportCount": len(paths),
        "parseStatus": status,
        "parseErrors": parse_errors,
    }, output, ensure_ascii=False)
PY

COVERAGE=""
if grep -q "All coverage checks have been met" "$VERIFY_LOG"; then COVERAGE="met"; fi
JAR_BUILT="no"
if [ -f "target/NEXUS-${VERSION}.jar" ]; then JAR_BUILT="yes"; fi

SUMMARY=$(python3 - \
  "$COMMIT" \
  "$VERSION" \
  "$JAVA_VERSION_FILE" \
  "$TEST_SUMMARY_FILE" \
  "$COVERAGE" \
  "$JAR_BUILT" \
  "$VERIFY_RC" <<'PY'
import datetime
import json
import subprocess
import sys

(
    commit,
    version,
    java_version_file,
    test_summary_file,
    coverage,
    jar_built,
    verify_rc,
) = sys.argv[1:]
exit_code = int(verify_rc)
with open(test_summary_file, encoding="utf-8") as source:
    test_summary = json.load(source)
summary = {
    "schemaVersion": 1,
    "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "git": {
        "commit": commit,
        "branch": subprocess.run(
            ["git", "branch", "--show-current"],
            capture_output=True,
            check=True,
            text=True,
        ).stdout.strip(),
    },
    "version": version,
    "command": "JAVA_HOME=<jdk21> ./mvnw clean verify",
    "enforcerSkipped": False,
    "java": open(java_version_file, encoding="utf-8").read().strip(),
    "tests": {
        "run": test_summary["tests"],
        "failures": test_summary["failures"],
        "errors": test_summary["errors"],
        "skipped": test_summary["skipped"],
        "reportCount": test_summary["reportCount"],
        "parseStatus": test_summary["parseStatus"],
        "parseErrors": test_summary["parseErrors"],
    },
    "jacocoCoverageCheck": coverage,
    "jarBuilt": jar_built,
    "buildResult": "SUCCESS" if exit_code == 0 else "FAILURE",
    "exitCode": exit_code,
}
print(json.dumps(summary, ensure_ascii=False, indent=2))
PY
)

mkdir -p docs/verification
OUT="docs/verification/${VERSION}-${COMMIT:0:10}.json"
echo "$SUMMARY" > "$OUT"
echo "$SUMMARY" > docs/verification/latest.json

echo "---"
echo "verify result: $(echo "$SUMMARY" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["buildResult"], "| tests:", d["tests"]["run"], "| jacoco:", d["jacocoCoverageCheck"], "| jar:", d["jarBuilt"])')"
echo "report: $OUT"

exit $VERIFY_RC
