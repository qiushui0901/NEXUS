# 实施计划

1. 完成 `release-verification-governance`。
   - 对当前 HEAD 执行完整 `./mvnw verify`。
   - 更新机器验证报告、Trellis 状态和 CHANGELOG。
   - 检查未跟踪文档并纳入或明确排除。
2. 完成 `real-rag-evaluation-baseline`。
   - 建立真实问题分类、冻结数据集和指标报告。
   - 将关键指标接入质量门。
3. 完成 `gitlab-project-auto-onboarding`。
   - 实现项目注册、仓库同步、Webhook 幂等和任务状态。
   - 覆盖权限、重试、分支和 commit provenance 测试。
4. 完成 `multi-instance-shared-state`。
   - 盘点本地状态并抽象共享存储。
   - 增加跨实例协调、迁移和故障恢复验证。
5. 执行全量质量检查并归档父任务。

## 验证

- `./mvnw verify`
- `git diff --check`
- 确认机器报告 commit 等于 `git rev-parse HEAD`
- 对 GitLab 和共享状态执行集成测试与故障注入测试

## 风险与回滚点

- 不在验证基线不可信时启用自进化策略。
- 不允许 Webhook 绕过项目权限和索引任务锁。
- 共享存储迁移必须保留本地实现作为开发和回滚路径。
