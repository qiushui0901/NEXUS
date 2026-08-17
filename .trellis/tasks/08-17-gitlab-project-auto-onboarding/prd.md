# GitLab 项目自动接入

## Goal

让管理员能够将 GitLab 项目注册到 NEXUS，并由 Webhook 驱动可审计、可重试的增量索引。

## Requirements

- 凭据不得写入仓库、日志或 API 响应。
- clone/fetch、默认分支和 commit SHA 必须经过验证。
- Webhook 必须校验签名、去重并进入后台任务。
- 同一项目的索引任务必须串行化并支持失败重试。
- 项目权限必须沿用现有 MCP/REST 访问控制。

## Acceptance Criteria

- [ ] 项目注册、同步、禁用和状态查询 API 可用。
- [ ] Push Webhook 能幂等触发目标 commit 的增量索引。
- [ ] 非法签名、未知项目、非快进和重复事件有明确处理。
- [ ] 集成测试覆盖成功、重复、失败重试和权限场景。

## Out Of Scope

- 首期不实现 GitHub、Gitee 等其他托管平台。
