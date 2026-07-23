# Qdrant Docker 持久化 + 前端向量库仪表盘 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Qdrant 迁移至 Docker Compose 运行确保数据持久化；前端索引页改为只读仪表盘，丰富展示向量库集合、分块明细、健康历史、性能指标和召回 Top 预览。

**Architecture:** Docker Compose 管理 Qdrant 容器，bind mount 持久化到 `./qdrant-storage/`。后端新增只读查询 API，前端索引页变为向量库仪表盘。保留现有 DataHealthChecker 和 MonitorSnapshot.dataHealth 逻辑。

**Tech Stack:** Docker Compose, Spring Boot 3.x, Vue 3 (CDN), Qdrant REST API

## Global Constraints

- Java 21+，Spring Boot 3.x
- 前端纯 Vue 3 CDN（无构建工具），单文件 `monitor.html`
- 前端**完全只读**：不暴露任何写入按钮
- Qdrant REST API 端口 6333
- 后端写入 API 保留（供 CLI/脚本/CI 调用）

---

### Task 1: Docker Compose 配置 + 数据迁移

**Files:**
- Create: `docker-compose.yml`
- Create: `qdrant-storage/.gitkeep`
- Modify: `.gitignore`
- Delete: `tools/qdrant-start.sh`
- Delete: `tools/qdrant-stop.sh`

**Interfaces:**
- Consumes: 无
- Produces: Qdrant 服务在 `localhost:6333` 可用，数据持久化到 `./qdrant-storage/`

- [ ] **Step 1: 停止当前 Qdrant 二进制**

```bash
kill $(lsof -ti :6333) 2>/dev/null || true
sleep 2
```

- [ ] **Step 2: 创建 docker-compose.yml**

```yaml
services:
  qdrant:
    image: qdrant/qdrant:latest
    container_name: nexus-qdrant
    ports:
      - "6333:6333"
      - "6334:6334"
    volumes:
      - ./qdrant-storage:/qdrant/storage
    restart: unless-stopped
    environment:
      QDRANT__SERVICE__GRPC_PORT: "6334"
```

- [ ] **Step 3: 迁移数据并创建目录**

```bash
mkdir -p qdrant-storage
cp -r tools/qdrant-data/* qdrant-storage/
touch qdrant-storage/.gitkeep
```

- [ ] **Step 4: 更新 .gitignore**

```gitignore
target/
.m2-cache/
.idea/
.vscode/
*.iml
.DS_Store
.env

# Runtime artifacts
tools/qdrant
tools/*.log
tools/*.tar.gz
tools/*.pid

# Qdrant persistent storage (Docker bind mount)
qdrant-storage/*
!qdrant-storage/.gitkeep

# Source data files
data/*
!data/.gitkeep

# Large binary files
*.zip
!.env.example
```

- [ ] **Step 5: 删除旧脚本**

```bash
rm tools/qdrant-start.sh tools/qdrant-stop.sh
```

- [ ] **Step 6: 启动并验证**

```bash
docker compose up -d
sleep 3
curl -s http://localhost:6333/collections | python3 -m json.tool
```

预期：集合列表包含 `requirement_chunks`、`code_chunks`。

- [ ] **Step 7: 提交**

```bash
git add docker-compose.yml qdrant-storage/.gitkeep .gitignore
git rm tools/qdrant-start.sh tools/qdrant-stop.sh
git commit -m "feat: migrate Qdrant to Docker Compose with bind mount persistence"
```

---

### Task 2: 后端新增向量库查询 API

**Files:**
- Modify: `src/main/java/com/example/requirementrag/web/MonitorController.java`
- Modify: `src/main/java/com/example/requirementrag/knowledge/DataHealthChecker.java`

**Interfaces:**
- Consumes: Qdrant REST API (`/collections`, `/collections/{name}`)、`QdrantHybridStore`、`CodeQdrantStore`、`DataHealthChecker`
- Produces:
  - `GET /api/monitor/collections` → `List<CollectionInfo>`
  - `GET /api/monitor/health-history` → `List<HealthCheckRecord>`
  - `GET /api/monitor/recall-preview?query={q}&collection={c}&limit={n}` → `RecallPreview`

- [ ] **Step 1: DataHealthChecker 添加健康历史记录**

在 `DataHealthChecker.java` 中新增一个内存列表保存最近 20 条自检记录：

```java
public record HealthCheckRecord(
    String timestamp,
    String status,
    long reqChunks,
    long codeChunks
) {}

private final List<HealthCheckRecord> history = new java.util.concurrent.CopyOnWriteArrayList<>();

// 在 checkOnStartup() 的 try 块中，每次检查后记录
private void record(String status, long reqChunks, long codeChunks) {
    history.add(new HealthCheckRecord(
        java.time.Instant.now().toString(), status, reqChunks, codeChunks));
    while (history.size() > 20) history.removeFirst();
}

public List<HealthCheckRecord> history() {
    return List.copyOf(history);
}
```

在 `checkOnStartup()` 中，HEALTHY / EMPTY / DEGRADED 各分支都调用 `record(healthStatus, reqChunks, codeChunks)`。

- [ ] **Step 2: MonitorController 添加集合查询端点**

```java
@GetMapping("/collections")
public List<Map<String, Object>> collections() {
    // 调用 Qdrant REST API: GET /collections
    // 对每个集合再调用 GET /collections/{name} 获取详情
    // 返回 [{ name, status, vectorsCount, pointsCount, segmentsCount, diskDataSize }]
}
```

使用 Spring `RestClient` 调用 Qdrant API，解析 JSON 返回：

```java
private final RestClient qdrantClient;

// 构造函数中初始化
this.qdrantClient = RestClient.builder()
    .baseUrl(properties.qdrant().baseUrl())
    .build();

@GetMapping("/collections")
public Object collections() {
    var listResp = qdrantClient.get()
        .uri("/collections")
        .retrieve()
        .body(Map.class);
    var collectionNames = ((List<Map<String, Object>>) ((Map) listResp.get("result")).get("collections"))
        .stream().map(c -> (String) c.get("name")).toList();

    return collectionNames.stream().map(name -> {
        try {
            var detail = qdrantClient.get()
                .uri("/collections/{name}", name)
                .retrieve()
                .body(Map.class);
            var result = (Map<String, Object>) detail.get("result");
            return Map.of(
                "name", name,
                "status", result.getOrDefault("status", "unknown"),
                "vectorsCount", result.getOrDefault("vectors_count", 0),
                "pointsCount", result.getOrDefault("points_count", 0),
                "segmentsCount", ((List<?>) ((Map) result.get("optimizer_status")).getOrDefault("segments", List.of())).size()
            );
        } catch (Exception e) {
            return Map.of("name", name, "status", "error", "error", e.getMessage());
        }
    }).toList();
}
```

- [ ] **Step 3: MonitorController 添加健康历史端点**

```java
@GetMapping("/health-history")
public List<DataHealthChecker.HealthCheckRecord> healthHistory() {
    return dataHealthChecker.history();
}
```

需要注入 `DataHealthChecker`：

```java
private final DataHealthChecker dataHealthChecker;
// 在构造函数中添加参数
```

- [ ] **Step 4: MonitorController 添加召回预览端点**

```java
@GetMapping("/recall-preview")
public Map<String, Object> recallPreview(
        @RequestParam(defaultValue = "测试查询") String query,
        @RequestParam(required = false) String projectId,
        @RequestParam(defaultValue = "5") int limit) {
    String resolvedProject = projectId == null || projectId.isBlank()
        ? properties.code().projectId() : projectId;

    // 需求检索
    List<Map<String, Object>> reqResults = new ArrayList<>();
    try {
        var reqHits = store.hybridSearch(
            properties.qdrant().collection(), query, limit);
        for (var doc : reqHits) {
            reqResults.add(Map.of(
                "content", truncate(doc.getText(), 200),
                "score", doc.getScore(),
                "metadata", doc.getMetadata()
            ));
        }
    } catch (Exception ignored) {}

    // 代码检索
    List<Map<String, Object>> codeResults = new ArrayList<>();
    try {
        var codeHits = codeKnowledgeService.search(query, resolvedProject, limit);
        for (var chunk : codeHits) {
            codeResults.add(Map.of(
                "filePath", chunk.filePath(),
                "symbolName", chunk.symbolName(),
                "symbolType", chunk.symbolType(),
                "score", chunk.score(),
                "snippet", truncate(chunk.text(), 200)
            ));
        }
    } catch (Exception ignored) {}

    return Map.of(
        "query", query,
        "requirement", reqResults,
        "code", codeResults
    );
}

private String truncate(String text, int maxLen) {
    if (text == null) return "";
    return text.length() > maxLen ? text.substring(0, maxLen) + "…" : text;
}
```

- [ ] **Step 5: 验证 API**

```bash
curl -s http://localhost:8080/api/monitor/collections | python3 -m json.tool
curl -s http://localhost:8080/api/monitor/health-history | python3 -m json.tool
curl -s "http://localhost:8080/api/monitor/recall-preview?query=成长基金&limit=3" | python3 -m json.tool
```

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/example/requirementrag/web/MonitorController.java \
        src/main/java/com/example/requirementrag/knowledge/DataHealthChecker.java
git commit -m "feat: add read-only APIs for collection info, health history, and recall preview"
```

---

### Task 3: 前端向量库仪表盘

**Files:**
- Modify: `src/main/resources/static/monitor.html`

**Interfaces:**
- Consumes: Task 2 产出的 API（`/collections`, `/health-history`, `/recall-preview`）
- Produces: 索引页面变为向量库仪表盘，只读展示

- [ ] **Step 1: Vue data 中添加新状态**

```javascript
collections: [],
healthHistory: [],
recallPreview: null,
recallQuery: '成长基金',
recallLoading: false,
```

- [ ] **Step 2: Vue methods 中添加数据加载方法**

```javascript
async loadCollections() {
  try {
    this.collections = await this.api('/api/monitor/collections');
  } catch (e) { this.collections = []; }
},
async loadHealthHistory() {
  try {
    this.healthHistory = await this.api('/api/monitor/health-history');
  } catch (e) { this.healthHistory = []; }
},
async loadRecallPreview() {
  if (!this.recallQuery.trim()) return;
  this.recallLoading = true;
  try {
    const pid = this.currentProjectId ? '&projectId=' + encodeURIComponent(this.currentProjectId) : '';
    this.recallPreview = await this.api(
      '/api/monitor/recall-preview?query=' + encodeURIComponent(this.recallQuery) + '&limit=5' + pid);
  } catch (e) {
    this.recallPreview = null;
    this.showToast('召回预览失败: ' + e.message);
  } finally {
    this.recallLoading = false;
  }
},
```

- [ ] **Step 3: 在 refreshAll 中加载集合和健康历史**

在现有的 `refreshAll()` 方法中，与 status 和 rag 并行加载：

```javascript
async refreshAll() {
  try {
    const [status, rag, collections, healthHistory] = await Promise.all([
      this.api('/api/monitor/status' + (this.currentProjectId ? '?projectId=' + encodeURIComponent(this.currentProjectId) : '')),
      this.api('/api/monitor/rag-chain'),
      this.api('/api/monitor/collections').catch(() => []),
      this.api('/api/monitor/health-history').catch(() => [])
    ]);
    this.status = status;
    this.knowledge = status.knowledge || {};
    this.bootstrap = status.bootstrap || {};
    this.metrics = status.metrics || {};
    this.rag = rag;
    this.collections = collections;
    this.healthHistory = healthHistory;
  } catch (error) {
    this.showToast('状态刷新失败：' + error.message);
  }
},
```

- [ ] **Step 4: 重写索引页面 HTML 为向量库仪表盘**

替换 `activeTab === 'status'` section 的内容：

```html
<section v-if="activeTab === 'status'" class="panel">
  <div class="topbar">
    <div class="topbar-title">
      <h2>向量库仪表盘</h2>
    </div>
    <span :class="['pill', dataHealthTone]">{{ dataHealthLabel }}</span>
  </div>
  <div class="panel-pad" style="display:grid; gap:14px;">

    <!-- 数据健康告警 -->
    <div v-if="status.dataHealth === 'EMPTY' || status.dataHealth === 'DEGRADED'" class="data-health-alert"
         :class="status.dataHealth === 'EMPTY' ? 'alert-error' : 'alert-warn'">
      <strong v-if="status.dataHealth === 'EMPTY'">Qdrant 数据为空</strong>
      <strong v-else>数据不完整</strong>
      <span v-if="(knowledge.chunkCount || 0) === 0"> · 需求分块缺失</span>
      <span v-if="(knowledge.codeChunkCount || 0) === 0"> · 代码分块缺失</span>
    </div>

    <!-- Bootstrap 进度 -->
    <div v-if="bootstrap.state === 'RUNNING'" style="display:grid; gap:8px;">
      <div class="progress"><div class="bar" :style="{width: bootstrapPercent + '%'}"></div></div>
      <p class="muted">阶段：{{ bootstrap.phase || '等待启动' }} · 进度：{{ bootstrap.filesProcessed || 0 }}/{{ bootstrap.filesTotal || 0 }}</p>
    </div>

    <!-- 分块计数 -->
    <div style="display:grid; grid-template-columns:1fr 1fr; gap:12px; max-width:480px;">
      <div class="stat-card">
        <span class="stat-label">需求分块</span>
        <span :class="['stat-value', (knowledge.chunkCount || 0) === 0 ? 'stat-bad' : 'stat-ok']">{{ knowledge.chunkCount || 0 }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">代码分块</span>
        <span :class="['stat-value', (knowledge.codeChunkCount || 0) === 0 ? 'stat-bad' : 'stat-ok']">{{ knowledge.codeChunkCount || 0 }}</span>
      </div>
    </div>
    <p class="muted">知识库：{{ knowledge.documentId || '-' }} / {{ knowledge.version || '-' }}</p>

    <!-- 集合概况 -->
    <div class="card" v-if="collections.length">
      <h3>Qdrant 集合概况</h3>
      <div class="list compact-list">
        <div class="item" v-for="col in collections" :key="col.name">
          <div style="display:flex;justify-content:space-between;align-items:center;">
            <strong>{{ col.name }}</strong>
            <span :class="['pill', col.status === 'green' ? 'ok' : (col.status === 'yellow' ? 'warn' : 'bad')]">{{ col.status }}</span>
          </div>
          <p class="muted" style="margin:4px 0 0;">向量数: {{ col.vectorsCount || 0 }} · 点数: {{ col.pointsCount || 0 }}</p>
        </div>
      </div>
    </div>

    <!-- 健康历史 -->
    <div class="card" v-if="healthHistory.length">
      <h3>健康检查历史</h3>
      <div class="timeline">
        <div v-for="record in healthHistory" :key="record.timestamp" :class="['stage', record.status === 'HEALTHY' ? 'success' : 'failure']">
          <div class="stage-name">{{ record.status }}</div>
          <div>
            <div>需求: {{ record.reqChunks }} · 代码: {{ record.codeChunks }}</div>
            <div class="faint">{{ new Date(record.timestamp).toLocaleString() }}</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 召回 Top 预览 -->
    <div class="card">
      <h3>召回 Top 预览</h3>
      <div style="display:flex;gap:8px;margin-bottom:12px;">
        <input v-model="recallQuery" placeholder="输入查询文本…" @keyup.enter="loadRecallPreview" style="flex:1;">
        <button @click="loadRecallPreview" :disabled="recallLoading" style="width:auto;padding:7px 14px;">{{ recallLoading ? '查询中…' : '查询' }}</button>
      </div>
      <template v-if="recallPreview">
        <div v-if="recallPreview.requirement?.length">
          <strong style="color:var(--accent);font-size:11px;">需求召回 Top {{ recallPreview.requirement.length }}</strong>
          <div class="list compact-list" style="margin-top:6px;">
            <div class="item" v-for="(hit,i) in recallPreview.requirement" :key="'req-'+i">
              <div style="display:flex;justify-content:space-between;">
                <span class="faint">{{ hit.metadata?.title || '#' + (i+1) }}</span>
                <span class="pill ok">{{ (hit.score * 100).toFixed(1) }}%</span>
              </div>
              <p class="muted" style="margin:4px 0 0;font-size:11px;">{{ hit.content }}</p>
            </div>
          </div>
        </div>
        <div v-if="recallPreview.code?.length" style="margin-top:14px;">
          <strong style="color:var(--accent);font-size:11px;">代码召回 Top {{ recallPreview.code.length }}</strong>
          <div class="list compact-list" style="margin-top:6px;">
            <div class="item" v-for="(hit,i) in recallPreview.code" :key="'code-'+i">
              <div style="display:flex;justify-content:space-between;">
                <strong>{{ hit.symbolType }} · {{ hit.symbolName }}</strong>
                <span class="pill ok">{{ (hit.score * 100).toFixed(1) }}%</span>
              </div>
              <div class="faint">{{ hit.filePath }}</div>
              <p class="muted" style="margin:4px 0 0;font-size:11px;">{{ hit.snippet }}</p>
            </div>
          </div>
        </div>
        <p v-if="!recallPreview.requirement?.length && !recallPreview.code?.length" class="empty-state">未检索到结果，请调整查询词。</p>
      </template>
      <p v-else class="empty-state">输入查询后点击「查询」预览召回 Top 结果。</p>
    </div>

    <!-- CLI 操作提示 -->
    <div class="data-health-alert" style="background:rgba(109,231,242,.06);border-color:rgba(109,231,242,.18);color:var(--muted);">
      写入操作请通过命令行：<br/>
      <code style="font-size:11px;">curl -X POST http://localhost:8080/api/monitor/bootstrap</code> — 重新导入文档<br/>
      <code style="font-size:11px;">curl -X POST http://localhost:8080/api/code/index</code> — 索引 Java 代码
    </div>
  </div>
</section>
```

- [ ] **Step 5: 验证前端**

启动应用，打开浏览器「索引」页面。预期：
- 数据健康标签正常
- 集合概况显示各集合名称、向量数、状态
- 健康历史显示最近的检查记录
- 召回预览输入框可输入查询，显示 Top 结果带分数
- 无写入按钮，底部显示 CLI 命令提示

- [ ] **Step 6: 提交**

```bash
git add src/main/resources/static/monitor.html
git commit -m "feat: transform index page into read-only vector DB dashboard"
```

---

### Task 4: 清理 + 端到端验证

**Files:**
- 无新文件

**Interfaces:**
- Consumes: Task 1-3 全部产出
- Produces: 干净的项目结构，全流程验证通过

- [ ] **Step 1: 清理 tools/qdrant-data**

```bash
git rm -r --cached tools/qdrant-data/ 2>/dev/null || true
rm -rf tools/qdrant-data/
```

- [ ] **Step 2: 验证数据持久化**

```bash
docker compose restart qdrant
sleep 3
curl -s http://localhost:6333/collections | python3 -m json.tool
```

预期：重启后数据仍在。

- [ ] **Step 3: 验证应用自检**

启动 Spring Boot 应用，检查 DataHealthChecker 日志。

- [ ] **Step 4: 验证前端仪表盘**

打开浏览器，确认所有面板正常渲染。

- [ ] **Step 5: 验证容器销毁重建**

```bash
docker compose down
docker compose up -d
sleep 3
curl -s http://localhost:6333/collections | python3 -m json.tool
```

预期：数据不丢失。

- [ ] **Step 6: 最终提交**

```bash
git add -A && git status
git commit -m "chore: clean up old Qdrant artifacts and verify end-to-end"
```
