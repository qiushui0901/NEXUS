# 开发方案流式状态 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让开发方案的等待、生成、完成和中断状态拥有准确且一致的文字与指示灯颜色，并让 18080 运行最新后端代码。

**Architecture:** Vue 页面以独立 `planStatus` 保存状态语义，`planStage` 只负责文案；SSE 事件和请求异常统一写入这两个字段。服务端沿用已有“已产生有效分段则正常收尾”的容错逻辑，重新启动旧进程后生效。

**Tech Stack:** Vue 3 单页、Spring Boot 4、SSE、JUnit 5、AssertJ、Java 21。

## Global Constraints

- 状态值固定为 `idle`、`running`、`success`、`error`。
- 中断或失败必须使用红色指示灯，完成才使用绿色。
- 用户主动取消旧请求不得显示故障。
- 不增加图标、大警告面板或额外页面。

---

### Task 1: 前端状态模型与指示灯

**Files:**
- Modify: `src/main/resources/static/monitor.html`
- Test: `src/test/java/com/example/requirementrag/web/MonitorWorkbenchPageTest.java`

**Interfaces:**
- Consumes: SSE 事件的 `event.type`、`event.message` 和 `event.payload.message`。
- Produces: Vue 字段 `planStatus: 'idle' | 'running' | 'success' | 'error'`，以及对应的 `.idle/.running/.success/.error` CSS 类。

- [ ] **Step 1: 写失败的页面测试**

在 `MonitorWorkbenchPageTest` 中断言页面包含：

```java
.contains("planStatus:'idle'")
.contains(":class=\"planStatus\"")
.contains("this.planStatus = 'running'")
.contains("this.planStatus = 'success'")
.contains("this.planStatus = 'error'")
.contains(".plan-stage i.error")
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
JAVA_HOME=/Users/user/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home \
/Users/user/Documents/env/maven/apache-maven-3.9.16/bin/mvn -Dtest=MonitorWorkbenchPageTest test
```

Expected: FAIL，缺少 `planStatus` 或 `.error` 状态样式。

- [ ] **Step 3: 实现最小状态模型**

在 Vue data 中增加：

```js
planStatus:'idle',
```

状态点改为：

```html
<i :class="planStatus"></i>
```

状态映射：开始请求设置 `running`；`completed` 设置 `success`；SSE `error` 和 fetch/read 异常设置 `error`。CSS 中 `running` 使用蓝色动画，`success` 使用绿色，`error` 使用 `var(--red)` 和红色外圈，`idle` 使用灰色。

- [ ] **Step 4: 运行页面测试并确认通过**

运行 Step 2 命令。Expected: `Tests run: 1, Failures: 0, Errors: 0`。

### Task 2: Claude 生成参数兼容性

**Files:**
- Create: `src/main/java/com/example/requirementrag/service/GenerationChatOptions.java`
- Modify: `src/main/java/com/example/requirementrag/service/DevelopmentPlanStreamService.java`
- Modify: `src/main/java/com/example/requirementrag/service/DevelopmentPlanService.java`
- Test: `src/test/java/com/example/requirementrag/service/GenerationChatOptionsTest.java`

**Interfaces:**
- Produces: `GenerationChatOptions.forModel(String): OpenAiChatOptions`，只设置 model，不设置 temperature。

- [ ] **Step 1: 写模型兼容性失败测试**

断言 `GenerationChatOptions.forModel("claude-sonnet-5").getTemperature()` 为 `null`，且 model 保持不变。先运行并确认类不存在导致测试失败，再实现工厂并替换开发方案两处 options 构建。

- [ ] **Step 2: 运行模型兼容性测试**

Run: `mvn -Dtest=GenerationChatOptionsTest test`。Expected: `Tests run: 1, Failures: 0, Errors: 0`。

### Task 3: 运行态与端到端验证

**Files:**
- Verify: `src/main/java/com/example/requirementrag/service/DevelopmentPlanStreamService.java`
- Verify: `src/main/resources/static/monitor.html`

**Interfaces:**
- Consumes: 18080 端口和 `/api/assistant/development-plan/stream`。
- Produces: 加载最新代码的本地 NEXUS 服务。

- [ ] **Step 1: 停止旧进程并启动最新服务**

确认 18080 监听进程是旧的 request-RAG Java 进程后停止它，再运行：

```bash
JAVA_HOME=/Users/user/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home \
/Users/user/Documents/env/maven/apache-maven-3.9.16/bin/mvn spring-boot:run \
-Dspring-boot.run.arguments=--server.port=18080
```

Expected: 日志包含 `Tomcat started on port 18080`。

- [ ] **Step 2: 浏览器验证状态**

打开 `/monitor.html`：生成期间状态类为 `running`；收到完成事件后为 `success`；模拟或收到错误事件后为 `error`，红色状态点可见。

- [ ] **Step 3: 运行完整测试**

Run:

```bash
JAVA_HOME=/Users/user/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home \
/Users/user/Documents/env/maven/apache-maven-3.9.16/bin/mvn test
```

Expected: BUILD SUCCESS，Failures 和 Errors 均为 0。
