# Qdrant 数据健康度、前端状态修复与 data/ 目录设计

**Date**: 2026-07-15
**Status**: Proposed

## Problem

1. **Qdrant 数据丢失无感知**：`kill -9` 强杀 Qdrant 导致 WAL 未刷盘，数据丢失后应用无任何提示。
2. **前端状态误导**：索引状态页基于内存中的 `BootstrapState` 显示，Qdrant 数据丢失后仍显示绿色"已完成"。只展示需求分块数，缺少代码分块数。
3. **源文件管理混乱**：ZIP/XLSX 源文件没有统一存放目录，且无 Qdrant 优雅关闭方案。

## Solution: Approach A — 轻量级自检 + 异步重建

### Module 1: DataHealthChecker

新建 `knowledge/DataHealthChecker.java`，Spring Bean。

**职责**：
- 监听 `ApplicationReadyEvent`
- 查询 Qdrant 中需求分块数和代码分块数
- 判定健康度并执行对应操作：

| 需求分块 | 代码分块 | 健康度 | 动作 |
|---------|---------|--------|------|
| > 0 | > 0 | HEALTHY | 无 |
| > 0 | = 0 | DEGRADED | 自动触发 `CodeKnowledgeService.index()` |
| = 0 | > 0 | DEGRADED | 自动触发 `KnowledgeBootstrapService.bootstrapAsync()` |
| = 0 | = 0 | EMPTY | 触发 bootstrap + index |
| 不可达 | 不可达 | UNKNOWN | 仅日志告警 |

**依赖**：`RagProperties`, `QdrantHybridStore`, `CodeKnowledgeService`, `KnowledgeBootstrapService`, `BootstrapState`

### Module 2: MonitorSnapshot 扩展

**KnowledgeStats** record 新增字段：
- `codeChunkCount` (long)

**MonitorSnapshot** record 新增字段：
- `dataHealth` (String): HEALTHY / DEGRADED / EMPTY / UNKNOWN

新增静态方法 `assessHealth(qdrantStatus, reqChunks, codeChunks)` 返回健康度字符串。

**MonitorController.status()** 变更：
- 注入 `CodeKnowledgeService`
- 调用 `safeCodeCount(projectId)` 获取代码分块数
- 使用 `MonitorSnapshot.assessHealth()` 计算 `dataHealth`

### Module 3: 前端索引状态页修复

**状态 pill**：
- 从 `bootstrapTone`（基于 `bootstrap.state`）→ `dataHealthTone`（基于 `status.dataHealth`）
- 标签：数据正常 / 数据不完整 / 数据为空 / 未检测

**数据展示**：
- 新增双卡片：需求分块数 + 代码分块数
- 数值为 0 时红色，> 0 时绿色
- 数据为空/不完整时显示 alert 条（红/黄色），附操作指引
- 进度条仅在 bootstrap RUNNING 时显示

**新增 computed properties**：
- `dataHealthTone`: 映射 dataHealth → pill CSS class
- `dataHealthLabel`: 映射 dataHealth → 中文标签

### Module 4: data/ 目录 + Qdrant 管理脚本

**data/ 目录**：
- 位于项目根目录
- 用于存放 ZIP/XLSX 源文件
- `.gitignore`: `data/*` + `!data/.gitkeep`

**application.yml 默认路径变更**：
- `zip-path`: `产品文档.zip` → `data/产品文档.zip`
- `xlsx-path`: `封神版本问题整理.xlsx` → `data/封神版本问题整理.xlsx`

**Qdrant 管理脚本**：
- `tools/qdrant-start.sh`: 设置 `QDRANT__STORAGE__STORAGE_PATH`，记录 PID，启动日志
- `tools/qdrant-stop.sh`: SIGTERM 优雅关闭，30s 超时后 `kill -9`

## Files Changed

| File | Action | Description |
|------|--------|-------------|
| `knowledge/DataHealthChecker.java` | New | 启动自检 + 自动重建 |
| `model/MonitorSnapshot.java` | Modify | 新增 codeChunkCount + dataHealth |
| `web/MonitorController.java` | Modify | 注入 CodeKnowledgeService，计算 dataHealth |
| `static/monitor.html` | Modify | 状态页 pill、数据卡片、alert |
| `application.yml` | Modify | 默认路径指向 data/ |
| `.gitignore` | Modify | 新增 data/* 规则 |
| `data/.gitkeep` | New | 保持目录结构 |
| `tools/qdrant-start.sh` | New | 启动脚本 |
| `tools/qdrant-stop.sh` | New | 停止脚本 |

## Not in Scope

- Qdrant Snapshot 定期备份（可作为后续优化）
- BootstrapState 持久化到文件
- 多项目独立健康度检查（当前仅检查默认项目）
