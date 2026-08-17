# 技术设计

## 1. 架构边界

新增 `knowledge.management` 包作为知识运行状态的唯一所有者：

```text
来源文件/GitLab
  -> KnowledgeBootstrapService / RequirementIngestionService
  -> KnowledgeIngestionTracker
  -> SQLiteKnowledgeManagementStore
  -> KnowledgeManagementController
  -> knowledge.html
```

Qdrant 继续拥有可检索正文和向量，SQLite 只保存管理元数据。`BootstrapState` 保留为兼容适配器，`MonitorController` 现有响应不变。

GitLab 状态扩展保持在 `integration.gitlab` 包：

```text
GitLabIntegrationController
  -> GitLabValidationService / GitLabSyncService
  -> GitLabProjectStore + GitLabJobStore
  -> gitlab-settings.html
```

## 2. 知识状态模型

SQLite 建立：

- `knowledge_base`
- `knowledge_ingestion_run`
- `knowledge_document`
- `knowledge_chunk_status`
- `knowledge_stage_event`

稳定标识：

- knowledge base：`projectId:type`
- document：`sha256(knowledgeBaseId + ":" + normalizedSourcePath)`
- chunk：复用 `ChunkRecord.id`
- run/event：UUID

数据库写操作使用短事务；列表查询使用稳定排序和 `limit/offset`。错误消息先通过统一 sanitizer 截断并去除凭据形态。

## 3. 导入数据流

1. Bootstrap 解析项目配置并 `ensureKnowledgeBase`。
2. Tracker 创建 run，扫描来源时 upsert document。
3. 每个 entry 依次记录 `CLEAN`、`CHUNK`、`DEDUPLICATE`。
4. 分块生成后批量保存 `CHUNKED`。
5. Qdrant 批次回调更新 `EMBED/INDEX` 进度。
6. Qdrant 验证成功后标记分块 `READY`。
7. 清理旧点后发布 run 和 knowledge base revision。
8. 任一步失败时记录稳定错误码；旧 `publishedRevision` 保留。

状态目录只在 Qdrant 发布成功后把当前 `runId` 作为完整快照：事务内删除该知识库中未被
当前 run 触达的旧文档和旧分块，再同时发布 run 与 knowledge base，避免 UI 展示已从
Qdrant 删除的记录。

Tracker 提供 no-op/容错边界。状态存储异常只记录日志，不替代业务异常。

## 4. API 契约

知识 API 使用 `/api/knowledge-bases`，避免与现有 `/api/knowledge` 草稿 API 冲突。分页响应统一为：

```json
{"items":[],"page":0,"size":50,"total":0}
```

详情响应返回管理字段，不返回原始向量。分块详情正文优先从 Qdrant 已发布 payload 获取。

重试动作通过 `KnowledgeBootstrapService.bootstrap(projectId)` 重新构建项目；文档/分块重试首版映射为所属知识库重建，并在响应中明确 `DOCUMENT_REBUILD`。

检索测试适配现有检索服务，只做 DTO 投影，不复制检索算法。

## 5. GitLab 扩展

- `GitLabGitClient` 增加只读远端校验能力，正式 clone 和预检共用 URL/Host/凭据注入规则。
- 新增 job/event 表；`GitLabSyncService` 在 enqueue 和每个阶段写事件。
- 项目视图派生 `syncAvailable`、`indexAvailable`、`revisionDrift`，不改变原状态枚举。
- Webhook Controller 在验证后记录接收结果，不保存请求正文。
- Secret 轮换生成新随机值、加密保存并只返回一次。

## 6. 前端

继续使用 Vue 3 WebJar和原生 `fetch`：

- `knowledge.html`
- `gitlab-settings.html`
- `assets/knowledge-app.css`
- `assets/knowledge-api.js`
- `assets/knowledge-app.js`
- `assets/gitlab-app.js`
- `assets/status-contract.js`
- `assets/icons.js`

URL 保存当前资源和筛选条件；敏感字段只存在 Vue 组件内存。轮询由一个共享调度器管理，页面隐藏时停止。

## 7. 安全与兼容

- Controller 继续使用 `@RequiresPermission` 和 `ProjectAccessGuard`。
- GitLab 管理操作要求 `SUPER_ADMIN`。
- 所有外部错误只返回稳定错误码、脱敏摘要和 correlation ID。
- 功能开关关闭时页面路由返回 404，后台原有链路继续运行。
- 新数据库是旁路元数据，可通过关闭开关回滚。

## 8. 测试与回滚

- Repository 使用临时 SQLite 文件验证 schema、事务、分页和重启恢复。
- Service 测试覆盖成功、失败、旧索引保留和重试映射。
- Controller 测试覆盖权限、分页、404/409/422 和 Secret 脱敏。
- 静态契约测试禁止 CDN、`v-html` 和敏感字段持久化。
- 浏览器验证 1440x900、1280x720 和 390x844。

回滚时关闭两个 UI 开关；新状态库不参与检索读路径，无需删除即可恢复旧行为。
