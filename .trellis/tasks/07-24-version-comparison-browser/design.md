# Design: 版本中心与差异浏览页面

## 1. Structure

新增：

```text
src/main/resources/static/versions.html
src/main/java/com/example/requirementrag/web/VersionPageController.java
src/test/java/com/example/requirementrag/web/VersionKnowledgePageTest.java
```

修改：

```text
src/main/resources/static/wiki.html
src/test/java/com/example/requirementrag/web/WikiKnowledgePageTest.java
README.md
CHANGELOG.md
```

## 2. Data flow

```text
GET /api/wiki/projects
  -> 可访问项目列表
GET /api/versions/manifests?projectId=...
  -> 版本时间线和选择器
GET /api/versions/compare?projectId=...&fromVersion=...&toVersion=...
  -> 四类差异与 warnings
```

项目列表继续复用 `/api/wiki/projects` 的访问过滤，避免新增重复的项目枚举 API。manifest 列表用于版本选择；即使某版本 Wiki 尚未发布，也可以出现在版本中心。

## 3. UI state

页面维护：

```text
projects
manifests
projectId
fromVersion
toVersion
report
activeTab
loading/error/notice
```

默认版本选择：

1. `toVersion` 取 manifest 列表第一项（服务端已按版本倒序）。
2. 优先取目标 manifest 的 `baseVersion` 作为 `fromVersion`。
3. 否则取第二个不同版本。
4. 少于两个版本时禁用比较。

## 4. Rendering

- 顶部：品牌、Wiki/版本中心导航、API Key。
- 左侧：项目、起止版本和版本时间线。
- 中部：报告摘要、warnings、四页签和差异明细。
- 需求修改项并列展示 before/after excerpt。
- 代码重命名显示 `oldPath → newPath`。
- 测试状态变化显示 `beforeStatus → afterStatus`。
- Wiki 项带“在 Wiki 中查看”链接；URL 使用 `/wiki?projectId=...&version=...&featureId=...`。

## 5. Wiki deep link

扩展现有 `wiki.html`：

- 初始化时读取 URL 查询参数 `projectId`、`version`、`featureId`。
- 项目和版本存在时优先选择参数指定值。
- index 加载完成后优先打开参数指定的 featureId。
- 参数非法或目标不存在时退回原有默认选择，不破坏浏览。

## 6. Testing

使用与 `WikiKnowledgePageTest`、`MonitorWorkbenchPageTest` 一致的静态资源契约测试：

- 静态页面存在且包含关键 API 路径。
- 包含四类页签、转义函数、API Key 和降级文案。
- `/versions` 控制器重定向正确。
- Wiki 页面包含版本中心导航和 deep-link 参数消费。

API 正确性由现有 `VersionControllerTest` 和服务测试覆盖，本任务不复制后端比较测试。
