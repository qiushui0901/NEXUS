# 技术设计

## 方案
新增 `tools/build-version-wiki.py`，作为不依赖 Qdrant、Ollama 或向量数据的历史版本 Wiki 补全器。它读取 `data/wiki-sources/*.json` 中已有的版本定义，并从指定 Git 仓库的真实 commit 快照提取受控代码事实，更新源定义后重新调用现有 Wiki 生成规则渲染 JSON、Markdown 和版本索引。

流程：
1. 校验仓库存在、版本源定义存在，并校验 `codeCommit` / `baseCodeCommit` 为合法 Git commit。
2. 使用受控 `git show`、`git diff --name-status -M`、`git ls-tree` 和 `git cat-file` 读取 commit 元数据、文件边界、文件变更和有限源码结构。
3. 过滤 `.git`、target、依赖、IDE、生成目录和大文件；只保留 Java/Kotlin/Groovy/XML/YAML/properties/SQL/JSON/pom 等代码或配置文件。
4. 按版本、模块和路径聚合，生成版本概览页、代码结构与变更页、模块页；每页限制文件、符号和证据数量，避免复制完整源码。
5. 保留已有人工页面和产品事实；自动页面使用稳定的 `version-<version>-...` featureId，重复运行时先替换同一批自动页面，确保结果可重复。
6. 自动页面只写入 `CODE` / `GIT` 证据；测试内容在没有真实执行记录时明确写“没有真实执行快照”，不把代码推断成产品规则或测试结果。

## 版本中心
版本中心直接读取 `GET /api/wiki/versions`。`/api/versions/compare` 仍优先使用完整 VersionManifest；当起止版本没有独立 manifest 时，控制器降级调用 Wiki 版本比较，使用两个 `VersionIndex` 的代码 commit 和页面索引生成代码、Wiki 差异，需求和测试来源明确标记不可用。

## 安全和边界
- 构建器只访问命令行传入的 Git 仓库路径，不读取 Qdrant、embedding、向量库、WAL 或本地索引。
- Git 输出限制文件数量、符号数量和证据数量；不把源码全文写入 Wiki。
- 自动证据过滤凭据和向量基础设施关键词。
- 生成过程使用临时目录和原子替换，避免版本索引只写入一半。
