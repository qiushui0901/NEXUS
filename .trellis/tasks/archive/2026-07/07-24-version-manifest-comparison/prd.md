# 版本档案与多来源差异分析

## Goal

为 NEXUS 建立独立于向量库的版本档案，并提供需求、代码、测试和 Wiki 四类来源的结构化版本差异，使产品、开发和测试可以基于同一版本基线浏览与核对变化。

## Requirements

### Version manifest

- 每个项目、每个业务版本保存一份 JSON 档案，关联需求文档版本、代码 commit、测试快照和 Wiki 版本。
- 平台版本与业务版本严格分离；业务版本只作为档案标识和来源引用。
- 提供保存、读取、列表能力，更新时保留 `createdAt` 并刷新 `updatedAt`。
- 使用安全标识和规范化路径防止路径穿越，并采用临时文件加原子移动写入。
- 档案不得包含向量、embedding、Qdrant point/snapshot/storage 或凭据。
- Git commit 必须是具体 SHA，不能把用户输入作为任意命令参数执行。

### Multi-source comparison

- 输入项目、起始版本和目标版本，读取两份 manifest 并生成结构化比较报告。
- 需求差异复用 Qdrant 的 payload-only 版本滚动读取，不读取向量；输出新增、修改、删除的父块及有限摘录。
- 代码差异复用统一 Git diff 组件，输出新增、修改、删除、重命名文件和分类统计。
- 测试差异只比较 manifest 中记录的真实测试快照；缺失时明确标记不可用，不把测试建议伪装为执行结果。
- Wiki 差异比较两个版本索引中的页面增删、状态、摘要和证据数量变化；任一 Wiki 缺失时返回 warning，不中断其他来源比较。
- 单一非关键来源不可用时允许生成降级报告；manifest 不存在或输入非法时返回明确、无敏感信息的公开错误。

### API and access control

- 提供 manifest 保存、列表、读取和版本比较 API。
- 保存要求 `WRITE` 权限；读取和比较要求 `PUBLIC_READ` 权限。
- 所有 API 都校验项目存在性和当前用户项目访问权。

## Acceptance Criteria

- [x] manifest 可安全保存、更新、读取和按版本排序列出，路径穿越和非法 commit 被拒绝。
- [x] manifest JSON 不包含向量库数据或凭据，写入为原子替换。
- [x] 需求比较正确识别新增、修改、删除，且 Qdrant 请求不包含向量。
- [x] Git 文件比较正确处理新增、修改、删除和重命名，并被增量代码索引与版本比较共同复用。
- [x] 测试快照比较能识别汇总变化、用例增删和状态变化；缺失快照明确为不可用。
- [x] Wiki 页面比较能识别增删和内容元数据变化；缺失 Wiki 只产生 warning。
- [x] API 权限、项目访问、输入校验和主要响应均有回归测试。
- [x] README、CHANGELOG、配置示例和平台版本记录完成。
- [x] Java 21 完整 `verify`、`git diff --check` 和敏感/向量数据检查通过。

## Out of Scope

- 本次不实现符号级 Git/AST 差异，只提供可靠的文件级差异。
- 本次不自动执行测试，也不推断测试通过状态。
- 本次不自动发布 Wiki 或覆盖现有 Wiki 源文件。
- 本次不提交 Qdrant 数据、模型缓存、本地 IDE 或工具索引目录。
