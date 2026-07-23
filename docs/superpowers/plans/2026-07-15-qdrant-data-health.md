# Qdrant 数据健康度 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 应用启动时自检 Qdrant 数据完整性，自动重建空索引，前端正确展示数据健康度。

**Architecture:** 新增 `DataHealthChecker` 组件监听 `ApplicationReadyEvent`，检查 Qdrant 分块数并按需触发异步重建。`MonitorSnapshot` 扩展 `codeChunkCount` 和 `dataHealth` 字段，前端根据实时 Qdrant 数据（而非内存状态）渲染索引状态。

**Tech Stack:** Spring Boot 4.1 + Spring AI 2.0 + Qdrant + Vue.js (CDN)

## Global Constraints

- Java 21，Spring Boot 4.1
- 所有 Qdrant 操作需 catch RuntimeException 并降级
- 前端为单文件 `monitor.html`（Vue 3 CDN），无构建步骤
- 遵循已有代码风格：record、构造器注入、RestClient

---

### Task 1: MonitorSnapshot 模型扩展

**Files:**
- Modify: `src/main/java/com/example/requirementrag/model/MonitorSnapshot.java`

**Interfaces:**
- Produces: `KnowledgeStats(documentId, version, chunkCount, codeChunkCount, zipFiles, xlsxRows)`, `assessHealth(String, long, long) -> String`, `MonitorSnapshot(..., dataHealth)`

- [ ] **Step 1: 修改 MonitorSnapshot.java**

```java
public record MonitorSnapshot(
        String application,
        String qdrant,
        String ollama,
        BootstrapStatus bootstrap,
        KnowledgeStats knowledge,
        Map<String, Double> metrics,
        String dataHealth
) {
    public record KnowledgeStats(
            String documentId, String version,
            long chunkCount, long codeChunkCount,
            long zipFiles, long xlsxRows) {
    }

    public static String assessHealth(String qdrantStatus, long reqChunks, long codeChunks) {
        if (!"UP".equals(qdrantStatus)) return "UNKNOWN";
        if (reqChunks > 0 && codeChunks > 0) return "HEALTHY";
        if (reqChunks > 0 || codeChunks > 0) return "DEGRADED";
        return "EMPTY";
    }
}
```

- [ ] **Step 2: 验证编译**

Run: `./mvnw compile -pl . -q`
Expected: BUILD SUCCESS

---

### Task 2: MonitorController 代码分块数 + 数据健康度

**Files:**
- Modify: `src/main/java/com/example/requirementrag/web/MonitorController.java`

**Interfaces:**
- Consumes: `MonitorSnapshot.assessHealth()`, `MonitorSnapshot.KnowledgeStats(6 params)`, `CodeKnowledgeService.count(String)`
- Produces: `/api/monitor/status` 响应新增 `knowledge.codeChunkCount` 和 `dataHealth` 字段

- [ ] **Step 1: 注入 CodeKnowledgeService**

在构造器参数中添加 `CodeKnowledgeService codeKnowledgeService` 并赋值给字段。添加对应的 import。

- [ ] **Step 2: 修改 status() 方法**

```java
@GetMapping("/status")
public MonitorSnapshot status(@RequestParam(required = false) String projectId) {
    RagProperties.Knowledge knowledge = resolveKnowledge(projectId);
    String collection = resolveRequirementCollection(projectId);
    long chunkCount = safeCount(collection, knowledge.documentId(), knowledge.version());
    long codeChunkCount = safeCodeCount(projectId);
    String qdrant = qdrantStatus();
    return new MonitorSnapshot(
            applicationStatus(),
            qdrant,
            ollamaStatus(),
            bootstrapState.status(),
            new MonitorSnapshot.KnowledgeStats(
                    knowledge.documentId(),
                    knowledge.version(),
                    chunkCount,
                    codeChunkCount,
                    bootstrapState.zipFiles(),
                    bootstrapState.xlsxRows()),
            metricValues(),
            MonitorSnapshot.assessHealth(qdrant, chunkCount, codeChunkCount));
}
```

- [ ] **Step 3: 添加 safeCodeCount 方法**

```java
private long safeCodeCount(String projectId) {
    try {
        return codeKnowledgeService.count(projectId);
    } catch (RuntimeException exception) {
        return 0L;
    }
}
```

- [ ] **Step 4: 验证编译**

Run: `./mvnw compile -pl . -q`
Expected: BUILD SUCCESS

---

### Task 3: DataHealthChecker — 启动自检 + 自动重建

**Files:**
- Create: `src/main/java/com/example/requirementrag/knowledge/DataHealthChecker.java`

**Interfaces:**
- Consumes: `RagProperties.knowledge()`, `RagProperties.qdrant().collection()`, `QdrantHybridStore.countVersion()`, `CodeKnowledgeService.count()`, `CodeKnowledgeService.index()`, `KnowledgeBootstrapService.bootstrapAsync()`, `BootstrapState.fail()`
- Produces: `healthStatus()` getter (HEALTHY/DEGRADED/EMPTY/UNKNOWN)

- [ ] **Step 1: 创建 DataHealthChecker.java**

```java
package com.example.requirementrag.knowledge;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class DataHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(DataHealthChecker.class);

    private final RagProperties properties;
    private final QdrantHybridStore store;
    private final CodeKnowledgeService codeKnowledgeService;
    private final KnowledgeBootstrapService bootstrapService;
    private final BootstrapState bootstrapState;

    private volatile String healthStatus = "UNKNOWN";

    public DataHealthChecker(RagProperties properties, QdrantHybridStore store,
                             CodeKnowledgeService codeKnowledgeService,
                             KnowledgeBootstrapService bootstrapService,
                             BootstrapState bootstrapState) {
        this.properties = properties;
        this.store = store;
        this.codeKnowledgeService = codeKnowledgeService;
        this.bootstrapService = bootstrapService;
        this.bootstrapState = bootstrapState;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        try {
            RagProperties.Knowledge k = properties.knowledge();
            String collection = properties.qdrant().collection();
            long reqChunks = store.countVersion(collection, k.documentId(), k.version());
            long codeChunks = codeKnowledgeService.count();

            if (reqChunks > 0 && codeChunks > 0) {
                healthStatus = "HEALTHY";
                log.info("Qdrant 数据健康 — 需求分块: {}, 代码分块: {}", reqChunks, codeChunks);
                return;
            }

            boolean needReqRebuild = reqChunks == 0;
            boolean needCodeRebuild = codeChunks == 0;

            if (needReqRebuild && needCodeRebuild) {
                healthStatus = "EMPTY";
                log.warn("Qdrant 数据为空！自动触发需求导入 + 代码索引重建…");
                bootstrapState.fail("数据自检：Qdrant 集合为空，正在自动重建");
            } else if (needReqRebuild) {
                healthStatus = "DEGRADED";
                log.warn("需求分块为空 (代码分块: {})，自动触发需求导入…", codeChunks);
                bootstrapState.fail("数据自检：需求分块为空，正在自动重建");
            } else {
                healthStatus = "DEGRADED";
                log.warn("代码分块为空 (需求分块: {})，自动触发代码索引…", reqChunks);
            }

            if (needReqRebuild) {
                bootstrapService.bootstrapAsync();
            }
            if (needCodeRebuild) {
                try {
                    codeKnowledgeService.index();
                    log.info("代码索引自动重建完成");
                } catch (Exception ex) {
                    log.warn("代码索引自动重建失败: {}", ex.getMessage());
                }
            }
        } catch (RuntimeException ex) {
            healthStatus = "UNKNOWN";
            log.warn("启动时无法检查 Qdrant 数据健康度: {}", ex.getMessage());
        }
    }

    public String healthStatus() {
        return healthStatus;
    }
}
```

- [ ] **Step 2: 验证编译**

Run: `./mvnw compile -pl . -q`
Expected: BUILD SUCCESS

---

### Task 4: 前端索引状态页修复

**Files:**
- Modify: `src/main/resources/static/monitor.html`

**Interfaces:**
- Consumes: `/api/monitor/status` 响应中的 `knowledge.codeChunkCount` 和 `dataHealth`

- [ ] **Step 1: 添加 CSS 样式**

在 `.pill.bad` 之后添加 `.data-health-alert`、`.stat-card`、`.stat-ok`、`.stat-bad` 样式。

- [ ] **Step 2: 替换索引状态面板 HTML**

将 `v-if="activeTab === 'status'"` 面板中的内容替换为：
- 状态 pill 使用 `dataHealthTone` + `dataHealthLabel`（替代 `bootstrapTone`）
- 数据异常时显示红/黄色 alert 条
- 进度条仅在 RUNNING 时显示
- 双卡片展示需求分块数 + 代码分块数（0 为红色，>0 为绿色）

- [ ] **Step 3: 添加 computed properties**

```javascript
dataHealthTone() {
  return { HEALTHY:'ok', DEGRADED:'warn', EMPTY:'bad', UNKNOWN:'' }[this.status.dataHealth] || '';
},
dataHealthLabel() {
  return { HEALTHY:'数据正常', DEGRADED:'数据不完整', EMPTY:'数据为空', UNKNOWN:'未检测' }[this.status.dataHealth] || '未检测';
},
```

---

### Task 5: data/ 目录 + 配置路径 + .gitignore

**Files:**
- Create: `data/.gitkeep`
- Modify: `src/main/resources/application.yml`
- Modify: `.gitignore`

- [ ] **Step 1: 创建 data/ 目录和 .gitkeep**

```bash
mkdir -p data && touch data/.gitkeep
```

- [ ] **Step 2: 修改 application.yml 默认路径**

```yaml
zip-path: ${KNOWLEDGE_ZIP_PATH:data/产品文档.zip}
xlsx-path: ${KNOWLEDGE_XLSX_PATH:data/封神版本问题整理.xlsx}
```

- [ ] **Step 3: 更新 .gitignore**

添加：
```
# Source data files
data/*
!data/.gitkeep

tools/*.pid
```

---

### Task 6: Qdrant 管理脚本

**Files:**
- Create: `tools/qdrant-start.sh`
- Create: `tools/qdrant-stop.sh`

- [ ] **Step 1: 创建 qdrant-start.sh**

设置 `QDRANT__STORAGE__STORAGE_PATH` 环境变量，记录 PID 到 `qdrant.pid`，nohup 后台启动。

- [ ] **Step 2: 创建 qdrant-stop.sh**

发送 SIGTERM，循环等待 30s，超时 `kill -9`。支持 PID 文件不存在时按端口查找。

- [ ] **Step 3: 设置可执行权限**

```bash
chmod +x tools/qdrant-start.sh tools/qdrant-stop.sh
```
