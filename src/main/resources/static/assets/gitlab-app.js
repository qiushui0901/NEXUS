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
        view:"list",loading:false,busy:false,error:null,pollTimer:null,popHandler:null,
        projects:[],connections:[],selected:null,selectedConnection:null,
        origin:location.origin,
        jobs:[],webhook:{},oneTimeSecret:null,secretVisible:false,
        filter:"",query:"",statuses:["PENDING","CLONING","SYNCING","INDEXING","READY","FAILED","DISABLED"],
        connectionForm:emptyConnectionForm(),reauthorizeToken:"",
        remotePage:{items:[],page:0,size:50,total:0},remoteQuery:"",
        selectedRemote:{},importConfigs:{},importResults:null
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
        this.oneTimeSecret=null;
        this.secretVisible=false;
      },
      async loadProjects(){
        this.loading=true;this.error=null;
        try{this.projects=await GitLabApi.projects();}
        catch(error){this.error=NexusErrors.normalize(error,"GitLab 项目加载失败").message;}
        finally{this.loading=false;}
      },
      async loadConnections(){
        this.loading=true;this.error=null;
        try{this.connections=await GitLabApi.connections();}
        catch(error){this.error=NexusErrors.normalize(error,"GitLab 账号加载失败").message;}
        finally{this.loading=false;}
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
      refresh(){
        if(this.view==="detail")return this.openProject(this.selected,false);
        if(this.view==="list")return this.loadProjects();
        if(this.view==="accounts")return this.loadConnections();
        if(this.view==="account")return this.loadRemoteProjects(this.remotePage.page);
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
        this.selectedRemote={};this.importConfigs={};this.importResults=null;
        if(navigate)history.pushState({},"","/settings/gitlab/accounts/"+encodeURIComponent(id));
        try{
          this.selectedConnection=await GitLabApi.connection(id);
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
      async loadRemoteProjects(page=0){
        if(!this.selectedConnection)return;
        this.loading=true;this.error=null;
        try{
          this.remotePage=await GitLabApi.remoteProjects(this.selectedConnection.id,{
            page,size:this.remotePage.size,query:this.remoteQuery
          });
          this.remotePage.items.forEach(item=>{
            if(this.selectedRemote[item.remoteProjectId])this.selectedRemote[item.remoteProjectId]=item;
          });
        }catch(error){this.error=NexusErrors.normalize(error,"GitLab 项目发现失败").message;}
        finally{this.loading=false;}
      },
      selectable(project){return project.importState==="AVAILABLE";},
      isSelected(project){return Boolean(this.selectedRemote[project.remoteProjectId]);},
      toggleRemote(project){
        if(!this.selectable(project))return;
        if(this.selectedRemote[project.remoteProjectId]){
          delete this.selectedRemote[project.remoteProjectId];
          delete this.importConfigs[project.remoteProjectId];
        }else{
          this.selectedRemote[project.remoteProjectId]=project;
          this.importConfigs[project.remoteProjectId]={
            remoteProjectId:project.remoteProjectId,projectId:project.projectId,
            side:project.side||"server",branch:project.defaultBranch,
            requirementCollection:project.requirementCollection,
            codeCollection:project.codeCollection
          };
        }
        this.selectedRemote={...this.selectedRemote};
        this.importConfigs={...this.importConfigs};
      },
      async importSelected(){
        const projects=this.selectedRemoteItems.map(item=>this.importConfigs[item.remoteProjectId]);
        if(!projects.length)return;
        this.busy=true;
        try{
          this.importResults=await GitLabApi.importProjects(this.selectedConnection.id,projects);
          this.show(`已接受 ${this.importResults.accepted} 个项目，失败 ${this.importResults.failed} 个`,
            this.importResults.failed?"error":"success");
          this.importResults.results.filter(item=>item.status==="ACCEPTED").forEach(item=>{
            delete this.selectedRemote[item.remoteProjectId];
            delete this.importConfigs[item.remoteProjectId];
          });
          this.selectedRemote={...this.selectedRemote};
          await Promise.all([this.loadRemoteProjects(this.remotePage.page),this.loadProjects()]);
        }catch(error){this.show(error.message,"error");}
        finally{this.busy=false;}
      },
      async openProject(project,navigate=true){
        this.resetSensitive();this.view="detail";
        this.selected={projectId:project.projectId,name:project.name||project.projectId,gitPath:"",branch:""};
        this.error=null;this.loading=true;
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
          this.error=NexusErrors.normalize(error,"GitLab 项目详情加载失败").message;
          this.show(this.error,"error");
        }finally{this.loading=false;}
      },
      async action(operation,message){
        this.busy=true;
        try{const project=await operation();this.show(message);await this.openProject(project,false);}
        catch(error){this.show(error.message,"error");}
        finally{this.busy=false;}
      },
      sync(){return this.action(()=>GitLabApi.sync(this.selected.projectId),"同步任务已提交");},
      retry(){return this.action(()=>GitLabApi.retry(this.selected.projectId),"重试任务已提交");},
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
