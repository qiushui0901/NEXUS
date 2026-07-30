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
