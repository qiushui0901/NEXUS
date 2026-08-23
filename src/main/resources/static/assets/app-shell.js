(function (global) {
  const VERSION = "0.9.3";
  const pages = [
    ["home", "总览", "/"],
    ["knowledge", "知识库", "/knowledge"],
    ["requirement-graph", "需求图", "/requirement-graph.html"],
    ["wiki", "Wiki", "/wiki"],
    ["monitor", "代码", "/monitor"],
    ["gitlab", "GitLab", "/settings/gitlab"]
  ];
  const pageTitles = Object.fromEntries(pages.map(([id, label]) => [id, label]));
  pageTitles.versions = "版本";

  function readContext() {
    const params = new URLSearchParams(location.search);
    return {
      projectId: params.get("projectId") || localStorage.getItem("nexus_project_id") || "",
      version: params.get("version") || ""
    };
  }
  let liveContext = readContext();

  function context() {
    return {...liveContext};
  }

  function href(path) {
    const current = context();
    const url = new URL(path, location.origin);
    if (current.projectId && !path.startsWith("/settings/gitlab")) {
      url.searchParams.set("projectId", current.projectId);
    }
    if (current.version && ["/wiki", "/monitor"].some(prefix => path.startsWith(prefix))) {
      url.searchParams.set("version", current.version);
    }
    return url.pathname + url.search;
  }

  function navLinks(active, mobile) {
    return pages.map(([id, label, path]) => {
      const link = document.createElement("a");
      link.href = href(path);
      link.textContent = label;
      if (id === active) link.setAttribute("aria-current", "page");
      if (mobile) link.addEventListener("click", () => closeMobile());
      return link;
    });
  }

  function closeMobile() {
    const drawer = document.querySelector(".nexus-mobile-drawer");
    const button = document.querySelector(".nexus-menu-button");
    if (drawer) drawer.classList.remove("open");
    if (button) button.setAttribute("aria-expanded", "false");
  }

  function renderContext(inner, title) {
    inner.replaceChildren();
    [
      {value: title, current: true},
      {value: liveContext.projectId, current: false},
      {value: liveContext.version, current: false}
    ].filter(item => item.value).forEach((entry, index) => {
      if (index) {
        const separator = document.createElement("span");
        separator.className = "nexus-context-separator";
        separator.textContent = "/";
        inner.append(separator);
      }
      const item = document.createElement(entry.current ? "strong" : "span");
      item.textContent = entry.value;
      inner.append(item);
    });
  }

  function refreshContext(root) {
    const shell = root._nexusShell;
    if (!shell) return;
    shell.brand.href = href("/");
    shell.nav.replaceChildren(...navLinks(shell.active, false));
    shell.mobile.replaceChildren(...navLinks(shell.active, true));
    renderContext(shell.contextInner, shell.title);
  }

  function applyContext(next) {
    if (Object.prototype.hasOwnProperty.call(next, "projectId")) {
      liveContext.projectId = next.projectId || "";
      if (liveContext.projectId) localStorage.setItem("nexus_project_id", liveContext.projectId);
      else localStorage.removeItem("nexus_project_id");
    }
    if (Object.prototype.hasOwnProperty.call(next, "version")) {
      liveContext.version = next.version || "";
    }
    document.querySelectorAll("[data-nexus-shell]").forEach(refreshContext);
  }

  function setContext(next) {
    global.dispatchEvent(new CustomEvent("nexus:context-changed", {detail: next || {}}));
  }

  function render(root) {
    const active = root.dataset.page || "home";
    const title = pageTitles[active] || "工作台";
    root.className = "nexus-app-shell";

    const bar = document.createElement("div");
    bar.className = "nexus-app-bar";

    const menu = document.createElement("button");
    menu.type = "button";
    menu.className = "nexus-menu-button";
    menu.setAttribute("aria-label", "打开主导航");
    menu.setAttribute("aria-expanded", "false");
    menu.textContent = "☰";

    const brand = document.createElement("a");
    brand.className = "nexus-brand";
    brand.href = href("/");
    brand.innerHTML = '<span class="nexus-brand-mark">NX</span><span>NEXUS</span><small class="nexus-brand-version">0.9.3</small>';

    const brandGroup = document.createElement("div");
    brandGroup.style.display = "flex";
    brandGroup.style.alignItems = "center";
    brandGroup.style.gap = "8px";
    brandGroup.append(menu, brand);

    const nav = document.createElement("nav");
    nav.className = "nexus-primary-nav";
    nav.setAttribute("aria-label", "主导航");
    nav.append(...navLinks(active, false));

    const actions = document.createElement("div");
    actions.className = "nexus-shell-actions";

    const health = document.createElement("button");
    health.type = "button";
    health.className = "nexus-shell-button";
    health.dataset.healthButton = "";
    health.title = "服务状态";
    health.innerHTML = '<span class="nexus-health-dot"></span><span class="label">服务</span>';

    const settings = document.createElement("button");
    settings.type = "button";
    settings.className = "nexus-shell-button";
    settings.dataset.connectionButton = "";
    settings.title = "连接设置";
    settings.innerHTML = '<span aria-hidden="true">⚙</span><span class="label">连接</span>';
    actions.append(health, settings);
    bar.append(brandGroup, nav, actions);

    const mobile = document.createElement("nav");
    mobile.className = "nexus-mobile-drawer";
    mobile.setAttribute("aria-label", "移动端主导航");
    mobile.append(...navLinks(active, true));

    const contextBar = document.createElement("div");
    contextBar.className = "nexus-context-bar";
    const contextInner = document.createElement("div");
    contextInner.className = "nexus-context-inner";
    renderContext(contextInner, title);
    contextBar.append(contextInner);

    const dialog = document.createElement("dialog");
    dialog.className = "nexus-connection-dialog";
    dialog.innerHTML = `
      <form method="dialog">
        <div class="nexus-dialog-head"><h2>连接设置</h2><button value="cancel" aria-label="关闭">×</button></div>
        <div class="nexus-dialog-body">
          <label>API Key<input data-api-key type="password" autocomplete="off" placeholder="启用认证时填写"></label>
          <p>API Key 仅保存在当前浏览器本地，用于向 NEXUS API 发送 <code>X-API-Key</code>。</p>
          <p><a href="/settings/gitlab">管理 GitLab 集成</a> · <a href="/actuator/health">查看服务状态</a></p>
        </div>
        <div class="nexus-dialog-actions"><button value="cancel">取消</button><button class="primary" value="save">保存连接</button></div>
      </form>`;

    const notices = document.createElement("div");
    notices.className = "nexus-notice-region";
    notices.id = "nexus-notice-region";
    notices.setAttribute("aria-live", "polite");
    root.append(bar, contextBar, mobile, dialog, notices);
    root._nexusShell = {active, title, brand, nav, mobile, contextInner};

    menu.addEventListener("click", () => {
      const open = !mobile.classList.contains("open");
      mobile.classList.toggle("open", open);
      menu.setAttribute("aria-expanded", String(open));
    });
    settings.addEventListener("click", () => {
      dialog.querySelector("[data-api-key]").value = global.NexusApi?.apiKey() || "";
      dialog.showModal();
    });
    dialog.addEventListener("close", () => {
      if (dialog.returnValue === "save") {
        global.NexusApi?.setApiKey(dialog.querySelector("[data-api-key]").value);
        global.NexusNotice?.show("连接设置已保存", "success");
      }
    });
    health.addEventListener("click", () => location.assign("/actuator/health"));
    document.addEventListener("keydown", event => {
      if (event.key === "Escape") closeMobile();
    });

    global.NexusApi?.request("/actuator/health", {timeout: 4000})
      .then(() => health.querySelector(".nexus-health-dot").classList.add("ready"))
      .catch(() => health.querySelector(".nexus-health-dot").classList.add("failed"));
  }

  function showNotice(message, type) {
    const region = document.getElementById("nexus-notice-region");
    if (!region) return;
    while (region.children.length >= 2) region.firstElementChild.remove();
    const notice = document.createElement("div");
    notice.className = "nexus-notice " + (type || "info");
    const glyph = document.createElement("span");
    glyph.textContent = type === "error" ? "!" : type === "success" ? "✓" : "i";
    const text = document.createElement("span");
    text.className = "nexus-notice-message";
    text.textContent = global.NexusErrors?.plainText(message) || "操作未完成";
    const close = document.createElement("button");
    close.type = "button";
    close.setAttribute("aria-label", "关闭通知");
    close.textContent = "×";
    close.addEventListener("click", () => notice.remove());
    notice.append(glyph, text, close);
    region.append(notice);
    setTimeout(() => notice.remove(), 6000);
  }

  global.addEventListener("nexus:context-changed", event => applyContext(event.detail || {}));
  global.NexusShell = {version: VERSION, href, context, setContext};
  global.NexusNotice = {show: showNotice};
  const boot = () => document.querySelectorAll("[data-nexus-shell]").forEach(render);
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
  else boot();
})(window);
