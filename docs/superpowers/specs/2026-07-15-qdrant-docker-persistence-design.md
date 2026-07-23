# Qdrant Docker 持久化 + 索引状态修复 + 增量更新设计

**日期**: 2026-07-15
**状态**: Draft → 待用户审阅

## 背景

Qdrant 停止后数据丢失，前端状态显示不准确，缺乏文档增量更新能力。

## 目标

1. Qdrant 数据在容器/进程重启后不丢失
2. 前端准确反映 Qdrant 实际数据健康度
3. 支持上传新文档并触发导入，支持代码仓库手动/自动重索引
4. 规范化 `data/` 目录存放源文件

## 非目标

- 多 Qdrant 实例集群部署
- 增量分块更新（仍使用全量替换）
- 前端新建项目功能（后续迭代）

---

## 1. Docker Compose + Qdrant 持久化

### 1.1 docker-compose.yml

在项目根目录新建 `docker-compose.yml`：

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
      QDRANT__SERVICE__GRPC_PORT: 6334
```

- **Bind mount**: `./qdrant-storage:/qdrant/storage` 确保数据持久化到本地目录
- **restart: unless-stopped**: 异常退出自动重启，手动 stop 不重启
- 数据目录对开发者可见，便于备份和检查

### 1.2 迁移现有数据

将 `tools/qdrant-data/` 中的数据复制到 `qdrant-storage/`：
```bash
cp -r tools/qdrant-data/* qdrant-storage/
```

### 1.3 清理旧脚本

- 删除 `tools/qdrant-start.sh`
- 删除 `tools/qdrant-stop.sh`
- 启停命令变为 `docker compose up -d` / `docker compose down`

### 1.4 .gitignore 更新

```gitignore
qdrant-storage/
```

---

## 2. 前端索引状态修复

### 2.1 后端状态检测（已有，保留）

`MonitorSnapshot` 的 `dataHealth` 字段通过 `assessHealth(qdrantStatus, reqChunks, codeChunks)` 实时计算：

| 状态 | 含义 |
|------|------|
| HEALTHY | 需求分块 > 0 且代码分块 > 0 |
| DEGRADED | 只有其中一种分块存在 |
| EMPTY | 两种分块都为 0 |
| UNKNOWN | Qdrant 不可达 |

### 2.2 启动自检（已有，保留）

`DataHealthChecker` 在 `ApplicationReadyEvent` 时：
1. 查询 Qdrant 中的需求分块数和代码分块数
2. 如果数据缺失，设置 `bootstrapState` 为 FAILED 并触发异步重建
3. 前端通过轮询 `/api/monitor/status` 获取最新的 `dataHealth`

### 2.3 前端展示（已有，保留）

- 索引页面顶部显示 `dataHealthLabel` 标签（颜色标识）
- 数据为空或不完整时显示红/黄告警
- 需求分块和代码分块独立显示计数卡片（0 时标红）

---

## 3. 增量更新机制（CLI 操作）

**约束：前端完全只读，所有写操作通过命令行/API 触发。**

### 3.1 场景 A：新增/替换产品文档

操作流程（CLI）：
1. 将新 ZIP/XLSX 文件放到 `data/` 目录
2. 如果文件名与 `application.yml` 中的 `zip-path`/`xlsx-path` 不同，修改配置
3. 通过 curl 触发导入：`curl -X POST http://localhost:8080/api/monitor/bootstrap`
4. 或重启应用，DataHealthChecker 自动检测并重建

### 3.2 场景 B：代码仓库更新

操作流程（CLI）：
1. 在代码仓库执行 `git pull`
2. 通过 curl 触发索引：`curl -X POST http://localhost:8080/api/code/index`

### 3.3 前端改为只读 + 丰富向量库状态展示

**移除写操作**:
- 移除前端「重新导入文档」按钮
- 移除前端「索引 Java 代码」按钮
- 显示 CLI 操作命令提示
- 后端 API 保留（供 CLI/脚本/CI 调用）

**新增只读展示面板**:

#### a) 集合概况
- 调用后端新增 API `/api/monitor/collections`，返回 Qdrant 各集合信息
- 展示：集合名称、向量数量、状态（green/yellow/red）、维度、距离度量

#### b) 分块明细
- 展示每个集合的文档分布（按 documentId 分组的 chunk 数量）
- 展示版本分布（按 version 分组的 chunk 数量）

#### c) 健康历史
- DataHealthChecker 将每次自检结果记录到内存列表（最近 20 条）
- 前端展示：时间、状态、需求分块数、代码分块数

#### d) 查询性能指标
- 展示 Micrometer 已有的 `rag.stage` 指标：检索延迟 p50/p95/p99、QPS
- 从 `/api/monitor/status` 的 metrics 中提取

#### e) 召回 Top 结果预览
- 新增后端 API `/api/monitor/recall-preview`：执行一次示例查询，返回 Top-K 结果带相似度分数
- 前端展示：查询文本、每条结果的文件/chunk 摘要、score

---

## 4. data/ 目录规范化

### 4.1 目录结构

```
request-RAG/
├── data/                           # 源文件
│   ├── .gitkeep
│   ├── 产品文档.zip
│   └── 封神版本问题整理.xlsx
├── qdrant-storage/                 # Qdrant 持久化数据
│   └── (Docker bind mount)
├── docker-compose.yml
└── ...
```

### 4.2 .gitignore

```gitignore
# Qdrant persistent storage
qdrant-storage/

# Source data files
data/*
!data/.gitkeep

# Old Qdrant local binary artifacts
tools/qdrant-data/
tools/qdrant
tools/*.log
tools/*.pid
tools/*.tar.gz
```

### 4.3 application.yml 默认路径

```yaml
app:
  rag:
    knowledge:
      zip-path: ${KNOWLEDGE_ZIP_PATH:data/产品文档.zip}
      xlsx-path: ${KNOWLEDGE_XLSX_PATH:data/封神版本问题整理.xlsx}
```

---

## 5. 改动清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `docker-compose.yml` | 新建 | Qdrant 容器编排 |
| `qdrant-storage/` | 新建 | Qdrant 数据持久化目录 |
| `tools/qdrant-start.sh` | 删除 | 替换为 docker compose |
| `tools/qdrant-stop.sh` | 删除 | 替换为 docker compose |
| `.gitignore` | 修改 | 添加 `qdrant-storage/` |
| `MonitorController.java` | 保留 | 已有 dataHealth 逻辑 |
| `MonitorSnapshot.java` | 保留 | 已有 assessHealth 方法 |
| `DataHealthChecker.java` | 保留 | 已有启动自检 |
| `KnowledgeBootstrapRunner.java` | 保留 | 已有跳过逻辑 |
| `monitor.html` | 修改 | 移除写操作按钮，新增向量库状态面板 |
| `MonitorController.java` | 修改 | 新增 `/collections`、`/recall-preview` 端点 |
| `DataHealthChecker.java` | 修改 | 记录健康历史到内存列表 |
| `application.yml` | 保留 | 已指向 data/ |

## 6. 风险与约束

- **数据迁移**: 从 `tools/qdrant-data/` 迁移到 `qdrant-storage/` 需要手动执行一次
- **Docker 依赖**: 开发者需要安装 Docker Desktop
- **全量替换**: 文档导入仍为全量替换，大文件时需等待
- **前端只读**: 所有写操作（导入、索引）仅通过 CLI/API，前端不暴露写入按钮
