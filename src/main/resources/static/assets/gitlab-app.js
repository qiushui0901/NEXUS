(function () {
  const {createApp} = Vue;
  const emptyConnectionForm = () => ({name:"",baseUrl:"https://gitlab.com",accessToken:""});
  const badge = {
    props:["status"],
    template:`<span class="status-badge" :class="'tone-'+tone"><span aria-hidden="true">{{glyph}}</span>{{label}}</span>`,
    computed:{
      label(){return NexusStatus.label(this.status);},
      glyph(){return NexusStatus.glyph(this.status);},
      tone(){return NexusStatus.tone(this.status);}
    }
  };

  createApp({
    components:{"status-badge":badge},
    data(){
      return {
        view:"list",loading:false,busy:false,polling:false,error:null,pollTimer:null,popHandler:null,
        projects:[],connections:[],businessProjects:[],targetBusinessProjectId:"",
        selected:null,selectedConnection:null,
        origin:location.origin,
        jobs:[],webhook:{},oneTimeSecret:null,secretVisible:false,
        filter:"",query:"",statuses:["PENDING","CLONING","SYNCING","INDEXING","READY","FAILED","DISABLED"],
        connectionForm:emptyConnectionForm(),reauthorizeToken:"",
        remotePage:{items:[],page:0,size:50,total:0},remoteQuery:"",
        selectedRemote:{},importConfigs:{},importResults:null,importStep:"select",
        branchOptions:{},branchLoading:{},branchErrors:{}
      };
    },
    computed:{
      visibleProjects(){
        const query=this.query.toLowerCase();
        return this.projects.filter(project=>(!this.filter||project.status===this.filter)
          &&(!query||[project.name,project.projectId,project.gitPath,project.branch]
            .some(value=>(value||"").toLowerCase().includes(query))));
      },
      runningCount(){return this.projects.filter(project=>["PENDING","CLONING","SYNCING","INDEXING"].includes(project.status)).length;},
      driftCount(){return this.projects.filter(project=>project.revisionDrift).length;},
      indexCount(){return this.projects.filter(project=>project.indexAvailable).length;},
      selectedRemoteItems(){return Object.values(this.selectedRemote);},
      branchesReady(){
        return this.selectedRemoteItems.length>0&&this.selectedRemoteItems.every(item=>
          !this.branchLoading[item.remoteProjectId]
          &&!this.branchErrors[item.remoteProjectId]
          &&(this.branchOptions[item.remoteProjectId]||[]).length>0);
      },
      importConfigsValid(){
        return Boolean(this.targetBusinessProjectId)&&this.branchesReady&&this.selectedRemoteItems.every(item=>{
          const config=this.importConfigs[item.remoteProjectId];
          return config&&["projectId","side","branch","codeCollection"]
            .every(field=>String(config[field]||"").trim());
        });
      },
      remotePages(){return Math.max(1,Math.ceil(this.remotePage.total/this.remotePage.size));},
      webhookBadge(){
        if(this.webhook.status==="ACCEPTED")return"SUCCESS";
        if(this.webhook.status==="NEVER_RECEIVED")return"IDLE";
        if(this.webhook.status==="DUPLICATE")return"STALE";
        return"FAILED";
      }
    },
    methods:{
      statusLabel:NexusStatus.label,
      glyph:NexusStatus.glyph,
      tone:NexusStatus.tone,
      versionState(project){
        if(project.revisionDrift)return"待更新";
        if(project.indexAvailable)return project.status==="FAILED"?"旧版可用":"已同步";
        return"尚未索引";
      },
      phaseLabel(value){
        return {QUEUED:"等待执行",CLONE:"准备仓库",FETCH:"拉取分支",RESOLVE_TARGET:"确认版本",
          INDEX:"建立索引",PUBLISH:"发布索引",FAILED:"同步失败",DISABLED:"项目停用",
          INTERRUPTED:"任务中断"}[value]||value||"未知阶段";
      },
      importStateLabel(value){
        return {AVAILABLE:"可导入",IMPORTED:"已导入",ARCHIVED:"已归档",
          NO_DEFAULT_BRANCH:"无默认分支",CONFLICT:"配置冲突"}[value]||value;
      },
      importStateStatus(value){
        return {AVAILABLE:"ACTIVE",IMPORTED:"READY",ARCHIVED:"DISABLED",
          NO_DEFAULT_BRANCH:"FAILED",CONFLICT:"STALE"}[value]||"IDLE";
      },
      shortSha(value){return value?value.slice(0,8):"无";},
      relativeTime(value){
        if(!value)return"无";
        const milliseconds=Date.now()-new Date(value).getTime();
        if(milliseconds<60000)return"刚刚";
        if(milliseconds<3600000)return Math.floor(milliseconds/60000)+" 分钟前";
        if(milliseconds<86400000)return Math.floor(milliseconds/3600000)+" 小时前";
        return new Date(value).toLocaleString("zh-CN");
      },
      show(message,type="success"){
        const safe=NexusErrors.normalize({message},message);
        NexusNotice.show(safe.message,type);
      },
      async copyText(value,message){
        if(!value)return;
        await navigator.clipboard.writeText(value);
        this.show(message);
      },
      resetSensitive(){
        this.connectionForm=emptyConnectionForm();
        this.reauthorizeToken="";
        this.importResults=null;
        this.importStep="select";
        this.branchOptions={};
        this.branchLoading={};
        this.branchErrors={};
        this.oneTimeSecret=null;
        this.secretVisible=false;
      },
      async loadProjects(silent=false){
        if(!silent){this.loading=true;this.error=null;}
        try{this.projects=await GitLabApi.projects();}
        catch(error){
          if(!silent)this.error=NexusErrors.normalize(error,"GitLab 项目加载失败").message;
        }finally{if(!silent)this.loading=false;}
      },
      async loadConnections(silent=false){
        if(!silent){this.loading=true;this.error=null;}
        try{this.connections=await GitLabApi.connections();}
        catch(error){
          if(!silent)this.error=NexusErrors.normalize(error,"GitLab 账号加载失败").message;
        }finally{if(!silent)this.loading=false;}
      },
      async applyRoute(){
        const suffix=location.pathname.slice("/settings/gitlab".length).replace(/^\/+|\/+$/g,"");
        this.resetSensitive();
        if(!suffix){this.view="list";this.selected=null;await this.loadProjects();return;}
        const parts=suffix.split("/").map(decodeURIComponent);
        if(parts[0]==="new"||(parts[0]==="accounts"&&parts[1]==="new")){
          this.view="connect";return;
        }
        if(parts[0]==="accounts"){
          if(!parts[1]){this.view="accounts";await this.loadConnections();return;}
          this.view="account";await this.openAccount(parts[1],false);return;
        }
        if(parts[0]==="projects"&&parts[1]){
          await this.openProject({projectId:parts[1]},false);return;
        }
        await this.openProject({projectId:parts[0]},false);
      },
      async refresh(){
        if(this.polling||this.loading||this.busy)return;
        this.polling=true;
        try{
          if(this.view==="detail")await this.openProject(this.selected,false,true);
          else if(this.view==="list")await this.loadProjects(true);
          else if(this.view==="accounts")await this.loadConnections(true);
          else if(this.view==="account")await this.loadRemoteProjects(this.remotePage.page,true);
        }finally{this.polling=false;}
      },
      goList(){
        this.resetSensitive();this.view="list";this.selected=null;
        history.pushState({},"","/settings/gitlab");this.loadProjects();
      },
      goAccounts(){
        this.resetSensitive();this.view="accounts";this.selectedConnection=null;
        history.pushState({},"","/settings/gitlab/accounts");this.loadConnections();
      },
      openConnect(){
        this.resetSensitive();this.view="connect";
        history.pushState({},"","/settings/gitlab/accounts/new");
      },
      async connectAccount(){
        this.busy=true;
        try{
          const connection=await GitLabApi.createConnection(this.connectionForm);
          this.connectionForm.accessToken="";
          this.show("GitLab 账号已关联");
          await this.openAccount(connection.id);
        }catch(error){this.show(error.message,"error");}
        finally{this.busy=false;}
      },
      async openAccount(connectionOrId,navigate=true){
        const id=typeof connectionOrId==="string"?connectionOrId:connectionOrId.id;
        this.view="account";this.loading=true;this.error=null;
        this.selectedRemote={};this.importConfigs={};this.importResults=null;this.importStep="select";
        this.branchOptions={};this.branchLoading={};this.branchErrors={};
        if(navigate)history.pushState({},"","/settings/gitlab/accounts/"+encodeURIComponent(id));
        try{
          [this.selectedConnection,this.businessProjects]=await Promise.all([
            GitLabApi.connection(id),GitLabApi.businessProjects()
          ]);
          if(!this.businessProjects.some(project=>project.id===this.targetBusinessProjectId)){
            this.targetBusinessProjectId=this.businessProjects[0]?.id||"";
          }
          await this.loadRemoteProjects(0);
        }catch(error){
          this.error=NexusErrors.normalize(error,"GitLab 账号项目加载失败").message;
          this.show(this.error,"error");
        }finally{this.loading=false;}
      },
      async verifyAccount(){
        this.busy=true;
        try{
          this.selectedConnection=await GitLabApi.verifyConnection(this.selectedConnection.id);
          this.show("账号连接验证通过");
          await this.loadRemoteProjects(0);
        }catch(error){this.show(error.message,"error");}
        finally{this.busy=false;}
      },
      async reauthorizeAccount(){
        if(!this.reauthorizeToken)return;
        this.busy=true;
        try{
          this.selectedConnection=await GitLabApi.reauthorizeConnection(
            this.selectedConnection.id,this.reauthorizeToken);
          this.reauthorizeToken="";
          this.show("账号已重新授权");
          await this.loadRemoteProjects(0);
        }catch(error){this.show(error.message,"error");}
        finally{this.busy=false;}
      },
      async disableAccount(){
        if(!confirm("确认停用该 GitLab 账号连接？已发布索引仍可读取。"))return;
        this.busy=true;
        try{
          this.selectedConnection=await GitLabApi.disableConnection(this.selectedConnection.id);
          this.show("账号连接已停用");
        }catch(error){this.show(error.message,"error");}
        finally{this.busy=false;}
      },
      async loadRemoteProjects(page=0,silent=false){
        if(!this.selectedConnection)return;
        if(!silent){this.loading=true;this.error=null;}
        try{
          this.remotePage=await GitLabApi.remoteProjects(this.selectedConnection.id,{
            page,size:this.remotePage.size,query:this.remoteQuery
          });
          this.remotePage.items.forEach(item=>{
            if(this.selectedRemote[item.remoteProjectId])this.selectedRemote[item.remoteProjectId]=item;
          });
        }catch(error){
          if(!silent)this.error=NexusErrors.normalize(error,"GitLab 项目发现失败").message;
        }finally{if(!silent)this.loading=false;}
      },
      selectable(project){return project.importState==="AVAILABLE";},
      isSelected(project){return Boolean(this.selectedRemote[project.remoteProjectId]);},
      toggleRemote(project){
        if(!this.selectable(project))return;
        if(this.selectedRemote[project.remoteProjectId]){
          delete this.selectedRemote[project.remoteProjectId];
          delete this.importConfigs[project.remoteProjectId];
          delete this.branchOptions[project.remoteProjectId];
          delete this.branchLoading[project.remoteProjectId];
          delete this.branchErrors[project.remoteProjectId];
        }else{
          this.selectedRemote[project.remoteProjectId]=project;
          this.importConfigs[project.remoteProjectId]={
            remoteProjectId:project.remoteProjectId,projectId:project.projectId,
            side:project.side||"server",branch:project.defaultBranch,
            codeCollection:project.codeCollection
          };
        }
        this.selectedRemote={...this.selectedRemote};
        this.importConfigs={...this.importConfigs};
      },
      branchLabel(branch){
        const states=[];
        if(branch.defaultBranch)states.push("默认");
        if(branch.protectedBranch)states.push("受保护");
        if(branch.merged)states.push("已合并");
        return branch.name+(states.length?`（${states.join(" · ")}）`:"");
      },
      async loadRemoteBranches(remote){
        const id=remote.remoteProjectId;
        this.branchLoading={...this.branchLoading,[id]:true};
        const errors={...this.branchErrors};delete errors[id];this.branchErrors=errors;
        try{
          const branches=(await GitLabApi.remoteBranches(this.selectedConnection.id,id))
            .map(branch=>({...branch,
              defaultBranch:branch.defaultBranch||branch.name===remote.defaultBranch}));
          if(!branches.length)throw new Error("该项目没有可选分支");
          this.branchOptions={...this.branchOptions,[id]:branches};
          const current=this.importConfigs[id].branch;
          if(!branches.some(branch=>branch.name===current)){
            const fallback=branches.find(branch=>branch.defaultBranch)||branches[0];
            this.importConfigs[id].branch=fallback.name;
            this.importConfigs={...this.importConfigs};
          }
        }catch(error){
          this.branchErrors={...this.branchErrors,[id]:
            NexusErrors.normalize(error,"分支列表加载失败").message};
        }finally{
          this.branchLoading={...this.branchLoading,[id]:false};
        }
      },
      clearRemoteSelection(){
        this.selectedRemote={};
        this.importConfigs={};
        this.branchOptions={};
        this.branchLoading={};
        this.branchErrors={};
        this.importStep="select";
      },
      async openImportConfig(){
        if(!this.selectedRemoteItems.length)return;
        this.importResults=null;
        this.importStep="configure";
        await Promise.all(this.selectedRemoteItems.map(remote=>this.loadRemoteBranches(remote)));
        this.$nextTick(()=>document.querySelector(".import-review")?.scrollIntoView({
          behavior:"smooth",block:"start"
        }));
      },
      backToProjectSelection(){
        this.importStep="select";
      },
      async importSelected(){
        if(this.importStep!=="configure"||!this.importConfigsValid)return;
        const projects=this.selectedRemoteItems.map(item=>this.importConfigs[item.remoteProjectId]);
        if(!projects.length)return;
        if(!confirm(`确认导入 ${projects.length} 个项目并立即开始首次同步？`))return;
        this.busy=true;
        try{
          this.importResults=await GitLabApi.importProjects(
            this.selectedConnection.id,this.targetBusinessProjectId,projects);
          this.show(`已接受 ${this.importResults.accepted} 个项目，失败 ${this.importResults.failed} 个`,
            this.importResults.failed?"error":"success");
          this.importResults.results.filter(item=>item.status==="ACCEPTED").forEach(item=>{
            delete this.selectedRemote[item.remoteProjectId];
            delete this.importConfigs[item.remoteProjectId];
          });
          this.selectedRemote={...this.selectedRemote};
          if(!this.selectedRemoteItems.length)this.importStep="select";
          await Promise.all([this.loadRemoteProjects(this.remotePage.page),this.loadProjects(true)]);
        }catch(error){this.show(error.message,"error");}
        finally{this.busy=false;}
      },
      async openProject(project,navigate=true,silent=false){
        if(!silent){
          this.resetSensitive();this.view="detail";
          this.selected={projectId:project.projectId,name:project.name||project.projectId,gitPath:"",branch:""};
          this.error=null;this.loading=true;
        }
        if(navigate)history.pushState({},"","/settings/gitlab/projects/"+encodeURIComponent(project.projectId));
        try{
          this.projects=await GitLabApi.projects();
          const current=this.projects.find(item=>item.projectId===project.projectId);
          if(!current)throw new Error("未找到 GitLab 项目："+project.projectId);
          this.selected=current;
          [this.jobs,this.webhook]=await Promise.all([
            GitLabApi.jobs(project.projectId),GitLabApi.webhook(project.projectId)
          ]);
        }catch(error){
          if(!silent){
            this.error=NexusErrors.normalize(error,"GitLab 项目详情加载失败").message;
            this.show(this.error,"error");
          }
        }finally{if(!silent)this.loading=false;}
      },
      async action(operation,message){
        this.busy=true;
        try{const project=await operation();this.show(message);await this.openProject(project,false);}
        catch(error){this.show(error.message,"error");}
        finally{this.busy=false;}
      },
      sync(){return this.action(()=>GitLabApi.sync(this.selected.projectId),"同步任务已提交");},
      retry(){return this.action(()=>GitLabApi.retry(this.selected.projectId),"重试任务已提交");},
      enable(){
        if(confirm("确认重新启用该 GitLab 项目并同步远端最新版本？")){
          return this.action(()=>GitLabApi.enable(this.selected.projectId),"项目已重新启用，同步任务已提交");
        }
      },
      disable(){
        if(confirm("确认停用该 GitLab 项目？已有索引与历史记录会保留。")){
          return this.action(()=>GitLabApi.disable(this.selected.projectId),"项目已停用");
        }
      },
      async rotateSecret(){
        if(!confirm("轮换后，GitLab 中的 Webhook Secret 也必须立即更新。继续？"))return;
        this.busy=true;
        try{
          const result=await GitLabApi.rotateSecret(this.selected.projectId);
          this.oneTimeSecret=result.webhookSecret;this.secretVisible=false;
          this.show("Webhook Secret 已轮换，仅显示一次");
        }catch(error){this.show(error.message,"error");}
        finally{this.busy=false;}
      },
      async loadJob(job){
        try{
          const detail=await GitLabApi.job(this.selected.projectId,job.id);
          this.show(detail.events.map(event=>this.phaseLabel(event.phase)).join(" → ")||"暂无阶段事件");
        }catch(error){this.show(error.message,"error");}
      }
    },
    mounted(){
      this.popHandler=()=>this.applyRoute();
      window.addEventListener("popstate",this.popHandler);
      this.applyRoute();
      this.pollTimer=setInterval(()=>{
        if(document.visibilityState==="visible"&&this.projects.some(project=>
          ["PENDING","CLONING","SYNCING","INDEXING"].includes(project.status)))this.refresh();
      },3000);
    },
    beforeUnmount(){
      if(this.pollTimer)clearInterval(this.pollTimer);
      if(this.popHandler)window.removeEventListener("popstate",this.popHandler);
      this.resetSensitive();
    }
  }).mount("#app");
})();
