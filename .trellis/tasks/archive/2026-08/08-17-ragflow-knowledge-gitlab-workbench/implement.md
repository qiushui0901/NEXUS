# 实施计划

## 1. 状态基础设施

- [x] 增加知识管理配置和功能开关。
- [x] 定义状态、阶段、分页和错误 DTO。
- [x] 实现 SQLite schema、Repository、启动中断恢复和错误脱敏。
- [x] 增加 Repository 单元测试。

## 2. 导入链路

- [x] 定义 `KnowledgeIngestionTracker` 及容错实现。
- [x] 接入 `KnowledgeBootstrapService`。
- [x] 接入 `RequirementIngestionService` 的逐文档阶段和分块状态。
- [x] 为 `QdrantHybridStore` 增加向后兼容的批次进度回调。
- [x] 验证失败时旧索引和已发布 revision 保留。

## 3. 知识 API

- [x] 实现知识库、run、document、chunk 分页查询和详情。
- [x] 实现重建与文档级重试。
- [x] 实现复用现有链路的检索测试。
- [x] 增加权限、项目隔离和错误契约测试。

## 4. 知识前端

- [x] 增加页面路由和功能开关。
- [x] 实现概览、文档列表、详情、阶段轨道、分块抽屉和检索测试。
- [x] 实现分页、筛选、URL 状态和自适应轮询。
- [x] 更新首页、Wiki、监控页深链。
- [x] 增加静态资源契约测试。

## 5. GitLab API 与状态

- [x] 增加连接、项目和配置校验 API。
- [x] 增加 job/event 持久化及同步阶段埋点。
- [x] 扩展项目视图和 revision drift。
- [x] 增加 Webhook 状态和 Secret 轮换。
- [x] 增加安全、重启和兼容测试。

## 6. GitLab 前端

- [x] 实现项目列表和项目详情。
- [x] 实现五步接入向导和即时校验。
- [x] 实现同步、重试、停用、Webhook 状态和任务时间线。
- [x] 验证敏感字段不进入 URL、存储和日志。

## 7. 文档与验证

- [x] 更新 `.env.example`、`application.yml`、用户/接入/冒烟文档。
- [x] 更新 `CHANGELOG.md` 当前版本。
- [x] 运行定向测试：`./mvnw -Dtest='*KnowledgeManagement*,*GitLab*' test`。
- [x] 运行完整验证：`./mvnw verify`。
- [x] 运行 `git diff --check`。
- [x] 启动本地服务并完成三个目标视口的浏览器验收。

## 风险与回滚点

- SQLite schema 只允许幂等追加，不执行破坏性迁移。
- Qdrant 公开方法保留原签名，新回调使用重载。
- GitLab 现有 Controller 路径与响应字段保持兼容，只追加字段和端点。
- 每完成一层先运行定向测试；出现回归时关闭对应功能开关，不修改原检索读路径。

## Review 修复

- [x] 发布成功时按当前 run 快照清理旧文档和旧分块状态。
- [x] GitLab 项目停用时终结运行中与排队的持久化同步任务。
- [x] Qdrant 中间批次记录 RUNNING 且不把未处理数量计为排除。
