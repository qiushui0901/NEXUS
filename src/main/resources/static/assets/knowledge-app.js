(function () {
  const {createApp} = Vue;
  const status = window.NexusStatus;
  const api = window.KnowledgeApi;

  const StatusBadge = {
    props: ["status"],
    template: `<span class="status-badge" :class="'tone-'+tone"><span aria-hidden="true">{{ glyph }}</span>{{ label }}</span>`,
    computed: {
      label() { return status.label(this.status); },
      glyph() { return status.glyph(this.status); },
      tone() { return status.tone(this.status); }
    }
  };
  const EmptyState = {props:["title","message"], template:`<div class="empty-state"><strong>{{title}}</strong><p>{{message}}</p></div>`};
  const LoadingState = {template:`<div class="loading-state"><span aria-hidden="true"></span>正在读取状态…</div>`};
  const Breadcrumb = {
    props:["items"],
    template:`<nav class="breadcrumb" aria-label="面包屑"><template v-for="(item,index) in items"><button v-if="item.action" type="button" @click="item.action">{{item.label}}</button><span v-else>{{item.label}}</span><i v-if="index<items.length-1">/</i></template></nav>`
  };
  const PageControls = {
    props:["page","size","total"],
    emits:["change"],
    computed:{pages(){return Math.max(1,Math.ceil(this.total/this.size));}},
    template:`<div class="pagination" v-if="total>size"><button type="button" :disabled="page<=0" @click="$emit('change',page-1)">← 上一页</button><span>第 {{page+1}} / {{pages}} 页</span><button type="button" :disabled="page+1>=pages" @click="$emit('change',page+1)">下一页 →</button></div>`
  };

  createApp({
    components:{StatusBadge,EmptyState,LoadingState,Breadcrumb,PageControls},
    data() {
      return {
        view:"bases", loading:false, actionBusy:false, stageCollapsed:false,
        bases:[], documents:[], chunks:[], latestRun:null,
        selectedBase:null, selectedDocument:null, selectedChunk:null,
        basePage:{page:0,size:50,total:0}, documentPage:{page:0,size:50,total:0}, chunkPage:{page:0,size:50,total:0},
        filters:{projectId:"",status:"",type:"",query:"",documentStatus:"",documentQuery:"",chunkStatus:"",chunkQuery:""},
        stages:["DISCOVER","PARSE","CLEAN","CHUNK","DEDUPLICATE","EMBED","INDEX","VERIFY","PUBLISH"],
        statusOptions:["IDLE","RUNNING","READY","PARTIAL","FAILED","STALE","DISABLED"],
        entityStatusOptions:["PENDING","RUNNING","CHUNKED","EMBEDDING","INDEXING","READY","FAILED","EXCLUDED","INTERRUPTED"],
        retrieval:{query:"",version:"",limit:10,loading:false,response:null},
        pollTimer:null, failedPolls:0
      };
    },
    computed:{
      projectOptions(){return [...new Set(this.bases.map(item=>item.projectId))].sort();},
      visibleBases(){
        const q=this.filters.query.toLowerCase();
        return this.bases.filter(item=>(!this.filters.status||item.status===this.filters.status)
          &&(!this.filters.type||item.type===this.filters.type)
          &&(!q||[item.name,item.projectId,item.collection].some(v=>String(v||"").toLowerCase().includes(q))));
      },
      visibleDocuments(){
        const q=this.filters.documentQuery.toLowerCase();
        return this.documents.filter(item=>(!this.filters.documentStatus||item.status===this.filters.documentStatus)
          &&(!q||[item.sourcePath,item.error&&item.error.code].some(v=>String(v||"").toLowerCase().includes(q))));
      },
      visibleChunks(){
        const q=this.filters.chunkQuery.toLowerCase();
        return this.chunks.filter(item=>(!this.filters.chunkStatus||item.status===this.filters.chunkStatus)
          &&(!q||[item.chunkId,item.parentId,item.contentHash,item.error&&item.error.code].some(v=>String(v||"").toLowerCase().includes(q))));
      },
      summary(){
        return this.bases.reduce((sum,item)=>{
          if(item.status==="READY")sum.ready++;
          if(["RUNNING","QUEUED"].includes(item.status))sum.running++;
          sum.failed+=Number(item.failedDocumentCount||0);sum.chunks+=Number(item.chunkCount||0);return sum;
        },{ready:0,running:0,failed:0,chunks:0});
      }
    },
    methods:{
      async loadBases(page=0){
        this.loading=true;
        try{
          const data=await api.bases({projectId:this.filters.projectId,status:this.filters.status,type:this.filters.type,query:this.filters.query,page,size:this.basePage.size});
          this.bases=data.items;this.basePage=data;this.syncRoute();this.schedulePoll();
        }catch(error){this.showError(error);}finally{this.loading=false;}
      },
      async loadDocuments(page=0){
        if(!this.selectedBase)return;this.loading=true;
        try{
          const [documents,runs]=await Promise.all([api.documents(this.selectedBase.id,{status:this.filters.documentStatus,query:this.filters.documentQuery,page,size:this.documentPage.size}),api.runs(this.selectedBase.id,{page:0,size:1})]);
          this.documents=documents.items;this.documentPage=documents;this.latestRun=runs.items[0]||null;this.schedulePoll();
        }catch(error){this.showError(error);}finally{this.loading=false;}
      },
      async loadChunks(page=0){
        if(!this.selectedDocument)return;this.loading=true;
        try{const data=await api.chunks(this.selectedBase.id,this.selectedDocument.id,{status:this.filters.chunkStatus,query:this.filters.chunkQuery,page,size:this.chunkPage.size});this.chunks=data.items;this.chunkPage=data;this.schedulePoll();}
        catch(error){this.showError(error);}finally{this.loading=false;}
      },
      async openBase(base){this.selectedBase=base;this.selectedDocument=null;this.view="documents";this.pushPath(`/knowledge/${encodeURIComponent(base.id)}/documents`);await this.loadDocuments(0);},
      async openDocument(doc){this.selectedDocument=doc;this.view="document";this.pushPath(`/knowledge/${encodeURIComponent(this.selectedBase.id)}/documents/${encodeURIComponent(doc.id)}`);await this.loadChunks(0);},
      openRetrieval(){this.view="retrieval";this.retrieval.version=this.selectedBase.publishedRevision||"";this.pushPath(`/knowledge/${encodeURIComponent(this.selectedBase.id)}/retrieval`);this.clearPoll();},
      async openChunk(chunk){try{this.selectedChunk=await api.chunk(this.selectedBase.id,chunk.chunkId);}catch(error){this.showError(error);}},
      async copyText(value,message){if(!value)return;await navigator.clipboard.writeText(value);NexusNotice.show(message,"success");},
      goHome(){this.view="bases";this.selectedBase=null;this.selectedDocument=null;this.selectedChunk=null;this.pushPath("/knowledge");this.loadBases(this.basePage.page);},
      async refresh(){if(this.view==="bases")return this.loadBases(this.basePage.page);if(this.view==="documents")return this.loadDocuments(this.documentPage.page);if(this.view==="document")return this.loadChunks(this.chunkPage.page);},
      async rebuildBase(){await this.action(()=>api.rebuild(this.selectedBase.id),"已提交知识库重建任务");},
      async retryDocument(doc){await this.action(()=>api.retryDocument(this.selectedBase.id,doc.id),"已提交文档重试任务");},
      async retryChunk(chunk){await this.action(()=>api.retryChunk(this.selectedBase.id,chunk.chunkId),"已按文档范围提交重试任务");this.selectedChunk=null;},
      async action(operation,message){this.actionBusy=true;try{await operation();NexusNotice.show(message,"success");await this.refresh();}catch(error){this.showError(error);}finally{this.actionBusy=false;}},
      async runRetrieval(){if(!this.retrieval.query)return;this.retrieval.loading=true;this.retrieval.response=null;try{this.retrieval.response=await api.retrieval(this.selectedBase.id,{query:this.retrieval.query,version:this.retrieval.version||null,limit:this.retrieval.limit});}catch(error){this.showError(error);}finally{this.retrieval.loading=false;}},
      statusLabel:status.label,
      typeLabel(value){return {REQUIREMENT:"需求",CODE:"代码",WIKI:"Wiki"}[value]||value;},
      stageLabel(value){return {DISCOVER:"发现文件",PARSE:"读取文件",CLEAN:"文本清洗",CHUNK:"父子分块",DEDUPLICATE:"内容去重",EMBED:"向量化",INDEX:"写入 Qdrant",VERIFY:"验证索引",PUBLISH:"发布版本"}[value]||value||"未开始";},
      stageDescription(stage){if(!this.selectedDocument)return"";if(stage===this.selectedDocument.phase)return"当前阶段";return this.stageIndex(stage)<this.stageIndex(this.selectedDocument.phase)?"已完成":"等待执行";},
      stageIndex(stage){return this.stages.indexOf(stage);},
      stageClass(stage){const current=this.stageIndex(this.selectedDocument.phase),index=this.stageIndex(stage);return {done:index<current||(this.selectedDocument.status==="READY"&&index<=current),current:index===current&&this.selectedDocument.status!=="READY",failed:index===current&&this.selectedDocument.status==="FAILED"};},
      stageGlyph(stage){const state=this.stageClass(stage);return state.failed?"!":state.done?"✓":state.current?"◐":"○";},
      progressText(run){if(!run)return"";if(run.chunksTotal)return`${run.chunksReady}/${run.chunksTotal} 分块`;return`${run.filesProcessed}/${run.filesTotal} 文件`;},
      duration(start,end){if(!start)return"—";const ms=new Date(end||Date.now())-new Date(start);if(ms<1000)return`${ms}ms`;if(ms<60000)return`${(ms/1000).toFixed(1)}s`;return`${Math.floor(ms/60000)}m ${Math.floor(ms%60000/1000)}s`;},
      relativeTime(value){if(!value)return"—";const seconds=Math.max(0,Math.floor((Date.now()-new Date(value))/1000));if(seconds<60)return"刚刚";if(seconds<3600)return`${Math.floor(seconds/60)} 分钟前`;if(seconds<86400)return`${Math.floor(seconds/3600)} 小时前`;return new Date(value).toLocaleDateString("zh-CN");},
      formatNumber(value){return new Intl.NumberFormat("zh-CN").format(value||0);},
      shortHash(value){return value?value.slice(0,12):"—";},
      padChunk(chunk){return String(chunk.parentOrder*1000+chunk.childOrder+1).padStart(4,"0");},
      pushPath(path){history.pushState({}, "", path);},
      syncRoute(){},
      showError(error){this.failedPolls++;const safe=NexusErrors.normalize(error,"读取知识管理状态失败");NexusNotice.show(safe.message,"error");this.schedulePoll();},
      clearPoll(){if(this.pollTimer)clearTimeout(this.pollTimer);this.pollTimer=null;},
      schedulePoll(){
        this.clearPoll();if(document.hidden)return;
        const running=this.view==="bases"?this.bases.some(item=>["RUNNING","QUEUED"].includes(item.status))
          :this.view==="documents"?Boolean(this.latestRun&&["RUNNING","PENDING","EMBEDDING","INDEXING"].includes(this.latestRun.status))
          :this.view==="document"&&["RUNNING","EMBEDDING","INDEXING"].includes(this.selectedDocument&&this.selectedDocument.status);
        if(running)this.pollTimer=setTimeout(()=>this.refresh(),this.failedPolls>=3?10000:2000);
      },
      async restoreRoute(){
        const parts=location.pathname.split("/").filter(Boolean).map(decodeURIComponent);
        await this.loadBases(0);
        if(parts.length<2)return;
        const base=this.bases.find(item=>item.id===parts[1]);
        if(!base)return;
        this.selectedBase=base;
        if(parts[2]==="retrieval"){this.openRetrieval();return;}
        this.view="documents";await this.loadDocuments(0);
        if(parts[2]==="documents"&&parts[3]){
          const doc=this.documents.find(item=>item.id===parts[3])||await api.document(base.id,parts[3]);
          this.selectedDocument=doc;this.view="document";await this.loadChunks(0);
        }
      }
    },
    mounted(){
      this.restoreRoute();
      document.addEventListener("visibilitychange",()=>document.hidden?this.clearPoll():this.schedulePoll());
      window.addEventListener("popstate",()=>location.reload());
      window.addEventListener("keydown",event=>{if(event.key==="Escape")this.selectedChunk=null;});
    },
    beforeUnmount(){this.clearPoll();}
  }).mount("#app");
})();
