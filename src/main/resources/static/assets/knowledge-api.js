(function (global) {
  const request = global.NexusApi.request;

  function query(params) {
    return global.NexusApi.query(params);
  }

  global.KnowledgeApi = {
    bases(params) { return request("/api/knowledge-bases" + query(params)); },
    base(id) { return request("/api/knowledge-bases/" + encodeURIComponent(id)); },
    runs(id, params) { return request("/api/knowledge-bases/" + encodeURIComponent(id) + "/runs" + query(params)); },
    documents(id, params) { return request("/api/knowledge-bases/" + encodeURIComponent(id) + "/documents" + query(params)); },
    document(id, documentId) { return request("/api/knowledge-bases/" + encodeURIComponent(id) + "/documents/" + encodeURIComponent(documentId)); },
    chunks(id, documentId, params) { return request("/api/knowledge-bases/" + encodeURIComponent(id) + "/documents/" + encodeURIComponent(documentId) + "/chunks" + query(params)); },
    chunk(id, chunkId) { return request("/api/knowledge-bases/" + encodeURIComponent(id) + "/chunks/" + encodeURIComponent(chunkId)); },
    rebuild(id) { return request("/api/knowledge-bases/" + encodeURIComponent(id) + "/rebuild", {method: "POST"}); },
    retryDocument(id, documentId) { return request("/api/knowledge-bases/" + encodeURIComponent(id) + "/documents/" + encodeURIComponent(documentId) + "/retry", {method: "POST"}); },
    retryChunk(id, chunkId) { return request("/api/knowledge-bases/" + encodeURIComponent(id) + "/chunks/" + encodeURIComponent(chunkId) + "/retry", {method: "POST"}); },
    retrieval(id, body) { return request("/api/knowledge-bases/" + encodeURIComponent(id) + "/retrieval-tests", {method: "POST", body: JSON.stringify(body)}); },
    // 语义 Claim 检索（多源）：与传统 Chunk 检索并行接入，互不影响（方案 §4.2）。
    semanticSearch(body) { return request("/api/knowledge/multi-source/search", {method: "POST", body: JSON.stringify(body)}); },
    // 语义构建状态：模块未启用（Controller 未装配）或无构建记录时后端返回 404，由调用方区分提示。
    semanticBuildStatus(params) { return request("/api/requirement-semantic/builds/latest" + query(params)); },
    // 项目/版本级聚合构建状态：与多源检索范围一致（按 projectId+version 聚合全部 active 文档）。
    semanticBuildAggregate(params) { return request("/api/requirement-semantic/builds/aggregate" + query(params)); },
    // 实体中心检索（dev md §11/§12）：recallMode = DETERMINISTIC（默认规则链）/ GRAPH_VECTOR（图+可选向量补召回）/ HYBRID。
    entitySearch(body) { return request("/api/knowledge/entity-search", {method: "POST", body: JSON.stringify(body)}); },
    // 实体中心带证据回答（§11/§12.1）：先取证据包再生成可审计回答。
    entityAnswer(body) { return request("/api/knowledge/entity-answer", {method: "POST", body: JSON.stringify(body)}); }
  };
})(window);
