#!/usr/bin/env bash
# NEXUS Module 闭环端到端验收脚本（0.8.4）
#
# 真实仓库复现：build -> review -> publish -> stale -> rebuild -> claim diff
#
# 前置条件：
#   - 已构建 jar：JAVA_HOME=... ./mvnw -Denforcer.skip=true verify
#   - 符号图快照与仓库 HEAD 一致（生产环境由代码索引保证；本机验收需同步，
#     见 docs/wiki-next-iteration-module-slice.md §11 验收记录）
#   - 本机已有 Qdrant 数据目录 qdrant-storage 与二进制 tools/qdrant
#
# 用法：
#   SHIGUANG_REPO=/path/to/shiguang ./tools/module-loop-verify.sh
#
# 环境变量：
#   SHIGUANG_REPO  目标真实仓库（默认 /Users/user/Documents/qiushui-shiguang）
#   PORT           应用端口（默认 18080）
#   MODULE_PATH    目标模块（默认 repository 包）
#   FEATURE_ID     已发布页面 featureId（默认 module-repository）
#   VERSION        Wiki 版本（默认 v1）
set -euo pipefail

REPO="${SHIGUANG_REPO:-/Users/user/Documents/qiushui-shiguang}"
PORT="${PORT:-18080}"
BASE="http://localhost:${PORT}"
MODULE_PATH="${MODULE_PATH:-shiguang-kv/shiguang-kv-biz/src/main/java/com/quanshiguang/shiguang/kv/biz/domain/repository}"
FEATURE_ID="${FEATURE_ID:-module-repository}"
VERSION="${VERSION:-v1}"
PROJECT_ID="${PROJECT_ID:-shiguang-eval}"
JAR="${JAR:-target/NEXUS-0.8.4-SNAPSHOT.jar}"
WORKDIR="$(cd "$(dirname "$0")/.." && pwd)"

step() { printf '\n\033[1;36m=== %s ===\033[0m\n' "$1"; }
json_get() { python3 -c "import json,sys; d=json.load(sys.stdin); print(d$1)"; }

cd "$WORKDIR"

step "0. 前置检查"
[ -f "$JAR" ] || { echo "jar 不存在，先执行 verify"; exit 1; }
[ -d "$REPO/.git" ] || { echo "仓库不可用: $REPO"; exit 1; }
pgrep -f "tools/qdrant" >/dev/null || { echo "Qdrant 未运行"; exit 1; }
PUBLISHED_COMMIT=$(git -C "$REPO" rev-parse HEAD)
echo "published baseline commit: ${PUBLISHED_COMMIT:0:10}"

step "1. build（质量门四硬约束放行）"
BID=$(curl -s -X POST "$BASE/api/wiki/modules/build?projectId=$PROJECT_ID&version=$VERSION&modulePath=$MODULE_PATH" \
  | json_get "['buildId']")
echo "buildId: $BID"
[ -n "$BID" ] || { echo "build 失败"; exit 1; }

step "2. review（DRAFT -> IN_REVIEW -> APPROVED）"
curl -s -X POST "$BASE/api/knowledge/drafts/$BID/transition?projectId=$PROJECT_ID&version=$VERSION" \
  -H 'Content-Type: application/json' -d '{"targetStatus":"IN_REVIEW","comment":"验收进入审核"}' >/dev/null
curl -s -X POST "$BASE/api/knowledge/drafts/$BID/transition?projectId=$PROJECT_ID&version=$VERSION" \
  -H 'Content-Type: application/json' -d '{"targetStatus":"APPROVED","comment":"验收通过"}' >/dev/null
echo "approved"

step "3. publish"
curl -s -X POST "$BASE/api/knowledge/drafts/$BID/publish?projectId=$PROJECT_ID&version=$VERSION" \
  | json_get "['draft']['status']" | grep -q PUBLISHED || { echo "publish 失败"; exit 1; }
echo "published"

step "4. staleness 基线（应 false：commit 一致）"
curl -s "$BASE/api/wiki/staleness?projectId=$PROJECT_ID&version=$VERSION" \
  | json_get "['stale']" | grep -q false || { echo "基线不应 stale"; exit 1; }
echo "baseline fresh"

step "5. 制造符号变更（修改模块调用方，不碰模块自身文件）"
BRANCH="smoke-verify-$(date +%s)"
git -C "$REPO" checkout -q -b "$BRANCH"
CALLER=$(grep -rl "CommentContentRepository" "$REPO/shiguang-kv/shiguang-kv-biz/src/main/java" \
  --include="*.java" | grep -v "/repository/" | head -1)
[ -n "$CALLER" ] || { echo "未找到调用方文件"; exit 1; }
sed -i '' 's/^import java.util.List;/import java.util.List;\nimport java.util.ArrayList; \/\/ smoke/' "$CALLER"
git -C "$REPO" add -A
git -C "$REPO" commit -q -m "smoke: touch module caller for staleness verification"
NEW_COMMIT=$(git -C "$REPO" rev-parse HEAD)
echo "changed caller: $(basename "$CALLER") @ ${NEW_COMMIT:0:10}"

step "6. 同步符号图快照到新 commit（真实环境由代码索引完成）"
DB="$WORKDIR/data/code-graph/code-graph.db"
cp "$DB" /tmp/code-graph.verify.bak
sqlite3 "$DB" "update code_symbol set commit_sha='$NEW_COMMIT' where project_id='$PROJECT_ID';
update code_relation set commit_sha='$NEW_COMMIT' where project_id='$PROJECT_ID';
update code_graph_snapshot set commit_sha='$NEW_COMMIT' where project_id='$PROJECT_ID';"
echo "symbol graph synced"

step "7. stale（应 true，reason 含传播链）"
curl -s "$BASE/api/wiki/staleness?projectId=$PROJECT_ID&version=$VERSION" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
assert d['stale'] is True, '应标记 stale'
assert any(p['featureId']=='$FEATURE_ID' for p in d['pages']), '缺少目标页面'
reason = next(p['reasons'][0] for p in d['pages'] if p['featureId']=='$FEATURE_ID')
print(reason)
assert '传播' in reason, '应包含传播链'
"

step "8. rebuild（StaleReport -> 新草稿 + claim diff）"
curl -s -X POST "$BASE/api/wiki/modules/rebuild?projectId=$PROJECT_ID&version=$VERSION&modulePath=$MODULE_PATH&featureId=$FEATURE_ID" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('rebuild draft:', d['draft']['buildId'], d['draft']['status'])
changes = [c for c in d['claimChanges'] if c['changeType'] != 'UNCHANGED']
print('changed claims:', len(changes), '/', len(d['claimChanges']))
"
echo "claim-diff 落盘: data/wiki-drafts/$PROJECT_ID/$VERSION/$(ls -t data/wiki-drafts/$PROJECT_ID/$VERSION | head -1)/claim-diff.json"

step "9. 清理（返回主分支，恢复符号图）"
git -C "$REPO" checkout -q main
git -C "$REPO" branch -q -D "$BRANCH"
cp /tmp/code-graph.verify.bak "$DB"
echo "cleaned"

printf '\n\033[1;32m=== Module 闭环端到端验收通过 ===\033[0m\n'
