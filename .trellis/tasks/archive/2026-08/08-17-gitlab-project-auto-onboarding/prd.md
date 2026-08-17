# GitLab 项目自动接入

## Goal

让 NEXUS 超级管理员通过 REST API 接入公司 GitLab 私有项目：服务端自动保存项目定义、受控
clone/fetch 指定分支、注册到现有项目目录并建立代码索引；后续 GitLab Push Hook 能幂等触发
目标 commit 的同步与增量索引。

## Background

- 当前 `ProjectRegistry` 只读取启动时的静态配置。
- 当前代码索引只扫描服务器已有的本地 Git 工作树，不负责 clone/fetch。
- 当前 `/api/webhooks/gitlab` 使用非 GitLab 原生的 HMAC 请求头，而且只重新索引本地目录。
- 项目已有 SQLite JDBC、项目级索引锁、全量/增量索引、角色权限与项目访问控制。
- 第一版以管理员显式提交 PAT 和项目参数为入口；OAuth、项目发现页面后续实现。

## Requirements

### R1. 管理 API

- 仅 `SUPER_ADMIN` 可注册、同步、重试、禁用 GitLab 项目。
- 提供注册、列表、详情、同步、重试和禁用 API。
- 注册请求包含稳定 `projectId`、展示名称、GitLab HTTPS clone URL、`pathWithNamespace`、
  分支、PAT 和 webhook secret。
- API 响应、日志和异常不得返回 PAT、加密密文或 webhook secret。

### R2. 持久化与动态注册

- 托管项目和同步状态持久化在独立 SQLite 数据库。
- PAT 使用 AES-GCM 加密，密钥从 `GITLAB_INTEGRATION_ENCRYPTION_KEY` 注入。
- 托管项目在应用启动时加载，并与静态项目共同进入 `ProjectRegistry`。
- 动态项目使用独立代码 collection；禁用后不再接受同步和 webhook。

### R3. 受控 Git 同步

- 仓库只能写入 `GITLAB_INTEGRATION_REPOSITORY_ROOT` 下的受控目录。
- 只接受 HTTPS GitLab URL、合法项目 ID、合法分支和 40 位 commit SHA。
- PAT 通过临时 `GIT_ASKPASS` 和环境变量注入，不出现在 remote URL、命令参数和日志中。
- 首次注册执行 clone/checkout 和全量索引；后续同步 fetch 目标 commit、验证 commit 属于配置分支，
  再 checkout 到 detached HEAD。
- 同一项目的同步任务串行执行；失败保留上一次可用索引并记录可重试状态。

### R4. GitLab Webhook

- 使用 GitLab 原生 `X-Gitlab-Token`，按项目校验 secret。
- 使用 `X-Gitlab-Event-UUID` 去重；缺失时退回 `projectId + after SHA`。
- 仅接受 Push Hook、已启用项目、配置分支和合法目标 SHA。
- 重复事件返回已接受但不重复执行；未知项目、错误 secret、错误分支和非快进更新有明确响应。
- 合法 Push Hook 进入后台同步，完成后执行增量索引；首次或无法安全增量时执行全量索引。

### R5. 兼容与运维

- `app.gitlab-integration.enabled=false` 时现有静态项目、索引、检索和旧 API 行为保持不变。
- 状态至少包含 `PENDING/CLONING/SYNCING/INDEXING/READY/FAILED/DISABLED`、当前 commit、
  目标 commit、错误摘要和更新时间。
- 提供环境变量、curl 和 GitLab Webhook 配置的简体中文文档。

## Acceptance Criteria

- [ ] 管理员能通过 API 注册私有 GitLab 项目，后台自动 clone 并完成首次全量索引。
- [ ] 注册完成后项目立即出现在 `/api/projects`，无需重启应用。
- [ ] 管理员能查询状态、手动同步、失败重试和禁用项目。
- [ ] GitLab Push Hook 能通过原生 token 校验、事件去重并同步到目标 commit。
- [ ] 快进 Push 执行增量索引；首次同步或不满足增量条件时安全回退全量索引。
- [ ] 非法 URL、目录逃逸、错误 secret、未知项目、错误分支、非法 SHA、重复事件和非快进均有测试。
- [ ] PAT、密文和 webhook secret 不出现在 API 响应、Git remote URL、日志消息或异常文本中。
- [ ] 功能关闭时现有基线测试行为不回归，并新增成功、重复、失败重试和权限测试。
- [ ] `CHANGELOG.md`、用户文档和后端代码规范同步更新。

## Out Of Scope

- GitLab OAuth Authorization Code + PKCE。
- 自动枚举当前用户可访问的 GitLab 项目和分支。
- 前端项目选择/导入页面。
- GitHub、Gitee 等其他托管平台。
- 多实例任务队列和跨实例分布式锁（由独立子任务处理）。
