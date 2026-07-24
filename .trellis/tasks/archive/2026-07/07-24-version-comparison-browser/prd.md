# 版本中心与差异浏览页面

## Goal

把已经完成的版本档案与多来源差异 API 转化为产品、开发和测试可以直接使用的版本中心页面，让用户能够选择项目和两个业务版本，并浏览需求、代码、测试、Wiki 四类结构化差异。

## Background

- NEXUS 已提供 `/api/versions/manifests`、`/api/versions/manifests/{version}` 和 `/api/versions/compare`。
- 现有 `/wiki` 页面只能浏览单个已生成版本，尚未调用版本档案和比较 API。
- 当前页面采用随 Spring Boot 打包的原生 HTML/CSS/JavaScript；本任务保持该架构，不引入新的前端框架和构建链。
- 差异服务允许需求、代码、测试或 Wiki 单项不可用，并通过 availability 与 warnings 返回降级结果。

## Requirements

### Page and navigation

- 新增 `/versions` 人类可读页面并重定向到静态版本中心资源。
- Wiki 页面和版本中心页面提供相互跳转入口。
- 页面沿用 `X-API-Key`，并与现有页面共用 `localStorage.nexusApiKey`。

### Version selection and timeline

- 从当前用户可访问的 Wiki 项目和版本 manifest 中加载项目及版本。
- 展示版本号、状态、基准版本、需求版本、代码 commit、Wiki 版本和更新时间。
- 默认选择最新版本为目标版本、其次版本或目标 manifest 的 `baseVersion` 为起始版本。
- 禁止相同版本对比；不足两个版本时给出明确空状态而不是发起非法请求。

### Multi-source comparison

- 调用 `/api/versions/compare`，展示报告生成时间及公开 warnings。
- 需求页签展示可用性、增改删统计、文件、父块位置以及前后摘录。
- 代码页签展示可用性、文件统计、Java/测试/配置分类以及新增、修改、删除、重命名列表。
- 测试页签展示真实快照状态和数量变化、用例增删与状态变化；不可用时明确说明没有真实执行快照。
- Wiki 页签展示页面增改删、状态、摘要变化和证据数量变化，并可跳转到 `/wiki` 的对应项目、版本和 featureId。
- 单一来源不可用或有 warning 时，其他来源仍正常呈现。

### Safety and compatibility

- 所有来自 API 的文本在插入 HTML 前必须转义。
- 不把内部异常、绝对路径、凭据或向量数据写入页面。
- 不改变现有 `/api/versions` 与 `/api/wiki` 合同。
- 页面在窄屏下可用，空数据、加载、错误和降级状态均有清晰反馈。

## Acceptance Criteria

- [ ] `/versions` 可访问并可在 Wiki 页面与版本中心之间导航。
- [ ] 页面能加载可访问项目及 manifest，形成版本时间线并合理默认选择起止版本。
- [ ] 页面能调用比较 API，并以四个页签展示需求、代码、测试、Wiki 差异。
- [ ] 测试快照或 Wiki 缺失时显示不可用/警告，不导致整个页面失败。
- [ ] Wiki 差异项可带项目、版本、featureId 跳转到 Wiki 页面并自动定位页面。
- [ ] API 文本经过 HTML 转义，API Key 延续现有本地保存方式。
- [ ] 增加页面路由与静态页面契约测试，现有回归测试继续通过。
- [ ] README 与 CHANGELOG 记录版本中心入口和能力。
- [ ] Java 21 `./mvnw -B verify` 与 `git diff --check` 通过。

## Out of Scope

- 本任务不实现一键版本构建、草稿审核或自动发布 Wiki。
- 不新增、编辑或删除 VersionManifest。
- 不实现符号级代码差异或可视化调用图。
- 不引入 React、Vue、Node.js 或单独前端工程。
- 不提交向量库、embedding、Qdrant storage、模型缓存或凭据。
