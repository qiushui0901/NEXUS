# Implementation Plan: 版本档案与多来源差异分析

## Phase A — Contracts and persistence

- [x] 新增 `VersioningProperties` 并注册配置属性。
- [x] 定义 manifest、测试快照和比较报告模型。
- [x] 实现安全、原子的 `VersionManifestService` 保存/读取/列表。
- [x] 添加 manifest 持久化、更新、排序、非法路径和非法 commit 测试。

## Phase B — Shared diff services

- [x] 抽取 `GitDiffService`，让增量代码索引复用。
- [x] 实现需求父块差异分析器，复用 payload-only `scrollVersion`。
- [x] 实现测试快照和 Wiki 索引比较。
- [x] 实现 `VersionComparisonService` 聚合与 warning/降级行为。
- [x] 添加需求、Git、测试和 Wiki 差异回归测试。

## Phase C — API and documentation

- [x] 新增 manifest 与比较 API、权限和项目访问校验。
- [x] 添加 Controller 测试。
- [x] 更新 README、CHANGELOG、`.env.example` 和平台版本记录。

## Phase D — Verification

- [x] 运行目标测试和 Java 21 完整 verify。
- [x] 运行 `git diff --check`。
- [x] 检查 Git 变更不包含向量库、凭据、IDE 或本地工具数据。
- [x] 更新 Trellis 规范和任务记录。


## Verification Record

- 2026-07-24：使用 Java 21 执行 `./mvnw -B verify`，共 98 个测试，0 failure、0 error、0 skipped，构建成功。
- 2026-07-24：`git diff --check` 通过。
- 2026-07-24：新增独立 `GitDiffServiceTest`，覆盖 A/M/D/R、Java/测试/配置分类统计和非法 SHA 拒绝。
- 2026-07-24：版本档案测试覆盖创建时间保留、排序、路径穿越、非法 commit、重复测试 `caseId`、临时文件清理和禁用数据字段缺失。
- 2026-07-24：Git 跟踪文件及当前工作区检查未发现 Qdrant storage、snapshot、WAL、`.env`、`.idea` 或 `.codegraph` 数据；`target/`、`.env`、`.idea/`、`.codegraph/` 继续由 `.gitignore` 排除。
- 2026-07-24：公开降级 warning 使用固定文本，不返回 Git/Qdrant/Wiki 异常原文、内部 URL 或绝对路径。
- 2026-07-24：平台版本为 `0.3.0-SNAPSHOT`，业务需求版本继续只作为 manifest 和知识来源标识。
