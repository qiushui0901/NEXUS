#!/usr/bin/env bash
# 生成机器可读的最终验证摘要（0.8.4）
#
# 用法：./tools/verify-report.sh
# 输出：docs/verification/<version>-<git-commit>.json + 更新 docs/verification/latest.json
#
# 依赖：JDK 21（Enforcer 要求）、maven、bash
set -euo pipefail

WORKDIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$WORKDIR"

VERSION=$(sed -n '16p' pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
COMMIT=$(git rev-parse HEAD)
JAVA_HOME="$(/usr/libexec/java_home -v 21)"

echo "verify with JDK 21 (Enforcer enabled)..."
"$JAVA_HOME/bin/java" -version 2>&1 | head -1 > /tmp/nexus-java-version.txt
VERIFY_LOG=$(mktemp)
set +e
JAVA_HOME="$JAVA_HOME" ./mvnw verify > "$VERIFY_LOG" 2>&1
VERIFY_RC=$?
set -e

# 聚合 surefire 报告
TESTS=0; FAILURES=0; ERRORS=0; SKIPPED=0
for report in target/surefire-reports/*.txt; do
  [ -f "$report" ] || continue
  read -r t f e s < <(grep -h "Tests run:" "$report" | sed 's/.*Tests run: \([0-9]*\), Failures: \([0-9]*\), Errors: \([0-9]*\), Skipped: \([0-9]*\).*/\1 \2 \3 \4/')
  TESTS=$((TESTS + ${t:-0})); FAILURES=$((FAILURES + ${f:-0}))
  ERRORS=$((ERRORS + ${e:-0})); SKIPPED=$((SKIPPED + ${s:-0}))
done

COVERAGE=""
if grep -q "All coverage checks have been met" "$VERIFY_LOG"; then COVERAGE="met"; fi
JAR_BUILT="no"
if [ -f "target/NEXUS-${VERSION}.jar" ]; then JAR_BUILT="yes"; fi

SUMMARY=$(python3 - <<EOF
import json, subprocess, datetime
summary = {
    "schemaVersion": 1,
    "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "git": {"commit": "$COMMIT", "branch": subprocess.run(["git", "branch", "--show-current"], capture_output=True, text=True).stdout.strip()},
    "version": "$VERSION",
    "command": "JAVA_HOME=<jdk21> ./mvnw verify",
    "enforcerSkipped": False,
    "java": open("/tmp/nexus-java-version.txt").read().strip(),
    "tests": {"run": $TESTS, "failures": $FAILURES, "errors": $ERRORS, "skipped": $SKIPPED},
    "jacocoCoverageCheck": "$COVERAGE",
    "jarBuilt": "$JAR_BUILT",
    "buildResult": "SUCCESS" if $VERIFY_RC == 0 else "FAILURE",
    "exitCode": $VERIFY_RC
}
print(json.dumps(summary, ensure_ascii=False, indent=2))
EOF
)

mkdir -p docs/verification
OUT="docs/verification/${VERSION}-${COMMIT:0:10}.json"
echo "$SUMMARY" > "$OUT"
echo "$SUMMARY" > docs/verification/latest.json

echo "---"
echo "verify result: $(echo "$SUMMARY" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["buildResult"], "| tests:", d["tests"]["run"], "| jacoco:", d["jacocoCoverageCheck"], "| jar:", d["jarBuilt"])')"
echo "report: $OUT"

exit $VERIFY_RC
