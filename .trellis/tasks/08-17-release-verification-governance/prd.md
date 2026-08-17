# 发布验证与工程治理

## Goal

让 `0.8.6` 的发布版本、验证报告、任务状态和发布文档保持一致，并能由其他开发者重复验证。

## Requirements

- 使用 JDK 21 对当前 HEAD 执行不跳过 Enforcer 的完整 Maven verify。
- 生成包含版本、commit、测试、覆盖率和构建结果的机器可读报告。
- 已经随 `0.8.6` 发布的 Trellis 任务必须归档或更新为真实状态。
- CHANGELOG 顶部版本顺序和当前版本章节结构必须清晰。
- 不覆盖用户已有的未跟踪文档；确认内容后纳入本次治理提交。

## Acceptance Criteria

- [x] `docs/verification/latest.json` 指向验证时的 HEAD 和版本 `0.8.6`。
- [x] Maven 测试、JaCoCo 和 jar 构建全部成功。
- [x] 已发布任务不再错误显示为 planning/in_progress。
- [x] CHANGELOG 包含本次治理变更且不存在比当前发布版本更靠前的旧快照版本。
- [x] `git diff --check` 通过。

## Out Of Scope

- 本子任务不修改检索排序算法、GitLab 业务逻辑或共享存储实现。
