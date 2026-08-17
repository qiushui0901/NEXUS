(function (global) {
  function headers(json) {
    const key = localStorage.getItem("nexusApiKey");
    return Object.assign(
      json ? {"Content-Type": "application/json"} : {},
      key ? {"X-API-Key": key} : {}
    );
  }

  async function request(path, options) {
    const response = await fetch(path, Object.assign({}, options, {
      headers: Object.assign({}, headers(Boolean(options && options.body)), options && options.headers)
    }));
    if (!response.ok) {
      let detail = "请求失败";
      try {
        const body = await response.json();
        detail = body.detail || body.message || detail;
      } catch (_) {
        detail = response.statusText || detail;
      }
      const error = new Error(detail);
      error.status = response.status;
      throw error;
    }
    return response.status === 204 ? null : response.json();
  }

  function query(params) {
    const values = new URLSearchParams();
    Object.entries(params || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== "") values.set(key, value);
    });
    const text = values.toString();
    return text ? "?" + text : "";
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
