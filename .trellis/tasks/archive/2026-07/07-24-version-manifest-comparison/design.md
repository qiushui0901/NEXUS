# Design: 版本档案与多来源差异分析

## 1. Package layout

新增 `com.example.requirementrag.versioning`：

```text
VersioningProperties
VersionModels
VersionManifestService
RequirementVersionDiffService
VersionComparisonService
```

新增/抽取 `com.example.requirementrag.code.GitDiffService`，供 `IncrementalCodeIndexService` 和 `VersionComparisonService` 共同使用。

新增 `VersionController` 暴露版本档案和比较 API。

## 2. Manifest persistence

默认根目录：

```text
data/version-manifests/<projectId>/<version>.json
```

`VersionManifest` 包含：schemaVersion、projectId、version、baseVersion、需求文档/版本、起止代码 commit、测试快照、Wiki 版本、Wiki buildId、状态、时间和备注。

保存过程：

1. 校验项目和版本标识，规范化可选文本。
2. 校验 commit 为 7-64 位十六进制 SHA。
3. 拒绝序列化结果中的禁用字段名。
4. 创建项目目录，将 JSON 写入同目录临时文件。
5. 优先使用原子移动替换目标文件，不支持时退化为普通替换。
6. 更新时读取旧档案并保留 `createdAt`。

## 3. Requirement comparison

读取 manifest 指定的 `requirementDocumentId` 与 `requirementVersion`，调用 `QdrantHybridStore.scrollVersion`。该接口只返回 payload，不返回 vector。

按父块聚合，匹配键优先使用稳定 `parentId`，缺失时使用 `filename + parentOrder`；内容 hash 缺失时由规范化文本计算 SHA-256。输出 `ADDED`、`MODIFIED`、`REMOVED`，摘录限制长度。

若任一档案没有需求引用，返回 `NOT_AVAILABLE` 和 warning，不伪造空差异。

## 4. Code comparison

`GitDiffService` 在配置的项目代码仓库内执行固定参数结构：

```text
git diff --name-status -M <fromSha> <toSha>
```

两个 SHA 均在执行前严格校验，只解析 `A/M/D/R` 状态。结果包含 oldPath/newPath 和汇总统计；路径只作为数据返回，不作为后续文件系统路径使用。

`IncrementalCodeIndexService` 改为调用该服务，移除重复的进程执行和解析逻辑。

## 5. Test and Wiki comparison

测试差异使用 manifest 内的 `TestSnapshot`：

- 比较状态和 total/passed/failed/skipped；
- 按稳定 caseId 比较新增、删除和状态变化；
- 任一侧缺失时状态为 `NOT_AVAILABLE`。

Wiki 差异通过 `WikiRepository.findIndex` 获取索引：

- 按 `featureId` 比较页面新增/删除；
- 比较页面状态、摘要和证据计数；
- 缺失版本时加入 warning，其他来源继续执行。

## 6. API

```http
PUT /api/versions/manifests
GET /api/versions/manifests?projectId=...
GET /api/versions/manifests/{version}?projectId=...
GET /api/versions/compare?projectId=...&fromVersion=...&toVersion=...
```

Controller 只负责权限、项目访问和参数转发；业务校验与降级语义位于服务层。
