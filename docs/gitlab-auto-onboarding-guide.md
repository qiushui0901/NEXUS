# GitLab 项目自动接入使用说明

## 1. 功能说明

NEXUS 可以由超级管理员通过 `/settings/gitlab` 可视化工作台或管理 API 接入 GitLab 项目。
接入后，服务会：

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
GITLAB_UI_ENABLED=true
GITLAB_REPOSITORY_ROOT_PATH=/data/nexus/gitlab-repositories
GITLAB_DATABASE_PATH=/data/nexus/gitlab-integration.db
GITLAB_ENCRYPTION_KEY=<上一步生成的 Base64 密钥>
GITLAB_GIT_TIMEOUT_SECONDS=120
GITLAB_SYNC_THREADS=2
GITLAB_ALLOWED_HOSTS=gitlab.example.com
GITLAB_ALLOW_PRIVATE_HOSTS=true
```

注意：

- `GITLAB_ENCRYPTION_KEY` 丢失后，已保存的 PAT 和 Webhook Secret 无法恢复。
- 仓库根目录和 SQLite 文件需要挂载持久卷。
- 管理 API 标记为 `Permission.ADMIN`，仅 `SUPER_ADMIN` 可以调用。
- `GITLAB_UI_ENABLED=false` 只关闭 `/settings/gitlab` 页面，不影响已启用的管理 API、
  Webhook 和后台同步。
- clone URL 仅接受不含用户名、密码、查询参数和片段的 HTTPS URL。
- `GITLAB_ALLOWED_HOSTS` 是逗号分隔的精确 Host 白名单，默认只有 `gitlab.com`。不会按后缀
  信任子域，例如允许 `gitlab.example.com` 不会允许 `gitlab.example.com.evil.test`。
- 默认拒绝 IP、回环、链路本地和内网地址。公司自建私网 GitLab 必须同时把 Host 加入
  `GITLAB_ALLOWED_HOSTS`，并显式设置 `GITLAB_ALLOW_PRIVATE_HOSTS=true`。
- 公网 GitLab 应保持 `GITLAB_ALLOW_PRIVATE_HOSTS=false`。该开关只放宽地址类型限制，不会绕过
  Host 白名单。

## 3. 创建 GitLab PAT

在 GitLab 中创建只读 Personal Access Token，至少授予读取目标仓库所需的 `read_repository`
权限。NEXUS 不使用该 Token 向仓库写入内容。

## 4. 通过管理页面接入

使用 `SUPER_ADMIN` 身份打开：

```text
http://localhost:8080/settings/gitlab
```

点击“接入 GitLab 项目”，按五步向导完成：

1. 填写 Clone URL、PAT 和目标分支，先执行连接测试。
2. 设置 NEXUS `projectId`、显示名称、分组、端类型和 GitLab 路径。
3. 确认需求与代码 collection；页面会在提交前检查冲突。
4. 生成或填写 Webhook Secret，并在 GitLab 中配置页面给出的 Webhook URL。
5. 检查脱敏摘要并创建项目，随后在项目详情观察首次同步。

PAT 和 Webhook Secret 使用密码输入框，仅保存在当前页面内存，不进入 URL 或
`localStorage`。创建完成后，项目详情不会回显两者。Webhook Secret 轮换接口只在当前响应
中返回一次明文，离开页面前应立即更新 GitLab Webhook 配置。

项目详情页可直接访问 `/settings/gitlab/{projectId}`，展示：

- `lastIndexedSha`、`targetSha`、版本偏离和旧索引可用性。
- 同步任务历史、当前阶段、触发方式和稳定错误码。
- 最近一次 Webhook 接收结果、目标 commit 和接收时间。
- 手动同步、失败重试、停用和 Secret 轮换操作。

## 5. 通过 API 接入

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

创建前可使用以下预检接口；响应只包含脱敏检查结果：

```text
POST /api/integrations/gitlab/validate-connection
POST /api/integrations/gitlab/validate-project
POST /api/integrations/gitlab/projects/validate-config
```

## 6. 配置 GitLab Push Hook

在 GitLab 项目的 **Settings > Webhooks** 中配置：

- URL：`https://<NEXUS_HOST>/api/webhooks/gitlab/order-service`
- Secret token：接入项目时提交的 `webhookSecret`
- Trigger：`Push events`
- SSL verification：生产环境保持开启

NEXUS 校验 GitLab 原生 `X-Gitlab-Token`，并按 `X-Gitlab-Event-UUID` 去重。未提供事件
UUID 时使用请求体 SHA-256 作为幂等键。非目标分支的 Push 会返回 `ignored`。

## 7. 运维接口

```text
GET    /api/integrations/gitlab/projects
GET    /api/integrations/gitlab/projects/{projectId}
POST   /api/integrations/gitlab/projects/{projectId}/sync
POST   /api/integrations/gitlab/projects/{projectId}/retry
DELETE /api/integrations/gitlab/projects/{projectId}
GET    /api/integrations/gitlab/projects/{projectId}/jobs
GET    /api/integrations/gitlab/projects/{projectId}/jobs/{jobId}
GET    /api/integrations/gitlab/projects/{projectId}/webhook-status
POST   /api/integrations/gitlab/projects/{projectId}/webhook-secret/rotate
```

- `sync`：拉取配置分支当前 HEAD。
- `retry`：仅允许重试 `FAILED` 项目。已记录目标 commit 时固定重试该目标；如果任务在解析
  远端 HEAD 前失败，则重新获取当前远端 HEAD，不会误用上一次成功的旧目标。
- `DELETE`：将项目置为 `DISABLED` 并从动态注册表移除；仓库、索引和元数据会保留。
- `jobs`：查询持久化任务和阶段事件。应用重启时，尚未结束的 job 会标记为
  `INTERRUPTED`，不会继续显示为运行中。
- `webhook-status`：查询最近一次 Webhook 接收结果，不保存或返回请求正文。
- `webhook-secret/rotate`：生成并加密保存新 Secret，明文只在本次响应中出现一次。

## 8. 一致性与失败处理

- 每个项目同一时刻只运行一个 Git 同步/索引任务；执行期间到达的后续 Push 会按顺序排队，
  不会因前一个任务仍在运行而丢失。
- 首次同步使用现有全量索引服务；后续同步使用现有增量索引服务。
- 只有索引成功发布后才更新 `lastIndexedSha`。
- 非快进 Push 会进入 `FAILED`，不会用新分支历史覆盖旧索引。
- 应用重启时，历史中未结束的 job 先标记为 `INTERRUPTED`；项目处于
  `PENDING`、`CLONING`、`SYNCING`、`INDEXING` 时会创建新的恢复任务重新入队，
  `READY`、`FAILED`、`DISABLED` 不会被自动调度。
- 禁用操作具有状态优先级，已经运行的后台任务不能把 `DISABLED` 覆盖回运行中或成功状态。
- Git 失败信息会转换为稳定公开消息，不返回 PAT、内部 URL、命令输出或绝对路径。
- 旧 `/api/webhooks/gitlab` HMAC 入口保持不变，便于静态项目继续使用。

## 9. 当前边界

当前版本由管理员直接提交 PAT 和项目信息，尚未实现：

- GitLab OAuth 登录和 Token 刷新。
- 自动枚举当前用户可访问的项目和分支。
- 多实例共享任务队列。

这些能力不影响当前在单实例 NEXUS 中完成团队级项目自动接入和持续索引。
