(function () {
  const api = window.NexusApi;
  const notice = window.NexusNotice;
  const state = {job:null, snapshots:[], snapshot:null, response:null};
  const $ = selector => document.querySelector(selector);
  const text = value => value === null || value === undefined || value === "" ? "—" : String(value);
  const clear = node => { while (node && node.firstChild) node.removeChild(node.firstChild); };
  const node = (tag, className, value) => {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (value !== undefined) element.textContent = value;
    return element;
  };
  const request = (path, options) => api.request(path, Object.assign({timeout: 30000}, options || {}));
  const query = () => ({
    projectId: $("#project-id").value.trim(),
    documentId: $("#document-id").value.trim(),
    version: $("#requirement-version").value.trim()
  });

  function showError(error) {
    const normalized = window.NexusErrors?.normalize(error, "需求图操作失败") || {message:"需求图操作失败"};
    notice?.show(normalized.message, "error");
  }

  function setBuildState(label, tone) {
    const pill = $("#build-state");
    pill.textContent = label;
    pill.className = "status-pill" + (tone ? " " + tone : "");
  }

  function jobMessage(job) {
    if (!job) return "等待构建任务。";
    const lines = [`${job.state} · ${job.buildId}`];
    if (job.snapshotId) lines.push(`快照：${job.snapshotId}`);
    if (job.errorCode) lines.push(`错误：${job.errorCode}${job.errorMessage ? " · " + job.errorMessage : ""}`);
    return lines.join("\n");
  }

  function updateJob(job) {
    state.job = job;
    $("#job-console").textContent = jobMessage(job);
    const running = job && ["QUEUED", "RUNNING"].includes(job.state);
    $("#cancel-button").disabled = !running;
    $("#resume-button").disabled = !job || !["FAILED", "PARTIAL_FAILED", "CANCELLED"].includes(job.state);
    if (!job) setBuildState("未启动", "neutral");
    else if (job.state === "SUCCEEDED") setBuildState("构建完成", "");
    else if (job.state === "PARTIAL_FAILED") setBuildState("部分失败", "warning");
    else if (job.state === "FAILED") setBuildState("构建失败", "danger");
    else if (job.state === "CANCELLED") setBuildState("已取消", "danger");
    else setBuildState(job.state === "RUNNING" ? "构建中" : "排队中", "neutral");
  }

  async function pollJob() {
    if (!state.job || !["QUEUED", "RUNNING"].includes(state.job.state)) return;
    try {
      updateJob(await request(`/api/requirement-graphs/builds/${encodeURIComponent(state.job.buildId)}`));
      if (["QUEUED", "RUNNING"].includes(state.job.state)) setTimeout(pollJob, 1500);
      else await loadSnapshots();
    } catch (error) {
      showError(error);
      setTimeout(pollJob, 5000);
    }
  }

  async function startBuild(event) {
    event.preventDefault();
    const values = query();
    if (!values.projectId || !values.documentId || !values.version) return;
    try {
      const job = await request("/api/requirement-graphs/builds", {method:"POST", body:JSON.stringify({
        projectId: values.projectId, documentId: values.documentId, requirementVersion: values.version,
        collection: $("#collection").value.trim() || null, allowPartial: false
      })});
      updateJob(job);
      setTimeout(pollJob, 1500);
      notice?.show("需求图构建任务已提交", "success");
    } catch (error) { showError(error); }
  }

  async function resumeBuild() {
    if (!state.job) return;
    try { updateJob(await request(`/api/requirement-graphs/builds/${encodeURIComponent(state.job.buildId)}/resume`, {method:"POST"})); setTimeout(pollJob, 1500); }
    catch (error) { showError(error); }
  }

  async function cancelBuild() {
    if (!state.job) return;
    try { updateJob(await request(`/api/requirement-graphs/builds/${encodeURIComponent(state.job.buildId)}/cancel`, {method:"POST"})); }
    catch (error) { showError(error); }
  }

  async function loadSnapshots() {
    const values = query();
    if (!values.projectId) return;
    try {
      state.snapshots = await request(`/api/requirement-graphs/snapshots${api.query({projectId:values.projectId, documentId:values.documentId, version:values.version})}`);
      const select = $("#snapshot-id");
      clear(select);
      if (!state.snapshots.length) select.append(node("option", "", "没有可用快照"));
      state.snapshots.forEach(snapshot => {
        const option = node("option", "", `${snapshot.requirementVersion} · ${snapshot.status} · ${snapshot.id}`);
        option.value = snapshot.id;
        select.append(option);
      });
      state.snapshot = state.snapshots[0] || null;
      if (state.snapshot) select.value = state.snapshot.id;
      await loadClaims();
    } catch (error) { showError(error); }
  }

  function selectedSnapshot() {
    const id = $("#snapshot-id").value;
    return state.snapshots.find(item => item.id === id) || state.snapshot;
  }

  async function runSearch(event) {
    event?.preventDefault();
    const values = query();
    const snapshot = selectedSnapshot();
    const queryText = $("#query").value.trim();
    if (!snapshot || !queryText) return;
    const includeUnresolved = $("#include-unresolved").checked;
    $("#query-state").textContent = "检索中";
    try {
      state.response = await request("/api/requirement-graphs/search", {method:"POST", body:JSON.stringify({
        projectId:values.projectId, documentId:snapshot.documentId, requirementVersion:snapshot.requirementVersion,
        query:queryText, mode:$("#search-mode").value, maxHops:Number($("#max-hops").value || 2),
        limit:Number($("#result-limit").value || 20), statuses:includeUnresolved ? [] : ["VERIFIED"], includeUnresolved, page:0
      })});
      renderResponse(state.response);
      $("#query-state").textContent = state.response.warnings?.length ? "需复核" : "证据已绑定";
    } catch (error) { $("#query-state").textContent = "失败"; showError(error); }
  }

  function evidenceCard(item) {
    const card = node("div", "evidence-card");
    card.append(node("div", "evidence-meta", `${text(item.evidenceId)} · ${text(item.sectionPath || item.filename)}`));
    card.append(node("div", "", text(item.quote || item.excerpt)));
    if (item.startOffset >= 0) card.append(node("div", "evidence-meta", `offset ${item.startOffset}–${item.endOffset} · ${text(item.resolutionStatus)}`));
    return card;
  }

  function renderResponse(response) {
    const root = $("#graph-result");
    clear(root);
    const entities = response.entities || [];
    const relations = response.relations || [];
    const byId = Object.fromEntries(entities.map(item => [item.id, item]));
    $("#result-summary").textContent = `${entities.length} 个实体 · ${relations.length} 条关系 · ${response.evidence?.length || 0} 条证据`;
    const warning = $("#result-warning");
    if (response.warnings?.length) { warning.classList.remove("hidden"); warning.textContent = response.warnings.map(item => `${item.code}：${item.message}`).join("\n"); }
    else { warning.classList.add("hidden"); warning.textContent = ""; }
    if (!entities.length && !relations.length) { root.className = "graph-result empty-result"; root.append(node("strong", "", "没有命中关系"), node("span", "", "尝试扩大问题描述或允许审阅中的声明。")); return; }
    root.className = "graph-result";
    if (entities.length) {
      root.append(node("div", "result-divider", "实体"));
      entities.forEach(entity => {
        const card = node("article", "entity-card");
        card.append(node("span", "entity-dot"));
        const body = node("div", "");
        const head = node("div", "entity-head");
        head.append(node("span", "entity-name", text(entity.displayName)));
        head.append(node("span", "entity-type", `${text(entity.type)} · ${text(entity.claimStatus)}`));
        body.append(head);
        if (entity.description) body.append(node("p", "entity-description", entity.description));
        const action = node("button", "quiet-button", "查看邻域");
        action.type = "button"; action.addEventListener("click", () => loadNeighborhood(entity.id));
        body.append(action);
        (response.evidence || []).filter(item => (entity.sourceEvidenceIds || []).includes(item.evidenceId)).slice(0,2).forEach(item => body.append(evidenceCard(item)));
        card.append(body); root.append(card);
      });
    }
    if (relations.length) {
      root.append(node("div", "result-divider", "关系"));
      relations.forEach(relation => {
        const row = node("article", "relation-row");
        row.append(node("div", "relation-node", text(byId[relation.sourceEntityId]?.displayName || relation.sourceEntityId)));
        row.append(node("div", "relation-type", `${text(relation.type)}${relation.condition ? " · " + relation.condition : ""}`));
        row.append(node("div", "relation-node", text(byId[relation.targetEntityId]?.displayName || relation.targetEntityId)));
        root.append(row);
        const detail = node("div", "evidence-card", text(relation.statement));
        (response.evidence || []).filter(item => (relation.sourceEvidenceIds || []).includes(item.evidenceId)).slice(0,2).forEach(item => detail.append(evidenceCard(item)));
        root.append(detail);
      });
    }
  }

  async function loadNeighborhood(entityId) {
    const snapshot = selectedSnapshot();
    if (!snapshot) return;
    try {
      const response = await request(`/api/requirement-graphs/${encodeURIComponent(snapshot.id)}/neighborhood/${encodeURIComponent(entityId)}${api.query({maxHops:Number($("#max-hops").value || 2),limit:Number($("#result-limit").value || 20),includeUnresolved:$("#include-unresolved").checked})}`);
      renderResponse(response); notice?.show("已载入实体邻域", "success");
    } catch (error) { showError(error); }
  }

  async function loadClaims() {
    const snapshot = selectedSnapshot();
    const root = $("#claims-result");
    clear(root);
    if (!snapshot) { root.append(node("div", "empty-result", "选择快照后载入声明")); return; }
    try {
      const page = await request(`/api/requirement-graphs/${encodeURIComponent(snapshot.id)}/claims${api.query({limit:50,offset:0})}`);
      const claims = [...(page.entities || []).map(item => ({...item, kind:"实体", text:item.displayName})), ...(page.relations || []).map(item => ({...item, kind:"关系", text:item.statement}))];
      if (!claims.length) { root.append(node("div", "empty-result", "此快照没有声明")); return; }
      claims.forEach(claim => {
        const item = node("article", "claim-item");
        const top = node("div", "claim-top");
        top.append(node("span", "claim-id", `${claim.kind} · ${claim.id}`));
        const status = node("span", `claim-status${claim.claimStatus === "VERIFIED" ? " verified" : ""}`, text(claim.claimStatus));
        top.append(status); item.append(top); item.append(node("div", "claim-text", text(claim.text)));
        const actions = node("div", "claim-actions");
        const verify = node("button", "", "通过"); verify.type="button"; verify.addEventListener("click", () => decideClaim(claim.id, "verify"));
        const reject = node("button", "", "驳回"); reject.type="button"; reject.addEventListener("click", () => decideClaim(claim.id, "reject"));
        const merge = node("button", "", "合并"); merge.type="button"; merge.addEventListener("click", () => mergeClaim(claim.id));
        const split = node("button", "", "拆分"); split.type="button"; split.addEventListener("click", () => splitClaim(claim));
        actions.append(verify, reject, merge, split); item.append(actions); root.append(item);
      });
    } catch (error) { root.append(node("div", "empty-result", "声明加载失败")); showError(error); }
  }

  async function mergeClaim(claimId) {
    const snapshot = selectedSnapshot();
    const targetClaimId = window.prompt("输入目标声明 ID");
    if (!snapshot || !targetClaimId) return;
    try {
      await request(`/api/requirement-graphs/claims/${encodeURIComponent(claimId)}/merge`, {method:"POST", body:JSON.stringify({targetClaimId, reason:"审阅工作台合并"})});
      notice?.show("声明已合并，等待重新审核", "success"); await loadClaims();
    } catch (error) { showError(error); }
  }

  async function splitClaim(claim) {
    const snapshot = selectedSnapshot();
    const value = window.prompt(claim.kind === "实体" ? "输入拆分后的实体名称" : "输入拆分后的关系声明");
    if (!snapshot || !value) return;
    const body = claim.kind === "实体"
      ? {newName:value, reason:"审阅工作台拆分"}
      : {newStatement:value, reason:"审阅工作台拆分"};
    try {
      await request(`/api/requirement-graphs/claims/${encodeURIComponent(claim.id)}/split`, {method:"POST", body:JSON.stringify(body)});
      notice?.show("声明已拆分，等待重新审核", "success"); await loadClaims();
    } catch (error) { showError(error); }
  }

  async function decideClaim(claimId, action) {
    const snapshot = selectedSnapshot();
    if (!snapshot) return;
    const reason = window.prompt(action === "verify" ? "审核备注（可选）" : "驳回原因");
    if (action === "reject" && reason === null) return;
    try {
      await request(`/api/requirement-graphs/claims/${encodeURIComponent(claimId)}/${action}`, {method:"POST", body:JSON.stringify({reason:reason || ""})});
      notice?.show(action === "verify" ? "声明已通过" : "声明已驳回", "success"); await loadClaims();
    } catch (error) { showError(error); }
  }

  function restoreContext() {
    const params = new URLSearchParams(location.search);
    $("#project-id").value = params.get("projectId") || localStorage.getItem("nexus_project_id") || "";
    $("#requirement-version").value = params.get("version") || "";
    if (window.NexusShell) NexusShell.setContext({projectId:$("#project-id").value, version:$("#requirement-version").value});
  }

  document.addEventListener("DOMContentLoaded", () => {
    restoreContext();
    $("#build-form").addEventListener("submit", startBuild);
    $("#search-form").addEventListener("submit", runSearch);
    $("#load-snapshots").addEventListener("click", loadSnapshots);
    $("#refresh-claims").addEventListener("click", loadClaims);
    $("#snapshot-id").addEventListener("change", () => { state.snapshot = selectedSnapshot(); loadClaims(); });
    $("#resume-button").addEventListener("click", resumeBuild);
    $("#cancel-button").addEventListener("click", cancelBuild);
    if ($("#project-id").value && $("#document-id").value && $("#requirement-version").value) loadSnapshots();
  });
})();
