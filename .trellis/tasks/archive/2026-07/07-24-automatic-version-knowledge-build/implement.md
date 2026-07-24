# Implementation Plan: 自动版本知识构建与统一证据检索

## Phase A — Unified retrieval

- [x] 新增 profile/request/bundle 合同。
- [x] 实现项目路由、双源召回、去重、限流、状态/警告/诊断聚合。
- [x] 添加成功、零命中、降级和失败测试。
- [x] 迁移同步 DevelopmentPlanService。
- [x] 迁移 DevelopmentPlanStreamService，保持 SSE 兼容。

## Phase B — Draft knowledge build

- [x] 扩展 WikiProperties 的 draftPath。
- [x] 新增构建请求、结果、FeatureFactDraft 模型。
- [x] 实现版本滚动读取和 baseVersion 增量比较。
- [x] 实现稳定 featureId、证据裁剪、草稿 Wiki source 和构建清单写入。
- [x] 新增 `/api/knowledge/build` 与权限/输入校验。
- [x] 添加差异、独立 ID、安全路径、禁用字段和 Controller 测试。

## Phase C — Version and documentation

- [x] 将 NEXUS 平台版本更新到 `0.2.0-SNAPSHOT`。
- [x] 新增 CHANGELOG，更新 README 工作流和边界。
- [x] 确认 `.gitignore` 持续排除所有向量库和本地工具数据。

## Phase D — Verification and delivery

- [x] 运行目标单元测试。
- [x] 运行 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify`。
- [x] 运行 `git diff --check`。
- [x] 执行 Trellis spec update/任务记录。
- [x] 检查 Git 变更后提交并推送 main。

## Verification Record

- 2026-07-24: Java 21 full verify passed: 88 tests, 0 failures, 0 errors.
- 2026-07-24: `git diff --check` passed.
- 2026-07-24: Git safety scan confirmed `.env`, `.idea`, `.codegraph`, Qdrant storage/snapshots, generic storage, and `target` remain ignored and untracked.
