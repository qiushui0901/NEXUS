(function (global) {
  const request = global.NexusApi.request;
  const root = "/api/integrations/gitlab";
  const project = id => root + "/projects/" + encodeURIComponent(id);
  const connection = id => root + "/connections/" + encodeURIComponent(id);
  global.GitLabApi = {
    projects() { return request(root + "/projects"); },
    create(body) { return request(root + "/projects", {method:"POST", body:JSON.stringify(body)}); },
    validateConnection(body) { return request(root + "/validate-connection", {method:"POST", body:JSON.stringify(body)}); },
    validateProject(body) { return request(root + "/validate-project", {method:"POST", body:JSON.stringify(body)}); },
    validateConfig(body) { return request(root + "/projects/validate-config", {method:"POST", body:JSON.stringify(body)}); },
    sync(id) { return request(project(id) + "/sync", {method:"POST"}); },
    retry(id) { return request(project(id) + "/retry", {method:"POST"}); },
    disable(id) { return request(project(id), {method:"DELETE"}); },
    jobs(id) { return request(project(id) + "/jobs"); },
    job(id, jobId) { return request(project(id) + "/jobs/" + encodeURIComponent(jobId)); },
    webhook(id) { return request(project(id) + "/webhook-status"); },
    rotateSecret(id) { return request(project(id) + "/webhook-secret/rotate", {method:"POST"}); },
    connections() { return request(root + "/connections"); },
    connection(id) { return request(connection(id)); },
    createConnection(body) {
      return request(root + "/connections", {method:"POST", body:JSON.stringify(body)});
    },
    verifyConnection(id) {
      return request(connection(id) + "/verify", {method:"POST"});
    },
    reauthorizeConnection(id, accessToken) {
      return request(connection(id) + "/reauthorize", {
        method:"POST", body:JSON.stringify({accessToken})
      });
    },
    disableConnection(id) {
      return request(connection(id), {method:"DELETE"});
    },
    remoteProjects(id, params) {
      const query = new URLSearchParams();
      Object.entries(params || {}).forEach(([key,value]) => {
        if (value !== undefined && value !== null && value !== "") query.set(key,value);
      });
      return request(connection(id) + "/projects?" + query.toString());
    },
    importProjects(id, projects) {
      return request(connection(id) + "/imports", {
        method:"POST", body:JSON.stringify({projects})
      });
    }
  };
})(window);
