# Implementation Plan — NEXUS 0.8.2

1. 更新历史文档，对 0.8.1 `Document Recall@10=1.0` 增加范围声明。
2. 扩展黄金文档结构和数据集校验，保持 v1 兼容。
3. 重构 matcher：实现 file/section/child 三层排名，移除 parentText 泄漏。
4. 扩展 CaseResult/Report，新增唯一 case 的分层质量摘要和 Markdown 展示。
5. 新增 v2 多文档多章节 hard-negative 语料与 JSONL 黄金集。
6. 扩展 setup 使其可选择 v2 fixture 目录，并增加固定语料位置契约测试。
7. 运行定向测试、完整 Java 21 verify、Python 比较工具测试、脚本语法和 diff 检查。
8. 同步 backend spec、路线图/历史文档与任务验收项；保持任务未归档、工作树未提交。
