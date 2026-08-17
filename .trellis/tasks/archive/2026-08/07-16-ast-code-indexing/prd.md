# 代码 AST 索引

## Goal

用 JavaParser + Symbol Solver 替代 Java 正则符号扫描，并通过 shadow collection 安全迁移。

## Requirements

- 覆盖类、接口、枚举、record、构造器、重载、嵌套类型、注解、继承和实现。
- 输出兼容现有 CodeChunk 和 payload 的符号、行号及关系字段。
- 旧正则与 AST 并行比较；AST 写入 code_chunks_v2，验证后切换，保留旧 collection 回滚。

## Acceptance Criteria

- [ ] AST 单元测试覆盖上述 Java 语法。
- [ ] shadow 差异可报告，不影响旧索引。
- [ ] v2 collection 查询和 payload 回归通过。

