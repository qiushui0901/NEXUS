# 实施计划

1. 扩展 GitLab 规范与数据模型。
   - 新增连接模型、状态、SQLite Store 和项目 `connection_id` 兼容迁移。
   - 抽取共享 Host/IP 安全策略。
   - 增加连接存储与迁移测试。

2. 实现 GitLab REST API 客户端。
   - 验证 PAT 与当前账号。
   - 分页读取 membership 项目和单项目详情。
   - 增加超时、限流、鉴权失败和脱敏测试。

3. 实现账号与批量导入服务。
   - 连接 CRUD、验证、重新授权和停用。
   - 项目发现状态投影、默认配置与冲突检测。
   - 批量逐项目导入、一次性 Webhook Secret 和立即首次同步。
   - 增加部分成功、重复导入和失效连接测试。

4. 接入现有同步链路。
   - 新增统一凭据解析器。
   - 新项目从连接读取 PAT，旧项目保留项目密文回退。
   - 覆盖启动恢复、手动同步、重试和连接失效场景。

5. 改造 GitLab 管理前端。
   - 增加已导入项目/账号视图和账号关联表单。
   - 增加项目发现、分页搜索、多选和导入配置编辑。
   - 增加批量结果与一次性 Webhook 配置展示。
   - 保留现有项目详情、时间线、同步、重试和停用。

6. 同步配置、文档、规范和 Changelog。
   - 更新 `.trellis/spec/backend/gitlab-auto-onboarding.md`。
   - 更新 GitLab 用户指南与 `CHANGELOG.md`。
   - 不提交 `.env`、数据库、仓库缓存或个人文档。

7. 验证。
   - 定向 Store/API/Service/Controller/Page 测试。
   - 所有独立 JavaScript 文件执行 `node --check`。
   - JDK 21 `./mvnw -B verify`。
   - `git diff --check`。
   - 浏览器验证 GitLab.com 或白名单自建实例：关联账号、跨页发现、多选导入、部分失败、
     首次同步、敏感字段清理和桌面/移动布局。

8. 评审整改。
   - 仅在明确的 PAT 鉴权失败时将连接标记为 `INVALID`。
   - 远端身份改为 `(connectionId, remoteProjectId)`，兼容旧记录但不再跨实例按路径判重。
   - 批量导入并发读取项目详情，使用权限字段验证 membership，移除同步 Git 网络预检。
   - 项目注册改为 SQLite 原子插入，清理逻辑只撤销当前调用实际创建的状态。
   - 连接 PAT 在同步解析时强制绑定相同 Host/有效端口。
   - 项目搜索词下推 GitLab API，使超过 10,000 项的账号可以缩小发现范围。

## 风险文件

- `GitLabProjectStore.java`：SQLite 兼容迁移，不能破坏旧数据库。
- `GitLabSyncService.java`：队列、恢复和 DISABLED 终态语义必须保持。
- `GitLabGitClient.java`：Host/IP/PAT 安全边界不得放宽。
- `gitlab-app.js`：PAT 和一次性 Secret 必须在所有退出路径清空。

## 回滚点

- 新 API 和连接 Store 只在 GitLab 功能开关开启时注册。
- 项目凭据保留旧字段回退，允许回滚到逐仓库模式。
- 新表和新列可保留，不参与旧版本读写时不影响项目列表与同步。
