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
    retrieval(id, body) { return request("/api/knowledge-bases/" + encodeURIComponent(id) + "/retrieval-tests", {method: "POST", body: JSON.stringify(body)}); }
  };
})(window);
