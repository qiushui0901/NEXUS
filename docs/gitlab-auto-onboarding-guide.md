# GitLab 项目自动接入使用说明

## 1. 功能说明

NEXUS 可以由超级管理员通过 `/settings/gitlab` 关联一个或多个 GitLab 账号，查看账号实际
加入或拥有的项目，选择一个或多个项目导入。旧的逐仓库管理 API 继续兼容。
接入后，服务会：

1. 将账号 PAT 和项目 Webhook Secret 使用 AES-256-GCM 加密后保存到独立 SQLite。
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

在 GitLab 中创建只读 Personal Access Token，授予：

- `read_api`：读取当前账号和该账号实际参与的项目列表。
- `read_repository`：通过 HTTPS clone/fetch 已选择的代码仓库。

NEXUS 不使用该 Token 向仓库写入内容。

## 4. 通过管理页面接入

使用 `SUPER_ADMIN` 身份打开：

```text
http://localhost:8080/settings/gitlab
```

1. 点击“关联 GitLab 账号”，填写连接名称、实例地址和 PAT。
2. 后端验证账号并加密保存 PAT；管理页面不会回显 Token。
3. 打开账号详情，搜索并勾选该账号实际参与的项目。仅公开可见但账号未加入的项目不会出现。
4. 系统按 `path_with_namespace` 自动生成 NEXUS `projectId` 和代码 collection；导入前可逐项目
   调整端类型，并从远端分支下拉列表中选择分支。GitLab 仓库导入只接入代码，不在这里绑定需求知识。
5. 进入“配置导入”确认页后点击“确认导入并开始同步”。每个项目独立返回成功或失败，
   成功项目立即进入首次同步队列。
6. 页面一次性显示各项目的 Webhook URL 和 Secret；离开页面后只能通过项目详情轮换 Secret。

PAT、重新授权 Token 和一次性 Webhook Secret 仅保存在当前表单/结果页面内存，不进入 URL
或 `localStorage`。项目详情页可直接访问 `/settings/gitlab/projects/{projectId}`，展示：

- `lastIndexedSha`、`targetSha`、版本偏离和旧索引可用性。
- 同步任务历史、当前阶段、触发方式和稳定错误码。
- 最近一次 Webhook 接收结果、目标 commit 和接收时间。
- 手动同步、失败重试、停用和 Secret 轮换操作。

## 5. 通过账号 API 接入

账号连接与项目发现接口：

```text
POST   /api/integrations/gitlab/connections
GET    /api/integrations/gitlab/connections
GET    /api/integrations/gitlab/connections/{connectionId}
POST   /api/integrations/gitlab/connections/{connectionId}/verify
POST   /api/integrations/gitlab/connections/{connectionId}/reauthorize
DELETE /api/integrations/gitlab/connections/{connectionId}
GET    /api/integrations/gitlab/connections/{connectionId}/projects
GET    /api/integrations/gitlab/connections/{connectionId}/projects/{remoteProjectId}/branches
POST   /api/integrations/gitlab/connections/{connectionId}/imports
```

创建连接：

```bash
curl -X POST http://localhost:8080/api/integrations/gitlab/connections \
  -H 'Content-Type: application/json' \
  -H 'X-Gateway-User: admin' \
  -H 'X-Gateway-Role: SUPER_ADMIN' \
  -d '{
    "name": "公司 GitLab",
    "baseUrl": "https://gitlab.example.com",
    "accessToken": "glpat-REPLACE_ME"
  }'
```

响应包含连接 ID、账号名和状态，不包含 PAT 或密文。使用连接 ID 获取项目列表与远端分支后，
将选中的 `remoteProjectId`、`projectId`、端类型、分支和代码 collection 提交到 `/imports`。
需求知识在知识管理流程中独立导入和关联，不属于 GitLab 仓库导入请求。

### 旧逐仓库 API

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

旧接口继续可用。账号批量导入和旧接口创建的项目都会立即进入后台队列，状态依次为：

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
POST   /api/integrations/gitlab/projects/{projectId}/enable
DELETE /api/integrations/gitlab/projects/{projectId}
GET    /api/integrations/gitlab/projects/{projectId}/jobs
GET    /api/integrations/gitlab/projects/{projectId}/jobs/{jobId}
GET    /api/integrations/gitlab/projects/{projectId}/webhook-status
POST   /api/integrations/gitlab/projects/{projectId}/webhook-secret/rotate
```

- `sync`：拉取配置分支当前 HEAD。
- `retry`：仅允许重试 `FAILED` 项目。已记录目标 commit 时固定重试该目标；如果任务在解析
  远端 HEAD 前失败，则重新获取当前远端 HEAD，不会误用上一次成功的旧目标。
- `enable`：将 `DISABLED` 项目原地恢复并提交一次最新 HEAD 同步；项目 ID、仓库、历史索引
  和任务记录保持不变。
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

GitLab 仓库接入遵循“业务项目 → 多仓库”模型：

- 业务项目拥有共享需求、产品版本、Wiki 和权限边界。
- GitLab 仓库只负责自身分支、commit、Webhook、同步任务和代码索引。
- 批量导入前必须统一选择一个已经配置完成的业务项目；单个仓库不能在导入行内改归属。
- 产品版本由业务项目的主仓库构建元数据决定。Immortal 当前主仓库
  `immortal-game-service` 的 Maven 版本为 `v5.2.0`，需求最高版本 `5.1` 会显示为落后，
  但最后可用需求仍可用于带警告的检索。
- 普通仓库只能属于一个业务项目。跨项目公共库独立索引，由业务项目显式引用。

尚未实现：

- GitLab OAuth 登录和 Token 刷新。
- 多实例共享任务队列。

这些能力不影响当前在单实例 NEXUS 中完成团队级项目自动接入和持续索引。
