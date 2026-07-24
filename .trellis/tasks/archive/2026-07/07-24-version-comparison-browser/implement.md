# Implementation Plan: 版本中心与差异浏览页面

## Phase A — Page contract

- [x] 新增 `VersionPageController` 和 `/versions` 路由。
- [x] 新增静态 `versions.html`，完成页面骨架、API Key 和导航。
- [x] 加载项目、manifest 并实现默认起止版本选择。

## Phase B — Comparison rendering

- [x] 调用 compare API，渲染报告头、warnings 和四页签统计。
- [x] 实现需求、代码、测试、Wiki 明细与不可用状态。
- [x] 为所有 API 文本使用统一 HTML 转义。
- [x] 实现窄屏布局、加载、空状态和错误反馈。

## Phase C — Wiki integration

- [x] Wiki 页面增加版本中心入口。
- [x] Wiki 页面支持 projectId/version/featureId deep link。
- [x] Wiki 差异页面支持跳转目标版本具体页面。

## Phase D — Verification and docs

- [x] 新增 `VersionKnowledgePageTest`。
- [x] 更新 `WikiKnowledgePageTest`。
- [x] 更新 README、CHANGELOG 和平台版本号；未形成新的跨任务规范。
- [x] 使用 Java 21 运行 `./mvnw -B verify`。
- [x] 运行 `git diff --check` 并检查无本地数据、凭据和向量库数据。

## Rollback points

- 页面是新增静态资源，失败时可独立移除 `versions.html` 与路由，不影响版本 API。
- Wiki deep link 修改必须保持无查询参数时的原行为。
- 不修改版本 API 模型，避免前后端合同迁移。
