# GitLab 项目自动接入使用说明

## 1. 功能说明

NEXUS 可以由超级管理员通过管理 API 接入 GitLab 项目。接入后，服务会：

1. 将 PAT 和 Webhook Secret 使用 AES-256-GCM 加密后保存到独立 SQLite。
2. 在服务端受控目录 clone 指定分支。
3. 首次执行全量代码索引。
4. 后续手动同步或 GitLab Push Hook 触发时执行快进增量索引。
5. 将项目动态发布到现有项目注册表，REST、MCP、Wiki 和代码检索继续使用同一个 `projectId`。

该功能默认关闭。关闭时不会初始化 GitLab 数据库、仓库目录或新增接口。

## 2. 服务端配置

生成 32 字节加密密钥：

```bash
openssl rand -base64 32
```

在 `.env` 中配置：

```properties
GITLAB_INTEGRATION_ENABLED=true
GITLAB_REPOSITORY_ROOT_PATH=/data/nexus/gitlab-repositories
GITLAB_DATABASE_PATH=/data/nexus/gitlab-integration.db
GITLAB_ENCRYPTION_KEY=<上一步生成的 Base64 密钥>
GITLAB_GIT_TIMEOUT_SECONDS=120
GITLAB_SYNC_THREADS=2
```

注意：

- `GITLAB_ENCRYPTION_KEY` 丢失后，已保存的 PAT 和 Webhook Secret 无法恢复。
- 仓库根目录和 SQLite 文件需要挂载持久卷。
- 管理 API 标记为 `Permission.ADMIN`，仅 `SUPER_ADMIN` 可以调用。
- clone URL 仅接受不含用户名、密码、查询参数和片段的 HTTPS URL。

## 3. 创建 GitLab PAT

在 GitLab 中创建只读 Personal Access Token，至少授予读取目标仓库所需的 `read_repository`
权限。NEXUS 不使用该 Token 向仓库写入内容。

## 4. 接入项目

```bash
curl -X POST http://localhost:8080/api/integrations/gitlab/projects \
  -H 'Content-Type: application/json' \
  -H 'X-Gateway-User: admin' \
  -H 'X-Gateway-Role: SUPER_ADMIN' \
  -d '{
    "projectId": "order-service",
    "name": "订单服务",
    "group": "commerce",
    "side": "server",
    "cloneUrl": "https://gitlab.example.com/commerce/order-service.git",
    "branch": "main",
    "gitPath": "commerce/order-service",
    "requirementCollection": "order_service_requirements",
    "codeCollection": "order_service_code",
    "accessToken": "glpat-REPLACE_ME",
    "webhookSecret": "REPLACE_WITH_RANDOM_SECRET"
  }'
```

接口立即返回 `202 Accepted`。后台状态依次为：

```text
PENDING -> CLONING -> SYNCING -> INDEXING -> READY
                                      \-> FAILED
```

查询状态：

```bash
curl http://localhost:8080/api/integrations/gitlab/projects/order-service
```

响应不包含 PAT、Webhook Secret 或密文。

状态响应中的 `lastIndexedSha` 表示当前已经成功发布的索引版本，`targetSha` 表示当前同步任务
正在追赶或最近一次尝试的目标 commit。任务失败时可用这两个字段判断是否发生版本偏离。

## 5. 配置 GitLab Push Hook

在 GitLab 项目的 **Settings > Webhooks** 中配置：

- URL：`https://<NEXUS_HOST>/api/webhooks/gitlab/order-service`
- Secret token：接入项目时提交的 `webhookSecret`
- Trigger：`Push events`
- SSL verification：生产环境保持开启

NEXUS 校验 GitLab 原生 `X-Gitlab-Token`，并按 `X-Gitlab-Event-UUID` 去重。未提供事件
UUID 时使用请求体 SHA-256 作为幂等键。非目标分支的 Push 会返回 `ignored`。

## 6. 运维接口

```text
GET    /api/integrations/gitlab/projects
GET    /api/integrations/gitlab/projects/{projectId}
POST   /api/integrations/gitlab/projects/{projectId}/sync
POST   /api/integrations/gitlab/projects/{projectId}/retry
DELETE /api/integrations/gitlab/projects/{projectId}
```

- `sync`：拉取配置分支当前 HEAD。
- `retry`：仅允许重试 `FAILED` 项目。
- `DELETE`：将项目置为 `DISABLED` 并从动态注册表移除；仓库、索引和元数据会保留。

## 7. 一致性与失败处理

- 每个项目同一时刻只运行一个 Git 同步/索引任务；执行期间到达的后续 Push 会按顺序排队，
  不会因前一个任务仍在运行而丢失。
- 首次同步使用现有全量索引服务；后续同步使用现有增量索引服务。
- 只有索引成功发布后才更新 `lastIndexedSha`。
- 非快进 Push 会进入 `FAILED`，不会用新分支历史覆盖旧索引。
- 禁用操作具有状态优先级，已经运行的后台任务不能把 `DISABLED` 覆盖回运行中或成功状态。
- Git 失败信息会转换为稳定公开消息，不返回 PAT、内部 URL、命令输出或绝对路径。
- 旧 `/api/webhooks/gitlab` HMAC 入口保持不变，便于静态项目继续使用。

## 8. 当前边界

当前版本由管理员直接提交 PAT 和项目信息，尚未实现：

- GitLab OAuth 登录和 Token 刷新。
- 自动枚举当前用户可访问的项目和分支。
- Web 管理页面。
- 多实例共享任务队列。

这些能力不影响当前在单实例 NEXUS 中完成团队级项目自动接入和持续索引。
