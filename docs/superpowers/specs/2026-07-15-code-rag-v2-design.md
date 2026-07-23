# Code RAG v2 设计规格

**日期**: 2026-07-15
**状态**: 设计中

## 背景

当前 Code RAG 使用 regex chunker + bge-m3 dual-vector + RRF 融合。用户中文查询直接匹配英文 Java 源码，精度不足。

## 目标

1. LLM 查询重写：搜索前用 Haiku 4.5 重写中文查询
2. 代码 embedding 模型：尝试 nomic-embed-code（Ollama 上最佳代码嵌入），回退 bge-m3
3. 中文语义 Metadata：索引时 LLM 生成中英文描述和关键词
4. 多路召回：向量 + BM25 + 关键词精确匹配
5. 代码知识图谱：静态分析提取 call_relation
6. 新 CodeChunk 格式：富结构

## 设计

### 1. 新 CodeChunk 格式

```java
record CodeChunk(
    String id,
    String projectId,
    String commitSha,
    String filePath,
    String language,          // NEW: "java"
    String className,         // NEW: 所属类名
    String symbolType,        // class/method/file
    String symbolName,
    int startLine,
    int endLine,
    String text,              // 原始源码
    String businessDescCn,    // NEW: LLM 生成的中文业务描述
    String businessDescEn,    // NEW: LLM 生成的英文业务描述
    List<String> callRelation,// NEW: 调用关系 [ClassName, ServiceName]
    List<String> keywords,    // NEW: 双语关键词
    String contentHash
)
```

### 2. 索引增强

**JavaCodeScanner 改造：**
- 提取 `className`（方法所在类）
- 提取 `import` 列表
- 提取方法内的类名引用（简单静态分析）→ `callRelation`
- `language` 固定为 "java"

**LLM 语义标注（批量）：**
- 索引时，每批 chunk 调用 Haiku 4.5 生成 `businessDescCn`、`businessDescEn`、`keywords`
- Prompt: 给定 className + symbolName + 源码 → 输出结构化 JSON
- 超时/失败时 fallback 为空值，不阻塞索引

### 3. 向量化文本（用于 embedding）

```
[{className}] {symbolName} ({symbolType})
{businessDescCn}
{businessDescEn}
{keywords joined by space}
{source code truncated to 2000 chars}
```

### 4. 查询重写

```
CodeQueryRewriter:
  Input: 用户中文查询
  Model: Haiku 4.5
  Output: {
    rewrittenQuery: "union upgrade level WorldUnion 联盟升级",
    keywords: ["union", "upgrade", "联盟", "升级"]
  }
```

### 5. 多路召回

```
1. Dense 召回: embedding(rewrittenQuery) → top 3*limit
2. Sparse BM25: sparse(rewrittenQuery) → top 3*limit  
3. Keyword 精确匹配: filter keywords ∩ rewrite.keywords → top limit
→ RRF 融合 → 最终 top limit
```

### 6. Qdrant Payload 索引

为 `keywords` 字段创建 keyword payload index，支持精确匹配召回。

## 实施分期

- Phase 1: CodeChunk 格式升级 + 静态分析 (callRelation, className)
- Phase 2: LLM 语义标注 (businessDesc, keywords)  
- Phase 3: 查询重写 + 多路召回
- Phase 4: 向量化增强 + 可选模型切换
