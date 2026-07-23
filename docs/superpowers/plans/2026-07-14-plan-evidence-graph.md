# 开发方案证据绑定与链路图 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复开发方案源码跳页和环节代码未命中问题，把产品规则、真实代码证据与建议改动绑定到具体环节，并加入“现有代码 + 规划节点”的完整链路图。

**Architecture:** 新建独立的 `PlanSectionEvidenceMatcher`，只在本次 Qdrant 真实代码结果内为流式 section 事件匹配代码并补充关系说明。`DevelopmentPlanStreamService` 负责协议增强，Vue 页面负责环节卡片、组合链路图和源码抽屉交互，代码理解工作台状态保持独立。

**Tech Stack:** Java 21、Spring Boot、Spring AI、Jackson、JUnit 5、AssertJ、Vue 3 CDN、原生 SVG。

## Global Constraints

- 真实代码节点只能来自 Qdrant 返回的 `CodeChunk`。
- 规划节点必须与真实节点使用不同样式，规划边必须是虚线。
- `loadSource()` 不得改变 `activeTab` 或 `intentTab`。
- 只有本次代码结果整体为空时才能显示代码未命中。
- 开发方案正文统一为 12px，环节标题 13px，辅助信息 10px。
- 不修改 Qdrant 索引结构，不合并开发方案页和代码理解页。

---

### Task 1: 锁定页面交互和信息架构

**Files:**
- Modify: `src/test/java/com/example/requirementrag/web/MonitorWorkbenchPageTest.java`
- Modify: `src/main/resources/static/monitor.html`

**Interfaces:**
- Consumes: 当前单文件 Vue 页面和 `loadSource(nodeOrHit)`。
- Produces: 不跳页的源码查看、环节规则/代码区域、方案链路图容器。

- [ ] **Step 1: Write the failing page assertions**

增加断言，要求页面包含 `relatedRules`、`inspectTargets` 的关系说明、`plan-chain-graph`、规划边样式和源码抽屉；提取 `loadSource` 方法文本并断言其中不包含 `this.activeTab = 'graph'` 或 `this.intentTab = 'graph'`。

- [ ] **Step 2: Run the focused test to verify RED**

Run:

```bash
JAVA_HOME=/Users/user/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home /Users/user/Documents/env/maven/apache-maven-3.9.16/bin/mvn -Dtest=MonitorWorkbenchPageTest test
```

Expected: FAIL because the plan graph and relationship UI are absent and `loadSource()` still switches tabs.

- [ ] **Step 3: Implement the minimum interaction shell**

在 `monitor.html` 中移除 `loadSource()` 的两个工作区切换赋值；加入计划图谱的模板、状态字段和基础样式；把右侧孤立的代码/规则卡片移除，把 `relatedRules`、`hit.relation` 和 `hit.matchType` 放入环节卡片。

- [ ] **Step 4: Run the focused test to verify GREEN**

运行相同命令，Expected: PASS。

### Task 2: 为流式环节绑定真实代码证据

**Files:**
- Create: `src/main/java/com/example/requirementrag/service/PlanSectionEvidenceMatcher.java`
- Create: `src/test/java/com/example/requirementrag/service/PlanSectionEvidenceMatcherTest.java`
- Modify: `src/main/java/com/example/requirementrag/service/DevelopmentPlanStreamService.java`
- Modify: `src/test/java/com/example/requirementrag/service/DevelopmentPlanStreamServiceTest.java`

**Interfaces:**
- Consumes: `JsonNode sectionPayload`、`List<CodeChunk>`。
- Produces: `ObjectNode enrich(JsonNode payload, List<CodeChunk> code)`，在 payload 中加入 `inspectTargets`；每个目标包含原始 `CodeChunk` 字段、`relation` 和 `matchType`。

- [ ] **Step 1: Write matcher tests first**

覆盖以下行为：配置环节优先匹配 Config 类；领取环节优先匹配 reward/claim 方法；没有强匹配但代码非空时返回最多两个 `recommended`；代码列表为空时返回空数组；所有返回 ID 必须来自输入列表。

- [ ] **Step 2: Run matcher tests to verify RED**

Run:

```bash
JAVA_HOME=/Users/user/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home /Users/user/Documents/env/maven/apache-maven-3.9.16/bin/mvn -Dtest=PlanSectionEvidenceMatcherTest test
```

Expected: compilation failure because `PlanSectionEvidenceMatcher` does not exist.

- [ ] **Step 3: Implement matcher**

实现规范化分词、业务同义词扩展、符号/路径/正文加权、关系说明和弱匹配回退。强匹配最多四个，推荐回退最多两个。不要访问 Qdrant，也不要生成新的代码 ID。

- [ ] **Step 4: Run matcher tests to verify GREEN**

运行相同命令，Expected: PASS。

- [ ] **Step 5: Write stream enrichment test first**

为 `DevelopmentPlanStreamService` 增加可直接测试的 `enrichSectionEvent(event, code)`，断言 section 事件获得真实 `inspectTargets`，非 section 事件保持原样。

- [ ] **Step 6: Run stream service test to verify RED**

Run:

```bash
JAVA_HOME=/Users/user/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home /Users/user/Documents/env/maven/apache-maven-3.9.16/bin/mvn -Dtest=DevelopmentPlanStreamServiceTest test
```

Expected: FAIL because enrichment method and matcher dependency are absent.

- [ ] **Step 7: Integrate matcher into streaming**

构造器注入 `PlanSectionEvidenceMatcher`。模型解析出 section 后先增强再发送。更新系统提示：section payload 必须包含 `relatedRules` 与 `plannedNodes`，但明确禁止模型输出真实文件路径和真实代码节点 ID。

- [ ] **Step 8: Run stream tests to verify GREEN**

运行相同命令，Expected: PASS。

### Task 3: 构建组合链路图和环节定位

**Files:**
- Modify: `src/main/resources/static/monitor.html`
- Modify: `src/test/java/com/example/requirementrag/web/MonitorWorkbenchPageTest.java`

**Interfaces:**
- Consumes: `/api/code/graph` 返回的真实 nodes/edges；section 的 `plannedNodes` 与 `inspectTargets`。
- Produces: `planGraph`、`planGraphNodes`、`planGraphEdges`、`loadPlanGraph()`、缩放/适配/全屏方法和规划节点定位方法。

- [ ] **Step 1: Add failing plan graph assertions**

断言存在 `/api/code/graph` 的独立方案请求、`planGraphNodes`、`planGraphEdges`、`planned` 节点类型、虚线 `.plan-edge.planned`、适配和全屏按钮，以及规划节点点击定位环节的方法。

- [ ] **Step 2: Run the page test to verify RED**

运行 `MonitorWorkbenchPageTest`，Expected: FAIL on the missing graph behavior.

- [ ] **Step 3: Implement graph state and rendering**

在开发方案流启动时清空 `planGraph`；收到 references 或 completed 后调用 `loadPlanGraph()`。将真实图谱节点限制在与 section targets 相关的子图，再合并规划节点。使用蛇形分层布局，初始留白 15%，支持拖动、滚轮缩放、适配和全屏；真实边实线，规划边虚线。真实节点调用 `loadSource()`，规划节点滚动到对应 section。

- [ ] **Step 4: Run the page test to verify GREEN**

运行 `MonitorWorkbenchPageTest`，Expected: PASS。

### Task 4: 统一开发方案视觉层级

**Files:**
- Modify: `src/main/resources/static/monitor.html`
- Modify: `src/test/java/com/example/requirementrag/web/MonitorWorkbenchPageTest.java`

**Interfaces:**
- Consumes: 新的环节卡片和链路图结构。
- Produces: `.plan-view` 范围内统一的字体变量和响应式布局。

- [ ] **Step 1: Add failing typography assertions**

断言存在 `--plan-body-size:12px`、`--plan-heading-size:13px`、`--plan-meta-size:10px`，并断言 `.plan-view` 的正文、列表、代码卡片统一继承这些变量。

- [ ] **Step 2: Run the page test to verify RED**

运行 `MonitorWorkbenchPageTest`，Expected: FAIL on missing typography tokens.

- [ ] **Step 3: Apply the compact visual system**

把产品规则与开发约束改成紧凑概览；环节卡片按目的、关联规则、相关代码、约束、建议的顺序布局；右栏只保留顺序和风险。所有文字遵守 16/13/12/10px 层级，内容自动换行，页面不得横向滚动。

- [ ] **Step 4: Run the page test to verify GREEN**

运行 `MonitorWorkbenchPageTest`，Expected: PASS。

### Task 5: 完整验证

**Files:**
- Verify: all modified files

**Interfaces:**
- Consumes: 完整应用。
- Produces: 自动化测试和真实浏览器证据。

- [ ] **Step 1: Run the full Maven suite**

```bash
JAVA_HOME=/Users/user/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home /Users/user/Documents/env/maven/apache-maven-3.9.16/bin/mvn test
```

Expected: 0 failures, 0 errors.

- [ ] **Step 2: Restart the application on port 18080**

确认旧进程退出后使用当前源码启动，等待 `/actuator/health` 返回 UP。

- [ ] **Step 3: Browser acceptance**

在真实开发方案中验证：字号一致；每个环节有关联规则和代码原因；点击源码不离开开发方案；链路图同时出现实线真实节点和虚线规划节点；适配、缩放和全屏可用；有全局代码命中时不出现错误的“未命中”；页面无横向溢出。
