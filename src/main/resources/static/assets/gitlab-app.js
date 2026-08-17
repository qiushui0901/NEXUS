(function () {
  const {createApp} = Vue;
  const badge = {
    props: ["status"],
    template: `<span class="status-badge" :class="'tone-'+tone"><span aria-hidden="true">{{glyph}}</span>{{label}}</span>`,
    computed: {
      label() { return NexusStatus.label(this.status); },
      glyph() { return NexusStatus.glyph(this.status); },
      tone() { return NexusStatus.tone(this.status); }
    }
  };

  createApp({
    components: {"status-badge": badge},
    data() {
      return {
        view: "list",
        loading: false,
        busy: false,
        error: null,
        pollTimer: null,
        popHandler: null,
        projects: [],
        selected: null,
        jobs: [],
        webhook: {},
        oneTimeSecret: null,
        secretVisible: false,
        showWizardSecret: false,
        filter: "",
        query: "",
        step: 1,
        checks: {},
        statuses: ["PENDING", "CLONING", "SYNCING", "INDEXING", "READY", "FAILED", "DISABLED"],
        stepNames: ["连接", "项目与分支", "索引配置", "Webhook", "确认"],
        form: {
          projectId: "",
          name: "",
          group: "default",
          side: "server",
          cloneUrl: "",
          branch: "main",
          gitPath: "",
          requirementCollection: "",
          codeCollection: "",
          accessToken: "",
          webhookSecret: ""
        }
      };
    },
    computed: {
      visibleProjects() {
        const query = this.query.toLowerCase();
        return this.projects.filter(project =>
          (!this.filter || project.status === this.filter)
          && (!query || [project.name, project.projectId, project.gitPath, project.branch]
            .some(value => (value || "").toLowerCase().includes(query))));
      },
      runningCount() {
        return this.projects.filter(project =>
          ["PENDING", "CLONING", "SYNCING", "INDEXING"].includes(project.status)).length;
      },
      driftCount() {
        return this.projects.filter(project => project.revisionDrift).length;
      },
      indexCount() {
        return this.projects.filter(project => project.indexAvailable).length;
      },
      webhookPreview() {
        return location.origin + "/api/webhooks/gitlab/"
          + encodeURIComponent(this.form.projectId || "project-id");
      },
      webhookBadge() {
        if (this.webhook.status === "ACCEPTED") return "SUCCESS";
        if (this.webhook.status === "NEVER_RECEIVED") return "IDLE";
        if (this.webhook.status === "DUPLICATE") return "STALE";
        return "FAILED";
      }
    },
    methods: {
      statusLabel: NexusStatus.label,
      glyph: NexusStatus.glyph,
      tone: NexusStatus.tone,
      stepMarker(n){return n<this.step?"✓":n},
      versionState(project) {
        if (project.revisionDrift) return "待更新";
        if (project.indexAvailable) return project.status === "FAILED" ? "旧版可用" : "已同步";
        return "尚未索引";
      },
      phaseLabel(value) {
        return {
          QUEUED: "等待执行",
          CLONE: "准备仓库",
          FETCH: "拉取分支",
          RESOLVE_TARGET: "确认版本",
          INDEX: "建立索引",
          PUBLISH: "发布索引",
          FAILED: "同步失败",
          DISABLED: "项目停用",
          INTERRUPTED: "任务中断"
        }[value] || value || "未知阶段";
      },
      shortSha(value) {
        return value ? value.slice(0, 8) : "无";
      },
      relativeTime(value) {
        if (!value) return "无";
        const milliseconds = Date.now() - new Date(value).getTime();
        if (milliseconds < 60000) return "刚刚";
        if (milliseconds < 3600000) return Math.floor(milliseconds / 60000) + " 分钟前";
        if (milliseconds < 86400000) return Math.floor(milliseconds / 3600000) + " 小时前";
        return new Date(value).toLocaleString("zh-CN");
      },
      show(message, type = "success") {
        const safe = NexusErrors.normalize({message}, message);
        NexusNotice.show(safe.message, type);
      },
      async copyText(value, message) {
        if (!value) return;
        await navigator.clipboard.writeText(value);
        this.show(message);
      },
      async load() {
        this.loading = true;
        this.error = null;
        try {
          this.projects = await GitLabApi.projects();
        } catch (error) {
          this.error = NexusErrors.normalize(error, "GitLab 项目加载失败").message;
        } finally {
          this.loading = false;
        }
      },
      async applyRoute() {
        const suffix = location.pathname.slice("/settings/gitlab".length).replace(/^\/+|\/+$/g, "");
        if (!suffix) {
          this.view = "list";
          this.selected = null;
          await this.load();
          return;
        }
        if (suffix === "new") {
          this.view = "wizard";
          this.step = 1;
          return;
        }
        const projectId = decodeURIComponent(suffix);
        this.selected = {projectId, name: projectId, gitPath: "", branch: ""};
        await this.openProject(this.selected, false);
      },
      refresh() {
        if (this.view === "detail") this.openProject(this.selected,false);
        else if (this.view === "list") this.load();
      },
      openWizard() {
        this.view = "wizard";
        this.step = 1;
        history.pushState({}, "", "/settings/gitlab/new");
      },
      goList() {
        this.view = "list";
        this.selected = null;
        this.oneTimeSecret = null;
        this.secretVisible = false;
        history.pushState({}, "", "/settings/gitlab");
        this.load();
      },
      async openProject(project, navigate = true) {
        this.view = "detail";
        this.selected = project;
        this.oneTimeSecret = null;
        this.secretVisible = false;
        this.error = null;
        this.loading = true;
        if (navigate) history.pushState({}, "", "/settings/gitlab/" + encodeURIComponent(project.projectId));
        try {
          const all = await GitLabApi.projects();
          this.projects = all;
          const current = all.find(item => item.projectId === project.projectId);
          if (!current) throw new Error("未找到 GitLab 项目：" + project.projectId);
          this.selected = current;
          [this.jobs, this.webhook] = await Promise.all([
            GitLabApi.jobs(project.projectId),
            GitLabApi.webhook(project.projectId)
          ]);
        } catch (error) {
          this.error = NexusErrors.normalize(error, "GitLab 项目详情加载失败").message;
          this.show(this.error, "error");
        } finally {
          this.loading = false;
        }
      },
      async testConnection() {
        this.busy = true;
        try {
          const result = await GitLabApi.validateConnection({
            cloneUrl: this.form.cloneUrl,
            branch: this.form.branch,
            accessToken: this.form.accessToken
          });
          this.checks.connection = result;
          if (!this.form.gitPath) this.form.gitPath = result.repositoryPath;
          this.show("连接与分支验证通过");
          return true;
        } catch (error) {
          this.show(error.message, "error");
          return false;
        } finally {
          this.busy = false;
        }
      },
      defaults() {
        if (!this.form.projectId) return;
        if (!this.form.name) this.form.name = this.form.projectId;
        const prefix = this.form.projectId.replace(/[.-]/g, "_");
        if (!this.form.requirementCollection) this.form.requirementCollection = prefix + "_requirements";
        if (!this.form.codeCollection) this.form.codeCollection = prefix + "_code";
      },
      async nextStep() {
        this.busy = true;
        try {
          if (this.step === 1) {
            if (!this.checks.connection && !(await this.testConnection())) return;
            this.step = 2;
          } else if (this.step === 2) {
            this.defaults();
            await GitLabApi.validateProject({
              projectId: this.form.projectId,
              gitPath: this.form.gitPath
            });
            this.step = 3;
          } else if (this.step === 3) {
            await GitLabApi.validateConfig({
              requirementCollection: this.form.requirementCollection,
              codeCollection: this.form.codeCollection,
              webhookSecret: null
            });
            this.step = 4;
          } else if (this.step === 4) {
            await GitLabApi.validateConfig({
              requirementCollection: this.form.requirementCollection,
              codeCollection: this.form.codeCollection,
              webhookSecret: this.form.webhookSecret
            });
            this.step = 5;
          } else {
            const created = await GitLabApi.create(this.form);
            this.form.accessToken = "";
            this.form.webhookSecret = "";
            this.show("项目已创建，首次同步已开始");
            await this.openProject(created);
          }
        } catch (error) {
          this.show(error.message, "error");
        } finally {
          this.busy = false;
        }
      },
      generateSecret() {
        const bytes = new Uint8Array(24);
        crypto.getRandomValues(bytes);
        this.form.webhookSecret = Array.from(bytes,
          value => value.toString(16).padStart(2, "0")).join("");
      },
      async action(operation, message) {
        this.busy = true;
        try {
          const project = await operation();
          this.show(message);
          await this.openProject(project, false);
        } catch (error) {
          this.show(error.message, "error");
        } finally {
          this.busy = false;
        }
      },
      sync() {
        return this.action(() => GitLabApi.sync(this.selected.projectId), "同步任务已提交");
      },
      retry() {
        return this.action(() => GitLabApi.retry(this.selected.projectId), "重试任务已提交");
      },
      disable() {
        if (confirm("确认停用该 GitLab 项目？已有索引与历史记录会保留。")) {
          return this.action(() => GitLabApi.disable(this.selected.projectId), "项目已停用");
        }
      },
      async rotateSecret() {
        if (!confirm("轮换后，GitLab 中的 Webhook Secret 也必须立即更新。继续？")) return;
        this.busy = true;
        try {
          const result = await GitLabApi.rotateSecret(this.selected.projectId);
          this.oneTimeSecret = result.webhookSecret;
          this.secretVisible = false;
          this.show("Webhook Secret 已轮换，仅显示一次");
        } catch (error) {
          this.show(error.message, "error");
        } finally {
          this.busy = false;
        }
      },
      async loadJob(job) {
        try {
          const detail = await GitLabApi.job(this.selected.projectId, job.id);
          this.show(detail.events.map(event => this.phaseLabel(event.phase)).join(" → ") || "暂无阶段事件");
        } catch (error) {
          this.show(error.message, "error");
        }
      }
    },
    mounted() {
      this.popHandler = () => this.applyRoute();
      window.addEventListener("popstate", this.popHandler);
      this.applyRoute();
      this.pollTimer = setInterval(() => {
        if (document.visibilityState === "visible"
            && this.projects.some(project =>
              ["PENDING", "CLONING", "SYNCING", "INDEXING"].includes(project.status))) {
          this.refresh();
        }
      }, 3000);
    },
    beforeUnmount() {
      if (this.pollTimer) clearInterval(this.pollTimer);
      if (this.popHandler) window.removeEventListener("popstate", this.popHandler);
    }
  }).mount("#app");
})();
