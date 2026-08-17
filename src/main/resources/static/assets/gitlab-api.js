(function (global) {
  const request = global.NexusApi.request;
  const root = "/api/integrations/gitlab";
  const project = id => root + "/projects/" + encodeURIComponent(id);
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
    rotateSecret(id) { return request(project(id) + "/webhook-secret/rotate", {method:"POST"}); }
  };
})(window);
