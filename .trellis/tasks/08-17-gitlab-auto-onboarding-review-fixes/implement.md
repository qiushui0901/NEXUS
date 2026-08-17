# GitLab 自动接入 Review 修复执行计划

## 实现步骤

- [x] 扩展 `GitLabIntegrationProperties`、`application.yml` 和 `.env.example`。
- [x] 在 `GitLabGitClient` 实现精确 Host 白名单及地址安全策略，并让注册入口复用同一校验。
- [x] 重构 `GitLabProjectStore` 的 `targetSha` 替换/保留语义。
- [x] 调整 `GitLabSyncService` 的同步、失败、禁用和冲突状态更新。
- [x] 在注册表恢复后重新调度中断状态。
- [x] 增加 URL 安全、Store 清空/保留、早期失败重试和重启恢复测试。
- [x] 更新简体中文接入指南、GitLab 规范和 `CHANGELOG.md`。

## 验证命令

```bash
./mvnw -Dtest='GitLab*Test,ProjectRegistryDynamicTest,RequirementRagApplicationTest' test
./mvnw verify
git diff --check
```

## 风险与检查点

- Host 校验不得通过后缀匹配，防止 `trusted.example.evil.test` 绕过。
- DNS 返回多个地址时，只要任一地址不安全，默认策略就拒绝。
- `allowPrivateHosts` 不能绕过 Host 白名单。
- `fail()` 必须保留已经记录的目标；最新 HEAD 同步开始必须清除旧目标。
- 启动恢复不得调度 `FAILED`，避免无限失败循环。
- 不修改或提交 `面试问答全解-NEXUS.md`。
