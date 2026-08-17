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
trap 'rm -f "$VERIFY_LOG" "$JAVA_VERSION_FILE"' EXIT

echo "verify with JDK 21 (Enforcer enabled)..."
"$JDK_HOME/bin/java" -version 2>&1 | head -1 > "$JAVA_VERSION_FILE"
set +e
JAVA_HOME="$JDK_HOME" ./mvnw clean verify > "$VERIFY_LOG" 2>&1
VERIFY_RC=$?
set -e

# 聚合 Surefire XML，避免依赖文本报告的格式。
TEST_SUMMARY=$(python3 - <<'PY'
import glob
import xml.etree.ElementTree as ET

totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
for path in glob.glob("target/surefire-reports/TEST-*.xml"):
    root = ET.parse(path).getroot()
    for key in totals:
        totals[key] += int(root.attrib.get(key, 0))
print(*(totals[key] for key in ("tests", "failures", "errors", "skipped")))
PY
)
read -r TESTS FAILURES ERRORS SKIPPED <<< "$TEST_SUMMARY"

COVERAGE=""
if grep -q "All coverage checks have been met" "$VERIFY_LOG"; then COVERAGE="met"; fi
JAR_BUILT="no"
if [ -f "target/NEXUS-${VERSION}.jar" ]; then JAR_BUILT="yes"; fi

SUMMARY=$(python3 - \
  "$COMMIT" \
  "$VERSION" \
  "$JAVA_VERSION_FILE" \
  "$TESTS" \
  "$FAILURES" \
  "$ERRORS" \
  "$SKIPPED" \
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
    tests,
    failures,
    errors,
    skipped,
    coverage,
    jar_built,
    verify_rc,
) = sys.argv[1:]
exit_code = int(verify_rc)
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
        "run": int(tests),
        "failures": int(failures),
        "errors": int(errors),
        "skipped": int(skipped),
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
