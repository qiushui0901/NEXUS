# Journal - qiushui (Part 1)

> AI development session journal
> Started: 2026-07-14

---



## Session 1: 统一检索与版本知识草稿

**Date**: 2026-07-24
**Task**: 统一检索与版本知识草稿
**Branch**: `main`

### Summary

实现统一 RetrievalPipeline、版本增量知识草稿构建 API、NEXUS 0.2.0 版本记录及完整安全回归测试。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `fc5566c` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 2: 完成版本档案与多来源差异分析

**Date**: 2026-07-24
**Task**: 完成版本档案与多来源差异分析
**Branch**: `main`

### Summary

实现安全版本档案、需求代码测试Wiki四类差异、版本API及回归测试；Java 21 verify共98个测试通过。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `d91c3d8` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 3: 完成版本中心与差异浏览页面

**Date**: 2026-07-24
**Task**: 完成版本中心与差异浏览页面
**Branch**: `main`

### Summary

完成 0.4.0-SNAPSHOT 版本中心页面、需求/代码/测试/Wiki 四类差异浏览、Wiki 深链接、页面契约测试和安全降级展示；使用 Java 21 验证 100 条测试通过，并完成任务归档。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `899a33f` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 4: 版本 Wiki 实质内容与直接对比

**Date**: 2026-07-26
**Task**: 版本 Wiki 实质内容与直接对比
**Branch**: `main`

### Summary

版本中心改为直接读取 Wiki 版本；基于 immortal-game-service 的 64 个 Git 版本重建 200 个实质页面，补充代码边界、模块、符号和证据，并保持需求与测试缺失时明确降级。新增可重复构建工具、Wiki 无档案对比回退、页面与服务测试，Java 21 全量 101 项测试通过。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `2482396` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 5: 补齐需求版本链

**Date**: 2026-07-27
**Task**: 补齐需求版本链
**Branch**: `main`

### Summary

新增受控需求快照、版本档案解析与需求差异回退链路，整理 20 个可靠需求基线并完善测试、文档和版本记录。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `24445e2` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 6: 修正需求增量继承语义

**Date**: 2026-07-27
**Task**: 修正需求增量继承语义
**Branch**: `main`

### Summary

需求快照按基线链累计合成，未重复出现的历史需求继续有效，仅结构化 REMOVE 产生删除差异，并补充完整回归测试。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `6f4fc23` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 7: Unified knowledge conflict detection

**Date**: 2026-07-27
**Task**: Unified knowledge conflict detection
**Branch**: `main`

### Summary

Added deterministic project/version-scoped conflict analysis for requirement, code, test, and derived Wiki claims; integrated conflict reports into non-streaming development-plan responses; documented contracts and passed 133 tests.

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `75924ad` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 8: Release NEXUS 0.5

**Date**: 2026-07-28
**Task**: Release NEXUS 0.5
**Branch**: `main`

### Summary

Completed the NEXUS 0.5 release: unified requirement review retrieval, request-scoped evidence citations, reviewable Wiki draft lifecycle with atomic publish/rollback, fail-safe authentication, bounded BGE timeouts, visible degradation logging, repository hygiene, and full Java 21 verification.

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `8b9d8cb` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 9: NEXUS 0.6 MCP Server

**Date**: 2026-07-28
**Task**: NEXUS 0.6 MCP Server
**Branch**: `main`

### Summary

Implemented and verified the Spring AI Streamable HTTP MCP facade with six evidence-bound tools, shared REST/MCP authorization, bounded responses, Codex and Cursor project configs, stdio bridge, container delivery files, Inspector/client smoke tests, and source symlink escape protection.

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `0c00e95` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 10: NEXUS 0.7 多语言代码智能收口

**Date**: 2026-07-28
**Task**: NEXUS 0.7 多语言代码智能收口
**Branch**: `main`

### Summary

完成 Java/Go/Python/TypeScript Tree-sitter 索引、SQLite 符号图、调用与 commit 影响分析、REST/MCP 接口、Wiki Resource Template、权限与降级边界；补齐隔离、事务回滚、删除/重命名和客户端协议测试。JDK 21 verify 181 项通过；Codex 调用通过，Cursor 完成 MCP 初始化但工具调用受团队用量上限阻断。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `97cbf42` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete

## Session 11: NEXUS 0.6 六工具契约矩阵收口

**Date**: 2026-07-29
**Task**: NEXUS 0.6 MCP per-tool contract matrix
**Branch**: `main`

### Summary

补齐 6 个 NEXUS 0.6 MCP 工具的 6 × 4 契约矩阵，覆盖入参校验、认证/角色/项目白名单、预期依赖降级和数量/文本/总响应截断；增加逐工具单字段静默截断回归，并保持权限集中、窄异常降级和安全 warning 语义。

### Main Changes

- 新增 `NexusMcpV06ContractTest`，52 项测试覆盖六工具完整矩阵及错误不降级、安全信息不泄漏。
- 强化 `McpResponsePolicy` 和六工具映射的截断传播，并补充策略 helper 的 null、边界、数量和文本单测。
- 稳定降级 warning code 为 `NEXUS_*_UNAVAILABLE`，且不回显异常消息、绝对路径、凭据或私有端点。
- 更新 0.6 路线图验收项和任务验证记录；未修改 0.7/0.8 验收历史。

### Testing

- MCP 定向回归通过：契约矩阵 52 项、原工具测试 4 项、策略测试 6 项、HTTP 集成测试 1 项。
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q clean verify`：258 项通过，0 failures/errors/skipped。
- `git diff --check`：通过。

### Status

[OK] **Implementation and quality check complete; awaiting explicit commit/archive instruction**

### Next Steps

- 用户确认后再提交或归档；本轮未自动 commit、push 或 archive。


## Session 11: Commit and archive 0.8 + MCP contract

**Date**: 2026-07-30
**Task**: Commit and archive 0.8 + MCP contract
**Branch**: `main`

### Summary

Committed MCP 0.6 contract matrix, 0.8 BGE/shiguang eval tooling, smoke/ecosystem docs, and Dockerfile 0.8 jar align; archived 07-28-nexus-0-8-quality-performance and 07-29-nexus-0-6-mcp-contract-matrix.

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `b9489ab` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 12: 完成 0.8.2 可信文档召回与 parent 代表项优化

**Date**: 2026-08-03
**Task**: 完成 0.8.2 可信文档召回与 parent 代表项优化
**Branch**: `main`

### Summary

扩展 document-v2-v2 至 18 个文档与 24 个结构化用例，新增分层召回评测；优化 child-first parent 代表项选择，使 Child Recall@10 从 0.916667 提升到 1.0，并通过 289 项 Java 测试和完整真实 calibration。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `fe171e2` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 13: Wiki Module 纵向闭环冒烟验证（0.8.4）

**Date**: 2026-08-07
**Task**: Wiki Module 纵向闭环冒烟验证（0.8.4）
**Branch**: `main`

### Summary

真实仓库验证 Module build/发布/重建/stale 闭环：Claim 引用类型精确、下标有效；修复 build.json 缺口

### Main Changes

# 冒烟验证结果：Module 页面纵向闭环（0.8.4）

- 日期：2026-08-07
- 环境：本机 `NEXUS-0.8.4-SNAPSHOT.jar`（local profile，端口 18080）+ shiguang 真实仓库
- 项目配置：`PROJECT_1_ID=shiguang-eval`、`PROJECT_1_REPO_PATH=/Users/user/Documents/qiushui-shiguang`（HEAD `d29f325`，与 SQLite 符号图快照 commit 一致）

## 验证链路

### 1. Module build API（POST /api/wiki/modules/build）

- `modulePath=.../domain/repository`：featureId=`module-repository`，10 条证据（CODE×4 / DEPENDENCY×2 / DATA×4），6 条 Claim。
  全部 evidenceIds 下标有效；`repository-dependencies`(INFERRED) 引用 2 条 DEPENDENCY 类型证据 ✓
- `modulePath=.../controller`：featureId=`module-controller`，24 条证据（CODE×8 / CODE_GRAPH×6 / ROUTE×10），6 条 Claim：
  - `controller-entry` **FULL** 引用 10 条 **ROUTE** 类型证据 ✓（类型精确匹配）
  - `controller-flow` PARTIAL 引用 6 条 **CODE_GRAPH** ✓
  - `controller-responsibility` PARTIAL 引用 8 条 **CODE** ✓

### 2. 发布链路（草稿 → 审核 → 发布）

- 冒烟发现并修复：模块草稿缺 `build.json` → `publish` 报 404「知识草稿构建产物不存在」。
  修复：两个构建服务补齐兼容 `BuildArtifact`（commit `9b051eb`）。
- 修复后：`transition DRAFT→IN_REVIEW→APPROVED` → `publish` → **PUBLISHED，1 页** ✓

### 3. rebuild（POST /api/wiki/modules/rebuild）

- 对已发布 `module-repository` 重建：6 条 Claim 全部 UNCHANGED（源码未变，行为正确）✓
- 生成新草稿并落盘 `claim-diff.json` ✓

### 4. staleness（GET /api/wiki/staleness）

- published commit `d29f32589c` == 当前 HEAD `d29f32589c` → `stale: false` ✓

## 结论

- 各类型 Claim 引用正确类型、有效下标的 Evidence（不只是单元测试）✓
- Module 草稿 → 审核 → 发布 → 重建 → 失效检测闭环在真实仓库上走通 ✓
- 产出 1 个集成修复（build.json）并入 0.8.4（Fixed 段）
- 回归：327 单元测试全绿

## 已知边界（后续迭代）

- 符号级 stale 传播尚未真实验收：本仓 HEAD 与发布 commit 一致，未触发 diff 命中路径；需在下一迭代用真实 git 变更验证「符号 → 调用关系 → Claim → 页面」的失效传播
- rebuild 的 MODIFIED/REMOVED 差异仅在源码实际变化时出现，本次验证为 UNCHANGED 基线


### Git Commits

(No commits - planning session)

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 14: 符号级 stale 传播真实场景验收（0.8.4）

**Date**: 2026-08-07
**Task**: 符号级 stale 传播真实场景验收（0.8.4）
**Branch**: `main`

### Summary

shiguang 真实仓库：修改调用方触发 module-repository 经调用边传播 STALE；修复 codeSymbols 传播基准

### Main Changes

# 符号级 stale 传播真实场景验收（0.8.4）

- 日期：2026-08-07
- 实现：`WikiStalenessService` 传播链路升级为「Git diff → 变更文件 → 变更符号（symbolsByFiles）→ 入向调用关系（relations）→ 页面 codeSymbols → STALE」，原因中携带「变更符号 -> 页面符号」传播链
- 测试：`WikiStalenessServiceTest` 新增调用关系传播用例（4/4 绿）；全量 327 测试回归绿

## 真实仓库验收（shiguang）

场景设计：**修改模块的调用方**（而非模块自身文件），验证传播而非文件命中。

1. shiguang 仓库 `smoke-symbol-stale` 临时分支提交 `64c428f`：改动 `CommentContentServiceImpl.java`（CommentContentRepository 的调用方）一个 import 行
2. 符号图快照同步到 `64c428f`（模拟代码索引完成后的状态；真实流程由 code index 完成）
3. `GET /api/wiki/staleness?projectId=shiguang-eval&version=v1` 结果：

```
stale: true
pages: [module-repository]
  reason: 代码提交从 d29f325... 变化到 64c428f...，
    符号 CommentContentServiceImpl.batchFindCommentContent ->
    CommentContentRepository.findByPrimaryKeyNoteIdAndPrimaryKeyYearMonthInAndPrimaryKeyContentIdIn
    经调用关系传播影响页面声明
```

- 变更文件是 service 包（页面入口文件未命中）→ 纯符号/调用关系传播 ✓
- 传播链展示调用方符号与页面符号 ✓
- 验收后已清理：shiguang 回到 main（d29f325）、临时分支删除、符号图恢复备份、应用/Qdrant 停止

## 验收暴露并修复的真实缺口

- Module 草稿缺 build.json → publish 404（已在上一轮修复 `9b051eb`）
- 符号传播原基于 `codeEntries`（仅含 entryPoint，repository 类模块为空）→ 改为基于 `codeSymbols` 后传播生效
- 符号图 `code_graph_snapshot` 表与 `code_symbol/code_relation` 必须同步更新，`latestCommit` 才能反映新快照（真实流程由索引保证）


### Git Commits

(No commits - planning session)

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 15: Module 质量门四硬约束与完整闭环真实验收（0.8.4）

**Date**: 2026-08-10
**Task**: Module 质量门四硬约束与完整闭环真实验收（0.8.4）
**Branch**: `main`

### Summary

shiguang 真实仓库走通 build→review→publish→stale→rebuild；MVP checklist 15 项全部勾选，Module 闭环判定完成

### Main Changes

# Module 质量门四硬约束 + 完整闭环真实验收（0.8.4）

- 日期：2026-08-10
- 实现：`ModuleClaimQualityGate` 四条硬约束
- 测试：新增 4 个约束回归用例（真实 CODE 证据 / 跨 commit / 文件缺失与行号越界 / CONFLICT 拦截），全量 330 测试绿

## 四条硬约束

1. **真实 CODE Evidence**：MODULE 页必须含至少一条 `type=CODE` 证据（DEPENDENCY/DATA/CONFIG/DIAGNOSTIC 等派生事实不算）
2. **commit 一致性**：全部证据 commit 必须等于目标代码提交（非空时逐条比对，跨 commit 拦截）
3. **文件/行号有效性**：代码类证据（CODE/CODE_GRAPH/ROUTE/TEST_SYMBOL）文件必须存在于仓库根内、行号不越界；仓库不可核验时 fail-closed
4. **CONFLICT 拦截**：任何 CONFLICT 声明阻止发布

## 完整闭环真实验收（shiguang-eval / repository 模块）

```
build（四硬约束放行，DRAFT）
  → IN_REVIEW → APPROVED（review）
  → publish（PUBLISHED，1 页）
  → staleness（基线 false：commit 一致）
  → 修改调用方（CommentContentServiceImpl，import-only）
  → staleness（true：符号传播链 batchFindCommentContent ->
    findByPrimaryKeyNoteId... 标记 module-repository）
  → rebuild（新草稿 + claim-diff.json）
```

- 完整链路真实走通 ✓
- rebuild 后 Claims 全 UNCHANGED 为**正确行为**：import-only 变更不改变模块事实；MODIFIED/ADDED/REMOVED 差异生成由单元测试覆盖（`ModuleStaleRebuildServiceTest`）
- 发布链路中质量门四硬约束在 `WikiGenerationService.generate` 强制生效（publish 必经）

## 验收环境

- shiguang 真实仓库（符号图快照 commit 同步到 HEAD；真实环境由代码索引完成同步）
- 验收后已清理：临时分支删除、符号图恢复备份、应用/Qdrant 停止、冒烟产物移除

## 结论

Module 纵向闭环 MVP 15 项验收全部通过（docs/wiki-next-iteration-module-slice.md §11 已勾选并记录），**Module 闭环视为完成**。Overview/API/Data 页面扩展暂不启动（按文档 §12 后续进行）。


### Git Commits

(No commits - planning session)

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 16: 质量门审查整改：封堵绕过 + rebuild 守卫（0.8.4）

**Date**: 2026-08-10
**Task**: 质量门审查整改：封堵绕过 + rebuild 守卫（0.8.4）
**Branch**: `main`

### Summary

commit fail-closed、符号存在性、ID 前缀-类型一致性、rebuild 的 StaleReport/MODULE 守卫；13 个门禁测试，335 全绿

### Main Changes

# 质量门审查整改：封堵绕过 + rebuild 守卫（0.8.4）

- 日期：2026-08-10
- 依据：对 Module 闭环的代码审查意见（跨 commit 可绕过、符号未核验、ID 前缀未校验、rebuild 无守卫）

## 逐条核对与整改

| 审查意见 | 结论 | 处理 |
|---|---|---|
| commit 未真正校验，跨 commit 可发布 | 上一轮已加比对，但存在空值绕过（commit 为空时跳过） | **fail-closed**：目标提交或任一代码证据 commit 缺失即拦截（`d761df9`） |
| 文件/路径/行号未校验 | 上一轮已校验（文件存在、路径不越根、行号不越界） | 保留 + 新增**符号存在性**：CODE 证据的符号必须仍在符号图快照 |
| 只有配置/诊断证据也能通过 | 上一轮已改为必须含 type=CODE | 保留，回归测试在 |
| CONFLICT 未阻止发布 | 上一轮已拦截 | 保留，回归测试在 |
| Evidence ID 下标关联未验证类型前缀 | **真实缺口** | 新增 namespace↔type 映射校验（code↔CODE 等 8 组），不匹配即拦截 |
| INFERRED 无置信度字段 | 文档要求但未实现 | 记录为 MVP 已知缺口 |
| rebuild 未确认目标来自 StaleReport、未校验 MODULE | **真实缺口** | rebuild 强制：目标必须是 MODULE 页 + 出现在当前 StaleReport；staleness 基础设施不可用则拒绝 |
| checklist 未勾选 | 上一轮已勾选 15 项 | 保留并记录验收记录 |
| 测试以 mock 为主，未跑真实流程 | 已用 shiguang 真实验收（build→review→publish→stale→rebuild） | 补充说明：符号图快照同步受网关限制，真实索引流程未覆盖 |
| 需求/CI 测试/RPC/消息/缓存事实不完整 | 同意 | 记录为 MVP 已知缺口（文档 §11 新增小节） |

## 新增回归测试（13 个模块门禁测试，全量 335 绿）

- 目标提交缺失 / 证据 commit 缺失 → 拦截
- 证据符号不在符号图 → 拦截
- evidenceId namespace 与类型不匹配（route 前缀引用 CODE 证据）→ 拦截
- rebuild 非 MODULE 页面 → 拒绝
- rebuild 目标不在 StaleReport → 拒绝
- 既有：真实 CODE 证据、跨 commit、文件缺失、行号越界、CONFLICT、FULL 无证据、越界引用、跨项目/版本


### Git Commits

(No commits - planning session)

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 17: Module 闭环最终验证与验收产物固化（0.8.4）

**Date**: 2026-08-10
**Task**: Module 闭环最终验证与验收产物固化（0.8.4）
**Branch**: `main`

### Summary

verify 335 绿 + JaCoCo 通过；验收脚本 tools/module-loop-verify.sh 入库；CHANGELOG/提交一致

### Main Changes

# 最终质量验证与验收产物固化（0.8.4）

- 日期：2026-08-10

## 最终验证（最新提交状态）

```
JAVA_HOME=... ./mvnw -Denforcer.skip=true verify
```

- **Tests run: 335, Failures: 0, Errors: 0, Skipped: 0**（含质量门 13 个模块门禁测试）
- JaCoCo coverage-check：`All coverage checks have been met`（343 类）
- jar：`target/NEXUS-0.8.4-SNAPSHOT.jar` 打包成功
- IT（RetrievalEvaluation*IT / HttpBgeRerankerLiveIT）由环境变量门控，无 failsafe 插件，默认不执行
- **BUILD SUCCESS**，26.3s

## CHANGELOG 与提交一致性

- 工作区干净，全部提交已推送
- 0.8.4 节 13 条条目 ↔ 功能提交逐一对应（module 闭环、schema 增强、质量门两轮、build.json 修复、符号传播、验收脚本）
- 最近提交链：`03c6d73`（验收脚本）→ `d761df9`（质量门加固）→ `1e75981`（四硬约束）→ ...

## 可复现验收产物

- 脚本：`tools/module-loop-verify.sh`（已入库，可执行）
  - 复现完整真实闭环：build → review → publish → staleness 基线 → 修改调用方 → 符号图同步 → stale 传播 → rebuild → claim diff → 清理
  - 每次运行自带断言（基线 fresh、stale 含传播链、rebuild 出草稿），失败即退出
- 真实验收执行记录（历次，shiguang-eval / repository 模块）：
  - build：module-repository 10 证据（CODE×4/DEPENDENCY×2/DATA×4）；module-controller 24 证据（CODE×8/CODE_GRAPH×6/ROUTE×10），FULL entry → 10 ROUTE
  - publish：PUBLISHED（质量门四硬约束放行）
  - stale：修改 CommentContentServiceImpl 后 `module-repository` 经调用边传播标记 STALE
  - rebuild：新草稿 + claim-diff.json（import-only 变更下全 UNCHANGED，正确）
- 已知限制（已记录文档 §11）：符号图快照同步由脚本完成（真实环境由代码索引生成）；需求/CI 测试/RPC 消息缓存事实为 MVP 已知缺口

## 下一步决策点

Module 闭环已作为稳定模板收尾。后续二选一（待用户决定）：
1. 接入需求证据（ModuleFactBundle.requirements ← 需求检索）
2. 扩展 Overview/API/Data 页面


### Git Commits

(No commits - planning session)

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 18: 0.8.5 交付项 1：评测基线与统一检索管线（Phase 0-1）

**Date**: 2026-08-10
**Task**: 0.8.5 交付项 1：评测基线与统一检索管线（Phase 0-1）
**Branch**: `main`

### Summary

QualityGate 全 profile + NO_RESULTS 门禁；336 测试 verify 全绿；0.8.5-SNAPSHOT 版本化

### Main Changes

# 0.8.5 交付项 1：评测基线与统一检索管线（Phase 0-1）

- 日期：2026-08-10

## 完成内容

1. **版本升级**：0.8.5-SNAPSHOT（pom/yml/Dockerfile/README/verify-report），CHANGELOG 新建 0.8.5 节
2. **检索质量门禁扩展**（`RetrievalQualityGateTest`，随 CI 默认执行）：
   - 全 profile HIT 回归：48 条冻结用例（REQUIREMENT_REVIEW 12 / DEVELOPMENT_PLAN 30 / WIKI_BUILD 12）断言**文档黄金 + 代码黄金**命中都不被确定性逻辑（去重/代表选择/截断）误杀
   - 严格 NO_RESULTS 断言：6 条无答案用例在空语料下必须返回 `RagOutcomeStatus.NO_RESULTS`（路线图"真正零命中返回 NO_RESULTS，不虚构命中"）
   - 测试按用例自身 profile 驱动管线（此前固定 REQUIREMENT_REVIEW 只覆盖 6 条）

## 现状核对（路线图 Phase 0/1 对照）

| 路线图要求 | 现状 |
|---|---|
| 评测集 ≥50 条 | 54 条（shiguang-v1，含 NO_RESULTS×6、version-leakage、cross-project、dependency-degradation tags）✓ |
| 同一管线多 profile | RetrievalPipeline + 3 profiles；服务层全部走 pipeline ✓ |
| 入口不复制检索逻辑 | DevelopmentPlan/Stream/DoubtReview/agentic 均经 pipeline ✓ |
| 评测 JSON 报告 | RetrievalEvaluationIT → report.json + md（env 门控）✓ |
| 缓存隔离/配置指纹 | 0.8.1 已实现 ✓ |
| 数据/代码/配置/模型指纹 | tools/retrieval-eval-comparison.py 已实现 ✓ |

## 验证

`JAVA_HOME=.../ms-17.0.20 ./mvnw verify`：**336 测试全绿，BUILD SUCCESS**（含新 NO_RESULTS 门禁）

## 下一步（交付项 2：错误/降级/超时治理 Phase 2）

现状快照：
- MCP：依赖失败 → DEGRADED + warning ✓（McpToolInvocationService）
- SSE：warning 事件已发（DevelopmentPlanStreamService）；error 事件与核心失败结束语义待确认
- REST：NO_RESULTS/DEGRADED 尚未结构化暴露（ReviewFacade 返回结果+warning，无 status 字段）；依赖失败走异常 → 5xx
- 待做：REST 响应 status 契约、SSE error 事件、静默 catch 清理、warning code 定稿


### Git Commits

(No commits - planning session)

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 19: 0.8.5 交付项 2-6：状态契约、证据闭环、AST shadow、大文档覆盖、质量门收口

**Date**: 2026-08-10
**Task**: 0.8.5 交付项 2-6：状态契约、证据闭环、AST shadow、大文档覆盖、质量门收口
**Branch**: `main`

### Summary

Phase 2-6 全部落地：341 测试 verify 全绿；状态契约注册表 + CONTEXT_TRUNCATED；Module 需求证据接入；Java AST shadow 差异报告；模块轮转上下文切片

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

(No commits - planning session)

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 20: 建立真实 RAG 企业评测基线

**Date**: 2026-08-17
**Task**: 建立真实 RAG 企业评测基线
**Branch**: `main`

### Summary

完成 v2 企业评测数据契约、24 条冻结数据集、Recall/MRR/nDCG/无结果准确率/降级率/P95 质量门、真实评测脚本与中文执行文档；全量 Maven verify 442 项测试通过并归档子任务。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `521beaf` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 21: GitLab 项目自动接入

**Date**: 2026-08-17
**Task**: GitLab 项目自动接入
**Branch**: `main`

### Summary

完成 GitLab 私有项目自动接入、同步、Webhook 和增量索引能力

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `b842338` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 22: RAGFlow 知识库与 GitLab 工作台 Review 收口

**Date**: 2026-08-17
**Task**: RAGFlow 知识库与 GitLab 工作台 Review 收口
**Branch**: `main`

### Summary

完成知识管理与 GitLab 可视化工作台，修复发布快照旧状态残留、停用项目同步任务未终结和 Qdrant 批次排除统计错误；494 项测试及覆盖率门禁通过。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `caae1e3` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 23: 发布 NEXUS v0.9.1 GitLab 账号导入与统一前端

**Date**: 2026-08-18
**Task**: 发布 NEXUS v0.9.1 GitLab 账号导入与统一前端
**Branch**: `main`

### Summary

完成 GitLab 多账号 PAT 连接、成员项目发现与批量导入，修复稳定远端身份、并发注册、凭据 Host 绑定、临时故障状态和服务端搜索问题；合并统一浅色前端、知识检索指标与运行状态监控，JDK 21 全量质量门通过。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `a32af87` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 24: 业务项目多仓库与语义检索收口

**Date**: 2026-08-26
**Task**: 业务项目多仓库与语义检索收口
**Branch**: `main`

### Summary

完成业务项目多仓库任务的最终代码审查与语义检索加固：修复业务项目 ID、并发构建写入、检索代际传播、响应快照评测资格和异步前端状态一致性；定向 Java 测试、JS 语法检查与 diff 检查通过；归档 08-18-business-project-multi-repository，未完成的扩展项保留在归档计划中供后续拆分。

### Main Changes

- Detailed change bullets were not supplied; see the summary above.

### Git Commits

| Hash | Message |
|------|---------|
| `1b2f6e3` | (see git log) |
| `3fbd070` | (see git log) |
| `e1dc574` | (see git log) |

### Testing

- Validation was not recorded for this session.

### Status

[OK] **Completed**

### Next Steps

- None - task complete

## Session 26: 实体中心检索 Phase 1——跨版本实体基础

**Date**: 2026-08-27
**Task**: 08-27-ec-phase1-entity-base（父：08-27-entity-centric-knowledge-retrieval）
**Branch**: `main`

### Summary

按 `docs/entity-centric-knowledge-retrieval-implementation.md` 开发实体中心检索首个批次：把 `BusinessConcept` 收敛为跨版本、跨来源稳定实体。canonicalKey 去除 `param:/req:/test:/obs:/doubt:` 前缀统一为 `<module>.<subject>`；新增 `BusinessConceptService.buildProject(projectId)`（枚举全部业务版本 + 当前代码，跨版本合并实体、保留历史成员、代码只挂最新版本）；补 5 个实体/别名/成员/Claim 索引；修复 `upsertVersionContext` 在 repository/commit 为 null（无代码索引）时重复 resolve 撞主键的 bug；`RequirementCodeDriftService` 改用共享 `AlignmentNaming.conceptKey`。

### Main Changes

- `AlignmentNaming`：新增 `conceptKey(module, subject)`（跨来源/版本稳定实体键）、`factKeyModule(factKey)` 共享 helper。
- `BusinessConceptService`：`conceptFor` 去来源前缀；新增 `buildProject`（数值感知版本排序后逐版本重建、代码挂最新版本）；`buildForVersion` 抽取共享重建逻辑。
- `MultiSourceKnowledgeStore`：新增 `findBusinessVersions(projectId)`（VERSION_ORDER 数值感知排序，5.9 < 5.10）；补 `idx_claim_fact_subject`、`idx_document_version_business_status`。
- `CodeCentricAlignmentStore`：补 `idx_concept_alias_lookup`、`idx_concept_member_entity_version`、`idx_concept_member_claim`；`upsertVersionContext` 冲突目标改 `context_id`（修复 NULL unique 语义）。
- `CodeCentricModels`：`LoadedCode.empty()`。
- `RequirementCodeDriftService`：需求概念查找改用 `AlignmentNaming.conceptKey`。
- 测试：BusinessConceptServiceTest 新增 5 例（跨版本同实体、参数/需求/测试成员同实体、历史成员保留、同 subject 不同 module 不合并、版本数值排序）。

### Testing

- `./mvnw test`：1020 tests，0 failures。

### Status

[OK] **Phase 1 complete；下一步 Phase 2（LLM 实体提取）或按最小闭环优先 Phase 3（entity-search API）**

## Session 27: 实体中心检索 Phase 2——LLM 实体提取与归一化

**Date**: 2026-08-27
**Task**: 08-27-ec-phase2-llm-extraction（父：08-27-entity-centric-knowledge-retrieval）
**Branch**: `main`

### Summary

新增 `knowledge/multisource/entity` 包：问题实体提取器（规则优先 + LLM 受限补召回）、实体解析器（§7.2 解析链 1-8 + NEEDS_REVIEW）、来源级提取器（LLM 只提议别名/关系，不写知识事实）、提取校验器（预算/claim 归属/关系白名单/受限选择）、LLM 辅助客户端（任何失败回退规则）。`business_concept_alias` 新增 origin/status/evidence_id 列；PROPOSED 别名不参与确认匹配。

### Main Changes

- `QuestionEntityAnalyzer`：规则命中已确认别名→mentions；意图/版本/标志规则推导；LLM 提议名 resolve-or-drop（可解析才成为 LLM_SELECTED mention）。
- `EntityResolverService`：别名命中 → 成员名/代码符号（`findConceptIdsByMemberName`）→ 单候选命中 → 多候选 LLM 受限选择 → NEEDS_REVIEW；无命中 ENTITY_UNRESOLVED。
- `SourceEntityExtractor`：LLM 提议别名 PROPOSED + 关系 PROPOSED（matchMethod=LLM_PROPOSED、evidence 取自源端），不写 facts。
- `EntityExtractionValidator`：结构化校验（预算/claim 输入批次归属/关系白名单/受限选择）；法学原则：校验层不硬拒未知名字（resolve-or-drop 交给 analyzer，防伪造 ID 机制在解析层）。
- `EntityLlmAssistant`：ChatClient.entity() 结构化输出 + 稳定错误码；失败返回 empty。
- `CodeCentricAlignmentStore`：alias 表加 origin/status/evidence_id（addColumnIfMissing）；新增 `findConfirmedAliasesMentionedIn`（instr+limit）、`findConceptIdsByAlias`（CONFIRMED only）、`findConceptIdsByMemberName`。
- `AlignmentTestSupport` 提升为 public 供 entity 包测试复用；新增共享 `seedParameter`。

### 踩坑记录

- **SQLite UNIQUE 对 NULL 互不相等**：`on conflict(unique_tuple)` 在 repository/commit 为 null 时永不命中 → 重复 resolve 撞 PK。修复：冲突目标用确定性的 `context_id`（Phase 1 批次已修，本轮回归确认）。
- **ImmutableCollections$SetN.contains(null) 抛 NPE**：白名单判断必须先判 null 再 contains。
- **resolver instr 方向**：`instr(?, display_name)>0` 是“查询文本包含成员名”——测试构造必须让查询包含成员名本身。
- **LLM 成员挂靠约束**：代码符号只有匹配到别名才成为成员，因此成员名可能与其别名存在包含关系，测试用直插 upsertMember 构造纯成员场景。

### Testing

- `./mvnw test`：1043 tests，0 failures（+23 Phase 2）。

### Status

[OK] **Phase 2 complete；下一步 Phase 3（EntityQueryService + entity-search API，最小闭环）**

## Session 28: 实体中心检索 Phase 3——实体证据查询 API（最小闭环达成）

**Date**: 2026-08-27
**Task**: 08-27-ec-phase3-entity-query（父：08-27-entity-centric-knowledge-retrieval）
**Branch**: `main`

### Summary

实现 dev md §16 最小闭环：`POST /api/knowledge/entity-search` —— 用户免选版本，问题 → 规则/LLM 提取实体 → 解析 → 全版本聚合（currentFacts + timeline 分治）→ 结构化证据响应。新增 `EntityEvidenceAggregator` / `EntityQueryService` / `EntitySearchController`，存储层补 `findAlignmentRelationsForClaim` 与批量 `findEvidenceIdsByClaimIds`。

### Main Changes

- `EntityEvidenceAggregator`：成员批量水化 + 批量证据；timeline 按业务版本分块；currentFacts 分区分治（code/parameterTables/testResults）；确定性冲突（同 factKey 不同值 CONFLICTED）；稳定告警（CODE_CONTEXT_UNAVAILABLE / PARAMETER_TABLE_UNAVAILABLE，不编造 commit）；20 条/块上限。
- `EntityQueryService`：analyze → resolve（已解析直通 + 未命中兜底）→ aggregate → EntitySearchResponse（factAssessment 为 Phase 4 占位）。
- `EntitySearchController`：@Valid 请求 + 项目注册/访问控制。
- `CodeCentricAlignmentStore.findAlignmentRelationsForClaim`；`MultiSourceKnowledgeStore.findEvidenceIdsByClaimIds`（批量回源防 N+1）。

### 关键设计决策

- **跨版本值演进不是冲突**：factKey 含版本，同 factKey 分组只在同版本生效；5.0=100 → 5.1=120 走时间轴展示，不判 CONFLICTED（冲突测试用同版本同 factKey 双来源不同值）。
- **需求归并到实体需要 module 对齐**：AlignmentTestSupport.requirement 的 factKey 模块为空会导致概念 key 退化为 subject（与 comb 前缀参数实体不同）——测试需手动构造带模块的 factKey。

### Testing

- `./mvnw test`：1050 tests，0 failures（+7 Phase 3）。

### Status

[OK] **Phase 3 complete，最小闭环可用；下一步 Phase 4（实体事实优先级与实现偏差）或 Phase 5（AI 带证据回答）**

## Session 29: 实体中心检索 Phase 4/5/6——事实优先级·带证据回答·局部图扩展（六阶段完成）

**Date**: 2026-08-27
**Task**: 08-27-ec-phase4-fact-priority / ec-phase5-answer / ec-phase6-lightrag-graph
**Branch**: `main`

### Summary

- **Phase 4**：`EntityFactPriorityService`——按问题类型选主证据视图（当前行为 CODE>TEST_RESULT>PARAMETER_TABLE>REQUIREMENT，当前数值 PARAMETER_TABLE 优先）；FactAssessment 升级为结构化 AssessmentItem；确定性偏差信号 REQUIREMENT_PARAMETER_MISMATCH / REQUIREMENT_IMPLEMENTATION_GAP / CODE_PARAMETER_MISMATCH；不做来源仲裁。
- **Phase 5**：`KnowledgeAnswerService`——受限证据包 + 模型引用服务端校验（PARTIAL/UNVERIFIED/VERIFIED）+ 确定性模板降级；`POST /api/knowledge/entity-answer`。
- **Phase 6**：`EntityGraphExpansionService`——一跳/两跳关系召回 + 向量命中映射回实体 + 检索指标；`POST /api/knowledge/entity-search/related`。向量索引扩大保持评测驱动。

### 关键设计决策

- **CODE_PARAMETER_MISMATCH 用确定性信号而非读代码值**：成员不携带代码配置值，偏差信号用“代码成员存在 + 数值表 + FAILED 测试”三元信号，避免伪造代码值；需求-参数 mismatch 用数值化比较（剥离单位）。
- **FactAssessment 结构性升级**：从 List<String> 改为 AssessmentItem(type/value/sourceType/status)，实体评估作为响应级摘要（多实体取首个），兼容旧 EMPTY 常量。
- **答案引用允许集** = citations(claimId/evidenceId) + 代码成员 evidence；模型返回非法引用丢弃并降级质量，不信任模型输出。
- **Phase 6 不接 Qdrant**：只提供映射能力与指标，符合 dev md §10.3 第 3 种（评测证明收益后再扩大）。

### Testing

- `./mvnw test`：1060 tests，0 failures（+10：Phase4 4 + Phase5 4 + Phase6 2）。

### Status

[OK] **Phase 1-6 全部完成；下一步：全量自评审直到无 High/Medium 风险**

## Session 30: 实体中心检索 Phase 1-6 全量自评审（直到无 High/Medium）

**Date**: 2026-08-27
**Task**: 08-27-entity-centric-knowledge-retrieval（全阶段自评审）
**Branch**: `main`

### 评审发现并修复（按严重度）

1. **[High] `QuestionEntityAnalyzer` 空 Optional.get()**：allowLlmAssist=true 且规则已命中、LLM 返回空时走 else 分支执行 `raw.get()` → NoSuchElementException。重构为 `isPresent()/else if` 三分支，新增回归测试 `llmAssistEmptyDoesNotBreakWhenRuleMentionsExist`。
2. **[Medium] resolver 3 参重载静默丢弃歧义候选**：preResolved 含 entityId=null 的 CANDIDATE mention 时无 `ENTITY_NEEDS_REVIEW` 告警 → 补 unresolved 统计 + 告警。
3. **[Medium] `EntityFactPriorityService` 用遍历顺序第一条需求当"最新需求"**：版本块顺序不保证 → 改为按数值感知版本比较取最新块（`versionCompare`，5.9<5.10），新增测试覆盖 5.0=100/5.1=120 场景。
4. **[Medium] 时间轴版本块未排序**：成员遍历序展示 → `capped()` 按数值版本升序排序。
5. **[Medium] 时间轴缺历史 TEST_RESULT**：dev md §8.3 要求"所有版本测试结果"进时间轴，原实现仅最新版 currentFacts → `appendToBlock` 对 TEST_RESULT 一并入块。
6. **[Medium] `KnowledgeAnswerService` 证据全非法时模型文本与模板混杂**：kept==0 时整体回退模板（带 ANSWER_EVIDENCE_UNVERIFIED 告警），不混用。
7. **[Medium] 噪音告警**：规则命中成功时误报 `ENTITY_LLM_UNAVAILABLE` → 仅在规则一无所获时提示。
8. **[Low] 清理**：`EntityLlmAssistant.unusedCall` 死代码、控制器 `NotNull` 未用 import、聚合器 `isTestResult` 死方法、buildEvidencePackage 未用局部变量。

### 验证

- 全量 `./mvnw test`：1062 tests，0 failures。
- 新增回归测试 2 例（空 Optional 路径、最新版本需求 mismatch）。
- 评审确认的质量门：跨项目/版本泄漏=0（projectRegistry+版本过滤）、无 Evidence 结论=0（引用校验）、代码/参数冲突不静默丢失（factAssessment gaps）、LLM 无来源实体不落库（PROPOSED + resolve-or-drop）。

### 已知接受的限制（Low）

- `findConfirmedAliasesMentionedIn` 为 instr 全表扫描（limit 封顶）；大别名库下为后续优化项。
- 并发概念构建无锁（沿用 alignment 子系统既有行为）。
- 项目级历史 Claim collection / 向量索引扩大按 dev md §10.3 评测驱动，未接 Qdrant。

### Status

[OK] **自评审完成，无 High/Medium 剩余；实体中心检索 Phase 1-6 全部落地（1062 tests 绿）**

## Session 31: 实体中心检索外部 Review 修复（6 项 High/Medium 全部修复）

**Date**: 2026-08-27
**Task**: 08-27-entity-centric-knowledge-retrieval（review → fix → re-review）
**Branch**: `main`

### 修复清单

1. **DRAFT 泄漏（High）**：`findPublishedBusinessVersions` + `findPublishedClaimsByProjectVersionAll`（PUBLISHED 过滤）；`BusinessConceptService.build/buildProject`、`EntityEvidenceAggregator.latestVersion` 全部切到已发布范围；测试 seed 改 PUBLISHED；新增 `buildProjectExcludesDraftVersions`。**决策**：实体层聚合全部已发布文档（时间轴需要），knowledge_active_version 单文档激活绑定仍由 Claim 向量投影契约负责（dev md Review 3）。
2. **无项目级构建入口（High）**：新增 `POST /api/knowledge/alignment/concepts/build-project`（WRITE），controller 测试。
3. **includeHistory 失效（High）**：request 显式值覆盖规则推导 → effectivePlan；Options 增加 includeHistory；false 时 timeline 空；答案层按 plan 门控 [HISTORICAL_REQUIREMENT]。
4. **代码事实无位置（High）**：FactRef 增加 location；聚合器注入 CodeSymbolLoader，按 symbol.id 回填 repository@commit:filePath:start-end；测试 `codeFactsCarryFileLocationAndCommit`。
5. **引用不做结论支持校验（High）**：分节声明 sourceType，服务端“允许集 + 类型”双重校验；参数表证据不能支撑代码行为结论；`evidenceTypeById` 映射 + `llmAnswerKeepsOnlyValidEvidenceReferences`/`evidenceTypeMismatchIsDroppedAndDowngradesQuality`。
6. **反向遍历 + 二跳版本（Medium）**：按 source/target 判定另一端点；遍历维护 claimVersion；测试 `reverseRelationExpandsWhenSeedIsTarget`。

### 验证

- `./mvnw test`：1068 tests，0 failures（+5 回归测试）。
- `git diff --check` 通过；无 High/Medium 剩余。

### Status

[OK] **Review 6 项全部修复并回归覆盖；实体中心检索链路（Phase 1-6 + review 修复）完成**

## Session 32: 实体中心检索 Review 第二轮修复（8 项 High/Medium 全部修复）

**Date**: 2026-08-27
**Task**: 08-27-entity-centric-knowledge-retrieval（review → fix → re-review 第二轮）
**Branch**: `main`

### 修复清单

1. **发布后陈旧成员泄漏（High）**：查询时按 `findPublishedDocumentVersionIds` 重校验水化 Claim（权威防护）；`queryFiltersClaimsOfDemotedDocumentVersions`。
2. **版本级构建挂历史代码（High）**：`build()` 仅最新已发布版本挂代码；`versionLevelBuildDoesNotAttachCurrentCodeToHistoricalVersion`。
3. **includeHistory=false 边界（High）**：timeline 保留最新块；冲突按输出内 Claim 计算；最新需求仍可评估。
4. **时间轴 FactRef 版本丢失（High）**：appendToBlock 带块版本；`timelineFactRefsCarryBusinessVersion`。
5. **无证据标记 CONFIRMED（High）**：`statusFor` 有证据才 SUPPORTED；模板无证据 UNVERIFIED；`factWithoutEvidenceIsNeverSupported`。
6. **MIXED 绕过 + 代码位置（High）**：分节强制单一类型，MIXED 证据丢弃；CURRENT_CODE 带 location；`mixedSectionCannotBypassTypeValidation`。
7. **跨版本关系端点（High）**：`memberClaimFor` 按版本 + 输入批次硬校验；`relationEndpointsAreScopedToCurrentVersion`。
8. **factKey 第三段 module 猜测（Medium）**：`AlignmentNaming.moduleOf` 来源特定语义（需求自引用 module → subject 锚定）。

### 验证

- `./mvnw test`：1074 tests，0 failures（+8 回归测试）。
- `git diff --check` 通过。

### Status

[OK] **Review 第二轮 8 项全部修复并回归覆盖**
