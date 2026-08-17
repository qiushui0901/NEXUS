(function (global) {
  const errors = global.NexusErrors;

  function apiKey() {
    return localStorage.getItem("nexusApiKey") || localStorage.getItem("nexus_api_key") || "";
  }

  function headers(extra, hasBody) {
    const result = Object.assign({}, extra || {});
    const hasHeader = name => Object.keys(result).some(key => key.toLowerCase() === name.toLowerCase());
    const key = apiKey();
    if (key && !hasHeader("X-API-Key")) result["X-API-Key"] = key;
    if (hasBody && !hasHeader("Content-Type")) result["Content-Type"] = "application/json";
    return result;
  }

  async function parse(response) {
    if (response.status === 204) return null;
    const type = response.headers.get("content-type") || "";
    if (type.includes("application/json")) return response.json();
    return response.text();
  }

  async function request(path, options) {
    const config = options || {};
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), config.timeout || 15000);
    try {
      const response = await fetch(path, Object.assign({}, config, {
        signal: config.signal || controller.signal,
        headers: headers(config.headers, Boolean(config.body))
      }));
      const body = await parse(response);
      if (!response.ok) {
        const failure = new Error();
        failure.status = response.status;
        failure.body = typeof body === "object" && body !== null ? body : {message: body};
        throw failure;
      }
      return body;
    } catch (error) {
      if (error && error.name === "AbortError") {
        throw Object.assign(new Error("请求超时，请检查服务连接"), {code: "REQUEST_TIMEOUT"});
      }
      const normalized = errors ? errors.normalize(error) : {message: "请求失败"};
      throw Object.assign(new Error(normalized.message), normalized);
    } finally {
      clearTimeout(timeout);
    }
  }

  function query(values) {
    const params = new URLSearchParams();
    Object.entries(values || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== "") params.set(key, value);
    });
    const value = params.toString();
    return value ? "?" + value : "";
  }

  global.NexusApi = {
    request,
    query,
    apiKey,
    setApiKey(value) {
      const next = String(value || "").trim();
      if (next) localStorage.setItem("nexusApiKey", next);
      else localStorage.removeItem("nexusApiKey");
      localStorage.removeItem("nexus_api_key");
      global.dispatchEvent(new CustomEvent("nexus:connection-changed"));
    }
  };
})(window);
