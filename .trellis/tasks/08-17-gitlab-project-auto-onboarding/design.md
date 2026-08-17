# GitLab 项目自动接入设计

## Architecture

新增 `integration.gitlab` 包，边界分为：

1. `GitLabIntegrationProperties`：总开关、仓库根目录、SQLite 路径、加密密钥和任务参数。
2. `GitLabProjectStore`：使用 SQLite 保存项目定义、加密凭据、状态和 webhook 去重记录。
3. `GitLabCredentialCipher`：AES-256-GCM 加解密 PAT 与 webhook secret。
4. `GitLabGitClient`：校验 URL/分支/SHA，通过临时 `GIT_ASKPASS` 执行 clone/fetch/checkout。
5. 启动加载托管项目，并调用 `ProjectRegistry.register/unregister` 动态发布。
6. `GitLabSyncService`：项目级串行后台任务、状态机、首次全量索引与后续增量索引。
7. `GitLabIntegrationController`：仅 ADMIN 可调用的注册、列表、同步、重试、禁用 API。
8. `WebhookController`：新增项目级原生 GitLab Push Hook 入口，保留旧入口兼容。

## Data Model

SQLite `gitlab_managed_project` 保存项目元数据、加密凭据、启用状态、同步状态、当前/目标 commit、
错误摘要和时间戳。SQLite `gitlab_webhook_event` 以事件 ID 为主键保存去重记录。

敏感字段不进入响应 DTO。事件记录按保留期清理。

## API Contracts

- `POST /api/integrations/gitlab/projects`：创建项目并返回 `202` 状态快照。
- `GET /api/integrations/gitlab/projects`：列出托管项目。
- `GET /api/integrations/gitlab/projects/{projectId}`：查询项目。
- `POST /api/integrations/gitlab/projects/{projectId}/sync`：同步配置分支 HEAD。
- `POST /api/integrations/gitlab/projects/{projectId}/retry`：重试失败目标。
- `DELETE /api/integrations/gitlab/projects/{projectId}`：禁用项目，不删除仓库和索引。
- `POST /api/webhooks/gitlab/{projectId}`：GitLab Push Hook。

## Sync Flow

注册：

`validate -> encrypt/store PENDING -> register dynamic project -> clone branch -> full index -> READY`

Push：

`authenticate -> validate branch/SHA -> deduplicate -> queue -> fetch target -> verify ancestry ->
checkout target -> incremental index -> READY`

失败：

`record FAILED + public error -> keep previous Qdrant live collection -> retry reuses target commit`

## Compatibility

- 静态项目优先，动态项目不得覆盖同 ID 静态配置。
- 功能关闭时不初始化凭据、数据库、目录或新增 controller 行为。
- 旧 `/api/webhooks/gitlab` 保留，项目级新入口使用 GitLab 原生 token。

## Security

- 加密密钥要求 Base64 编码 32 字节；启用功能但密钥无效时应用启动失败。
- Git clone URL 只允许 `https`，禁止 user-info、query、fragment和非空白 host。
- 本地目录由规范化根目录加项目 ID 派生并做 `startsWith(root)` 校验。
- PAT 仅存在于加密数据库、短生命周期内存和子进程环境。
- 所有 API DTO 和日志只使用脱敏项目元数据。

## Rollback

- 关闭 `GITLAB_INTEGRATION_ENABLED` 即停止动态加载和新同步，不影响静态项目。
- 禁用单项目停止 webhook/sync，但保留仓库和最后可用索引供人工恢复。
- 数据库 schema 只新增独立表，不修改现有代码图数据库。
