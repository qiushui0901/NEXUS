# Implementation Plan

## 1. Shared evidence contract

- [x] 新增证据类型、支持状态、统一 `EvidenceRef`、`CitedText`、引用质量与方案引用包。
- [x] 实现 request-scoped `EvidenceRegistry`：稳定 ID、白名单、安全路径、受限摘录。
- [x] 实现统一引用校验与质量统计服务，warning 去重。
- [x] 添加证据注册与校验单元测试。

## 2. Synchronous development plan

- [x] 扩展模型草稿契约和 prompt，使每个生成结论携带 `evidenceIds`。
- [x] 将证据 ID 写入需求/代码 prompt 上下文。
- [x] 保留现有响应字段并追加 `PlanCitationBundle`。
- [x] 缺失或非法引用时降级响应，保留现有检索 warning、diagnostic 和 conflict report。
- [x] 更新同步服务测试与兼容构造测试。

## 3. SSE development plan

- [x] 扩展流式 prompt 的引用格式约束和事件类型白名单。
- [x] 在事件发送前统一清洗 `evidenceIds`，附加 `supportStatus`。
- [x] `references` 事件追加统一证据列表，`completed` 追加引用质量和 warning。
- [x] 保留旧 `documents`、`code`、事件名称与失败语义。
- [x] 更新 parser、stream service 与 controller SSE 测试。

## 4. Workbench UI

- [x] 扩展前端 plan state 和 SSE reducer，兼容字符串/引用对象两种条目。
- [x] 展示引用质量、支持状态和可点击证据标签。
- [x] 需求证据打开摘录抽屉；代码证据复用源码抽屉。
- [x] 更新静态页面契约测试，确认没有不安全 HTML 插入。

## 5. Documentation and verification

- [x] 更新 README/CHANGELOG 和相关后端规范。
- [x] 使用通用虚构数据运行聚焦测试。
- [x] 使用 JDK 21 运行 `./mvnw -B verify`。
- [x] 运行 `git diff --check`。
- [x] 检查 Git 变更中无真实项目内容、需求快照、向量数据、绝对私有路径或凭据。

## Risk and rollback points

- 修改 `DevelopmentPlanResponse` 时保留当前 17/18 参数兼容构造器。
- 修改模型草稿结构后，模型返回旧格式可能反序列化失败；服务必须安全回退并标记降级。
- SSE 引用校验不得把检索 warning 覆盖，也不得把部分模型流误报为成功。
- 页面 reducer 是跨层契约边界，所有 payload 规范化集中在一处，避免模板散落字段转换。


## Verification

- JDK 21 `./mvnw -B verify`: 145 tests, 0 failures, 0 errors, 0 skipped.
- `git diff --check`: passed.
- `monitor.html` inline JavaScript extracted and checked with `node --check`: passed.
- Repository hygiene scan: no real project content, requirement snapshots, vector data, absolute private paths, or credentials in the change set.
