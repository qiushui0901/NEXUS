# 技术设计

## 1. 架构边界

将 GitLab 集成拆成“账号连接”和“托管项目”两层：

```text
GitLabConnectionController
  -> GitLabAccountService
  -> GitLabApiClient
  -> GitLabConnectionStore

GitLabIntegrationController
  -> GitLabProjectImportService
  -> GitLabSyncService
  -> GitLabProjectStore / GitLabJobStore
```

账号连接拥有实例地址、账号身份和加密 PAT。托管项目继续拥有分支、collection、
Webhook Secret、同步状态和索引版本，只保存 `connectionId` 引用。

## 2. 数据模型与兼容迁移

新增 SQLite 表：

```text
gitlab_connection(
  id PK,
  name,
  base_url,
  host,
  username,
  display_name,
  access_token_ciphertext,
  status,
  last_verified_at,
  last_error,
  created_at,
  updated_at
)
```

`gitlab_managed_project` 增加可空 `connection_id`、`remote_project_id`，并对二者建立
部分唯一索引。远端稳定身份为 `(connection_id, remote_project_id)`；`path_with_namespace`
只用于展示、Webhook 路由与旧记录兼容，不能跨实例判重。SQLite 初始化使用现有
`CREATE TABLE IF NOT EXISTS` 与 `ensureColumn` 风格做增量迁移。

- 新项目：`connection_id` 必填，`access_token_ciphertext` 为空。
- 旧项目：`connection_id` 为空时继续读取原项目密文，不强制重新索引。
- 重新关联旧项目后清除项目级 PAT 密文，避免继续重复保存。
- 删除或停用连接不级联删除项目和索引；项目继续可读，但同步返回稳定的账号不可用错误。

## 3. GitLab API 客户端

新增 `GitLabApiClient`，使用有界连接/读取超时和 JSON DTO：

```http
GET /api/v4/user
GET /api/v4/projects?membership=true&simple=true&per_page=100&page=N
GET /api/v4/projects/{id}
```

- 使用 `PRIVATE-TOKEN` 请求头，不把 PAT 放入 URL。
- 跟随 `X-Next-Page` 读取全部分页，并设置最大页数/项目数上限；非空搜索词通过
  GitLab `search` 参数在分页前过滤。
- 处理 401/403、404、429、5xx、超时和无效 JSON，映射为稳定公开错误。
- 项目发现只使用 `membership=true`，不返回仅公开可见的无关项目。
- 返回字段限定为远端 ID、名称、命名空间路径、HTTP Clone URL、默认分支、可见性、
  归档状态和最近活动时间。
- 导入时后端按远端项目 ID 并发读取项目详情，通过 `permissions` 确认账号确实为项目成员；
  不信任前端提交的 Clone URL、命名空间或默认分支，也不在批量请求内同步执行 `git ls-remote`。

现有 Clone URL Host 白名单、IP/内网地址限制抽取为共享 `GitLabHostPolicy`，Git API
请求和 Git Clone/Fetch 使用同一安全判断；账号 PAT 解析时额外要求 Clone URL 与连接
Base URL 的规范化 Host 和有效端口完全一致。

## 4. API 契约

新增 ADMIN API：

```http
POST   /api/integrations/gitlab/connections
GET    /api/integrations/gitlab/connections
GET    /api/integrations/gitlab/connections/{connectionId}
POST   /api/integrations/gitlab/connections/{connectionId}/verify
POST   /api/integrations/gitlab/connections/{connectionId}/reauthorize
DELETE /api/integrations/gitlab/connections/{connectionId}

GET    /api/integrations/gitlab/connections/{connectionId}/projects
POST   /api/integrations/gitlab/connections/{connectionId}/imports
```

连接创建请求包含显示名称、实例 Base URL 和 PAT。响应只返回安全账号信息和状态。

项目发现响应分页：

```json
{
  "items": [],
  "page": 1,
  "size": 50,
  "total": 0
}
```

每个项目返回 `importState`：`AVAILABLE`、`IMPORTED`、`ARCHIVED`、`NO_DEFAULT_BRANCH`
或 `CONFLICT`。

批量导入请求只提交远端项目 ID 与用户可编辑配置：

```json
{
  "projects": [
    {
      "remoteProjectId": 123,
      "projectId": "group-service",
      "side": "server",
      "branch": "main",
      "requirementCollection": "group_service_requirements",
      "codeCollection": "group_service_code"
    }
  ]
}
```

响应逐项目返回 `ACCEPTED` 或 `FAILED` 及稳定错误码。成功项目立即进入现有首次同步队列；
单个失败不回滚其他成功项目。

## 5. 默认值与冲突处理

- `projectId` 从 `path_with_namespace` 生成小写安全 slug；冲突时追加远端项目 ID。
- 显示名称使用 GitLab 项目名，分组使用命名空间路径。
- 端类型默认 `server`，导入前可改为 `client`。
- 分支默认远端 `default_branch`，允许选择远端存在的其他分支。
- collection 从最终 `projectId` 生成，并复用现有 collection 校验。
- 已归档或没有默认分支的项目默认不可勾选。
- 已导入项目显示当前 NEXUS 项目 ID，不重复导入。

## 6. 凭据解析与同步

新增 `GitLabCredentialResolver`：

1. 项目存在 `connectionId` 时，从连接表解密 PAT。
2. 连接必须为 ACTIVE；失效或停用返回稳定错误。
3. 旧项目无连接时回退到原项目密文。

`GitLabSyncService`、手动同步、重试和恢复队列统一通过 resolver 取凭据。日志只记录连接 ID、
项目 ID 和异常类型，不记录 URL、用户名或 Token。

## 7. 前端交互

`/settings/gitlab` 保留“已导入项目”作为默认视图，并新增“GitLab 账号”视图：

1. 点击“关联 GitLab 账号”。
2. 输入连接名称、实例地址和 PAT，验证后显示账号身份。
3. 进入账号详情，分页展示该账号实际参与的项目。
4. 搜索、筛选、跨页保留选择。
5. 导入前查看自动配置；需要时逐项目展开编辑。
6. 点击“导入并开始同步”，逐项目显示接受/失败结果。

旧 `/settings/gitlab/new` 路由兼容跳转到账号关联页。PAT 只存在于关联/重新授权表单内存，
离开页面、完成授权、路由变化和组件卸载时清空。

## 8. Webhook

批量导入为每个项目生成独立 Webhook Secret。导入结果只在当前页面一次性展示项目对应的
Webhook URL 和 Secret；后续仍使用现有轮换入口。首版不自动调用 GitLab 创建 Webhook。

## 9. 失败与回滚

- GitLab API 不可用：账号详情显示安全错误，连接保持原状态，已导入项目和旧索引继续可用。
- PAT 失效：连接标记 INVALID；重新授权成功后恢复 ACTIVE。
- 部分导入失败：保留成功项目并展示逐项失败原因。
- 关闭 `app.rag.gitlab.enabled` 时所有新增 Bean/API 与现有 GitLab 集成一起关闭。
- 回滚代码不会删除新表；旧项目读取路径继续兼容。

## 10. 测试

- Connection Store：建表、迁移、加密字段不回显、重启持久化。
- API Client：账号验证、membership 项目、跨页读取、搜索、401/403/429/超时和项目详情。
- Host Policy：Git API 与 Git Clone 使用同一白名单和私网规则。
- Import Service：默认值、冲突、归档/无分支、重复项目、部分成功和立即排队。
- Credential Resolver：连接凭据、失效连接和旧项目密文回退。
- Controller：ADMIN 权限、分页、脱敏、批量响应。
- Frontend：账号路由、敏感字段清理、跨页选择、可编辑配置、部分失败和移动端布局。
- 完整 Java 21 `verify`、JS 语法、`git diff --check` 和真实 GitLab 账号浏览器冒烟。
