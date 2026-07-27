# 技术设计：需求版本链补齐

## 1. 数据边界

新增 `data/requirement-snapshots/<projectId>/<requirementVersion>.json`，保存可审阅的非向量需求快照：

- 项目、文档 ID、需求版本、基线需求版本
- 可映射的业务版本 aliases
- 来源文件、来源位置、来源哈希
- 需求条目稳定 ID、文件名、顺序、正文和正文哈希

快照是版本比较的持久化事实来源，不是检索索引；Qdrant 仍用于检索和问答。

## 2. 版本解析

新增版本档案解析层：

1. 优先读取 `VersionManifestService` 中人工保存的正式档案。
2. 缺失时读取 Wiki `VersionIndex`。
3. 通过 `RequirementSnapshotRepository` 按业务版本 alias 解析需求快照。
4. 合成只读 `VersionManifest`，填入需求、代码和 Wiki 引用。

列表接口合并正式档案与合成档案，正式档案覆盖同版本合成结果。

## 3. 需求差异

`RequirementVersionDiffService` 按以下顺序加载需求版本：

1. 若两侧均存在受控快照，直接比较快照条目。
2. 否则保持现有 Qdrant payload scroll 比较能力。
3. 任一侧没有明确引用时返回 `NOT_AVAILABLE`。

差异键使用快照条目的稳定 ID；修改使用正文哈希判断，并复用现有 `RequirementChange` 响应。

## 4. 快照生成

新增 Python 标准库工具：

- 解析 XLSX OOXML 中的历史版本工作表；
- 解析 ZIP 中当前版本的有效 HTML；
- 规范化文本，计算 SHA-256；
- 写入确定性 JSON；
- 只生成小型文本快照，不复制原始 ZIP，不产生向量。

历史版本映射集中配置在工具中，便于审阅和更新。没有可靠来源的版本不映射。

## 5. 兼容性与降级

- 保持现有 REST 字段不变。
- 正式档案保存接口不变。
- 快照目录缺失时继续尝试 Qdrant。
- Qdrant 和快照都不可用时沿用 warning 与 `NOT_AVAILABLE`。
- 测试快照缺失仍独立降级，不影响需求、代码和 Wiki。

## 6. 安全

- 路径使用既有 `VersionPathPolicy` 约束。
- 生成工具拒绝输出敏感字段和向量字段。
- 快照来源路径只记录仓库相对路径与 ZIP 内位置。
