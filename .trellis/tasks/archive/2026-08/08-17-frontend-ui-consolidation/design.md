# 技术设计

## 1. 改造策略

采用“共享基础设施先行、页面渐进迁移、业务逻辑不重写”的策略：

```text
design tokens / shell / components / error normalizer
  -> knowledge + GitLab
  -> home + Wiki
  -> monitor shell and control layout
  -> responsive and accessibility verification
```

静态页面继续由 Spring Boot 提供，Vue 页面继续使用 WebJar。共享模块使用浏览器原生脚本和全局 `window.Nexus*` 命名空间，避免引入模块打包和部署变化。

## 2. 共享资产

新增或整理：

```text
static/assets/
  design-tokens.css
  app-shell.css
  components.css
  responsive.css
  app-shell.js
  api-client.js
  error-normalizer.js
  status-contract.js
```

- `design-tokens.css`：颜色、字号、间距、尺寸、边框、圆角、阴影、z-index、代码画布变量。
- `app-shell.css/js`：品牌、一级导航、上下文栏、移动菜单、系统状态和连接设置弹层。
- `components.css`：按钮、图标按钮、输入、状态标签、统计栏、表格/移动列表、菜单、弹窗、抽屉、通知、空/错/加载状态。
- `responsive.css`：1200px、768px 两个断点及通用触控、列表和抽屉策略。
- `api-client.js`：统一 API Key 读取、请求头、JSON/文本解析、超时和 correlation ID 投影。
- `error-normalizer.js`：将任意响应/异常投影为 `{code,message,action,correlationId,detail}`，detail 只保留安全摘要。
- `status-contract.js`：稳定状态词汇、图标、色彩语义和显示文案。

## 3. 应用外壳契约

每页提供：

```html
<div data-nexus-shell data-page="knowledge"></div>
```

`app-shell.js` 根据 `data-page` 渲染统一头部，并从 URL 读取 `projectId`、`version`。导航链接通过白名单复制上下文参数；GitLab 设置等不适用版本的页面只保留项目参数。

移动菜单使用按钮、抽屉、遮罩和 Escape 关闭，支持键盘焦点。连接设置复用现有 `localStorage.nexusApiKey`，保存后广播 `nexus:connection-changed` 事件，页面可选择刷新。

## 4. 错误与请求边界

请求数据流：

```text
page action
  -> NexusApi.request
  -> fetch
  -> parse JSON or safe text
  -> NexusErrors.normalize
  -> page state / NexusNotice
```

共享请求头合并必须按 HTTP Header 大小写不敏感语义去重；调用方传入
`content-type` 时不得再次补充 `Content-Type`，避免浏览器合并为无效的重复媒体类型。

规范化器禁止输出：

- `<!DOCTYPE` 或完整 HTML 标签；
- Java/JavaScript 堆栈；
- `/Users/...`、Windows 盘符等绝对路径；
- shell 命令或后端内部异常类名；
- Token、Secret、Authorization 值。

已有页面业务函数可先通过兼容包装调用共享请求层，不要求一次重写全部数据状态。初始化错误留在内容区，通知仅用于用户触发动作。

## 5. 页面迁移

### Home

保留 `/api/runtime/status`，将内容重组为统计栏、待处理事项、快速操作和项目状态。接口不可用时显示可操作错误状态，不阻塞导航。

### Knowledge / GitLab

复用现有 Vue 状态与 API 文件，替换页面外壳和通用样式。桌面表格通过共享类统一，移动端利用同一数据渲染 `.mobile-record-list`，避免横向表格。

### Wiki / Legacy Versions

保留 Wiki 现有原生 JavaScript 和 API 调用顺序，迁移外壳、通知、连接设置与布局，证据栏通过 CSS/少量状态控制转换为抽屉。`/versions` 与比较 API 仅做兼容保留，不再参与核心导航和产品主流程。

### Monitor

第一阶段不改核心图谱/方案算法，只：

1. 接入共享外壳、Token 和错误规范化；
2. 将控制区、图画布和信息栏统一为浅色工作区，图谱只通过网格、节点和连线语义色建立层级；
3. 重组现有 mode/search/filter DOM 的视觉分组；
4. 增加移动 Tab 状态和 CSS，使图与侧栏互斥；
5. 将可独立的外壳/错误代码移到共享资产。

现有字符串契约测试继续约束图谱、SSE、源码抽屉和证据能力。

## 6. 兼容与安全

- 页面路由和后端 Controller 不变。
- 原有 `nexusApiKey` 键继续可用，不迁移或复制凭据到 URL。
- PAT、Webhook Secret 继续只保存在页面内存，不进入共享连接设置。
- 共享错误模块只改变展示，不吞掉页面内部状态分类。
- 业务页面可逐步迁移；共享资产失败时不会改变后端行为。
- 监控与首页统计属于只读旁路：不得调用会创建 collection 或执行长退避的存储方法。
  Qdrant 不可用时快速返回降级统计，前端在上一轮刷新结束后再安排下一轮；依赖 DOWN 时
  轮询间隔为 15 秒。

## 7. 版本发布契约

- 产品发布版本的单一主值来自 `pom.xml`，本任务更新为 `0.9.0`。
- `application.yml` 的 Spring 应用版本、`README.md` 当前版本和 Changelog 发布标题必须同步。
- GitLab 与知识管理工作台不作为独立版本发布，而是随统一应用外壳一起进入 `0.9.0`。
- 评测数据集、阈值文件和历史验证报告中的 `0.8.6` 属于可追溯基线标识，保持不变。

## 8. 测试

- 新增共享资产契约测试，验证五个核心页面加载顺序、完整导航、移动菜单、统一连接设置和错误清理规则。
- 扩展现有页面测试，保留 API 路径、关键状态与无 CDN/no `v-html` 约束。
- 对共享 JavaScript 和页面脚本运行 `node --check`。
- 使用 1440x900、1280x720、390x844 验证八个目标页面/状态，无水平溢出、标题遮挡或控制重叠。
- 完整运行 JDK 21 Maven verify 和 JaCoCo 门禁。
- 验证构建产物名为 `NEXUS-0.9.0.jar`，页面和应用元信息不存在仍指向当前产品版本的 `0.8.6`。

## 9. 回滚

共享资源以新增文件为主。若某页迁移出现回归，可保留共享 Token/错误处理，仅回退该页 DOM/CSS 布局；后端 API 和业务链路不受影响。`monitor.html` 以分块改造为回滚边界，避免整文件替换。
