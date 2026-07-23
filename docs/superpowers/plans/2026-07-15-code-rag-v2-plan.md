# Code RAG v2 实施计划

**Spec**: `docs/superpowers/specs/2026-07-15-code-rag-v2-design.md`

---

### Task 1: CodeChunk 格式升级 + 静态分析

**Files:**
- Modify: `src/main/java/com/example/requirementrag/model/CodeChunk.java`
- Modify: `src/main/java/com/example/requirementrag/code/JavaCodeScanner.java`
- Modify: `src/main/java/com/example/requirementrag/code/CodeQdrantStore.java`

**Steps:**
1. 扩展 `CodeChunk` record 新增: `language`, `className`, `businessDescCn`, `businessDescEn`, `callRelation`(List<String>), `keywords`(List<String>)
2. `JavaCodeScanner` 提取: className (method 所属类), import 列表, 方法内类引用
3. `CodeQdrantStore.buildPoints` 将新字段写入 payload
4. `CodeQdrantStore.toChunk` 读取新字段
5. 编译通过

**注意**: 新字段初始为空，Phase 2 由 LLM 填充

---

### Task 2: LLM 语义标注

**Files:**
- New: `src/main/java/com/example/requirementrag/code/CodeSemanticAnnotator.java`
- Modify: `src/main/java/com/example/requirementrag/code/CodeKnowledgeService.java`

**Steps:**
1. 创建 `CodeSemanticAnnotator` 组件
   - 注入 `ChatClient`
   - 方法: `List<CodeChunk> annotate(List<CodeChunk> chunks)` 
   - 每批 5 个 chunk 调用 Haiku 4.5
   - Prompt: className + symbolName + text[:500] → JSON {businessDescCn, businessDescEn, keywords}
   - 超时/异常时 fallback 为空值
2. 在 `CodeKnowledgeService.index()` 中，scanner 后调用 annotator
3. 编译+测试

---

### Task 3: 查询重写

**Files:**
- New: `src/main/java/com/example/requirementrag/code/CodeQueryRewriter.java`
- Modify: `src/main/java/com/example/requirementrag/code/CodeKnowledgeService.java`

**Steps:**
1. 创建 `CodeQueryRewriter` 组件
   - 注入 `ChatClient`
   - 方法: `RewriteResult rewrite(String query)`
   - 内部 record: `RewriteResult(String rewrittenQuery, List<String> keywords)`
   - 用 Haiku 4.5 将中文查询重写为包含英文技术关键词的搜索文本
   - 失败时 fallback 为原始 query
2. 在 `search()` / `graph()` 调用前先 rewrite
3. 编译+测试

---

### Task 4: 多路召回 + 关键词索引

**Files:**
- Modify: `src/main/java/com/example/requirementrag/code/CodeQdrantStore.java`

**Steps:**
1. `ensureCollection` 中为 `keywords` 创建 payload index (keyword type)
2. 新增 `keywordSearch(collection, keywords, projectId, limit)` 方法
   - 使用 Qdrant filter: keywords must match any of the given keywords
   - 不做向量搜索，只做 payload 精确匹配 + scroll
3. 修改 `hybridSearch` 接受 `RewriteResult`
   - Dense + Sparse prefetch (使用 rewrittenQuery)
   - 追加 keyword 精确匹配结果
   - RRF 合并去重
4. 编译+测试

---

### Task 5: 向量化增强

**Files:**
- Modify: `src/main/java/com/example/requirementrag/code/CodeQdrantStore.java`

**Steps:**
1. `enrichedSearchText` 使用新字段:
   ```
   [{className}] {symbolName} ({symbolType})
   {businessDescCn}
   {businessDescEn}  
   {keywords}
   {pathWords}
   {source truncated to 2000}
   ```
2. 编译+重新索引测试
