# Design: 需求增量继承与显式删除

## Current problem

现有差异服务直接比较两个版本各自的原始快照条目。由于原始文档是增量记录，旧需求没有在新文档重复出现时会被底层集合差异误判为 `REMOVED`。

## Data contract

- `Snapshot.entries` 保存该版本的增量事件，不直接代表完整需求清单。
- `Entry.operation` 为可选字段：
  - 缺失或 `UPSERT`：新增或更新。
  - `REMOVE`：按同一稳定 `entryId` 删除历史条目。
- 保持 `schemaVersion = 1` 兼容已提交快照；旧 JSON 缺少 `operation` 时按 `UPSERT` 处理。
- 生成工具只读取专用操作/状态列中的精确删除状态，不扫描需求正文关键词。

## Materialization

在 `RequirementSnapshotRepository` 中增加完整视图合成：

1. 一次读取项目全部快照并按需求版本索引。
2. 从目标快照沿 `baseRequirementVersion` 递归解析。
3. 使用访问集合检测继承环。
4. 基线必须属于同一项目和文档；缺失或不一致时安全失败。
5. 从最早基线开始，以 `LinkedHashMap<entryId, Entry>` 应用增量：`UPSERT` 覆盖，`REMOVE` 删除。
6. 返回仅供比较使用的目标版本完整快照。

## Comparison

- 两端都有受控快照：比较两端 materialized 完整视图，可准确得到新增、修改和显式删除。
- 快照不可用而回退 Qdrant：保留新增和修改，过滤由“新版本缺席”推断出的删除，因为该来源没有结构化删除事件。

## Compatibility and safety

- 已提交的 20 份快照无需加入默认操作字段，避免无意义的大范围数据重写。
- `REMOVE` 条目仍保留来源文本和哈希，便于审核，但合成后的完整视图不包含该条目。
- 递归链失败通过既有安全异常契约返回，不暴露绝对路径或源文件内容。
