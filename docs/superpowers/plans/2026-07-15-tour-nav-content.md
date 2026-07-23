# 导览导航与内容改进 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 导览的上一步/下一步按钮始终可见，描述包含具体组件名和阅读建议。

**Architecture:** CSS flex 固定 footer + 后端 `tour()` 方法利用实际节点标签生成结构化描述。

**Tech Stack:** Spring Boot 4.1 (Java 21) + Vue 3 (CDN SPA)

## Global Constraints

- Java 21, Spring Boot 4.1
- 前端为单文件 `monitor.html`，无构建步骤
- 遵循已有代码风格

---

### Task 1: CSS 导览 Footer 固定

**Files:**
- Modify: `src/main/resources/static/monitor.html` (CSS section)

**Interfaces:**
- Produces: `.tour-active` 容器内 footer 始终在底部可见

- [ ] **Step 1: 修改 `.tour-active` 样式**

将 `min-height:100%` 改为 `height:100%; overflow:hidden`。

```css
.tour-active { height:100%; display:flex; flex-direction:column; overflow:hidden; }
```

- [ ] **Step 2: 添加 `.tour-active-head` flex 属性**

在已有样式后追加 `flex:0 0 auto;`。

- [ ] **Step 3: 添加 `.tour-progress` flex 属性**

```css
.tour-progress { height:3px; background:rgba(95,118,137,.22); flex:0 0 auto; }
```

- [ ] **Step 4: 修改 `.tour-content` 样式**

```css
.tour-content { flex:1; min-height:0; overflow-y:auto; padding:22px 18px; }
```

- [ ] **Step 5: 添加 `.tour-footer` flex 属性**

```css
.tour-footer { padding:12px 16px; border-top:1px solid var(--line); flex:0 0 auto; }
```

- [ ] **Step 6: 添加 description 的 white-space 支持**

将 `<p>{{ activeTour.description }}</p>` 改为：

```html
<p style="white-space:pre-line;">{{ activeTour.description }}</p>
```

---

### Task 2: 后端导览描述增强

**Files:**
- Modify: `src/main/java/com/example/requirementrag/code/CodeKnowledgeService.java`

**Interfaces:**
- Consumes: `nodes` Map (内部 GraphBuilder state), `query` String
- Produces: `CodeGraphTourStep` 的 `title` 和 `description` 字段内容增强

- [ ] **Step 1: 重写 `tour()` 方法**

从 `nodes` Map 中提取实际节点标签，用 `Collectors.joining("、")` 拼接组件名。

```java
private List<CodeGraphResponse.CodeGraphTourStep> tour(List<CodeGraphResponse.CodeGraphLayer> layers) {
    List<CodeGraphResponse.CodeGraphTourStep> steps = new ArrayList<>();
    int order = 1;
    for (CodeGraphResponse.CodeGraphLayer layer : layers) {
        if (layer.nodeIds().isEmpty()) continue;
        List<String> refIds = layer.nodeIds().stream().limit(5).toList();
        String nodeNames = refIds.stream()
                .map(id -> nodes.containsKey(id) ? nodes.get(id).label() : id)
                .collect(java.util.stream.Collectors.joining("、"));
        String description = tourDescription(layer.id(), nodeNames, refIds.size(), layer.nodeIds().size());
        steps.add(new CodeGraphResponse.CodeGraphTourStep(order++,
                tourTitle(layer, order - 1, layers.size()), description, refIds));
    }
    return steps;
}
```

- [ ] **Step 2: 添加 `tourTitle()` 方法**

```java
private String tourTitle(CodeGraphResponse.CodeGraphLayer layer, int step, int total) {
    String prefix = step == 1 ? "先看" : step == total ? "最后看" : "接着看";
    return prefix + "：" + layer.name();
}
```

- [ ] **Step 3: 添加 `tourDescription()` 方法**

每层返回三段式描述：功能说明 + 相关组件 + 建议。使用 `\n\n` 分隔段落。

```java
private String tourDescription(String layerId, String nodeNames, int shown, int total) {
    String countHint = total > shown ? "（共 " + total + " 个节点，已列出 " + shown + " 个关键组件）" : "";
    return switch (layerId) {
        case "layer:entry" -> "先确定「" + query + "」的入口在哪里。...\n\n相关组件：" + nodeNames + "\n\n建议：...";
        // ... (完整 8 个 case)
    };
}
```

- [ ] **Step 4: 验证编译**

Run: `mvn compile -q`
Expected: BUILD SUCCESS
