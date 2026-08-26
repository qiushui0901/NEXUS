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
        statusOptions:["IDLE","RUNNING","READY","PARTIAL","FAILED","STALE","DISABLED","UNAVAILABLE"],
        entityStatusOptions:["PENDING","RUNNING","CHUNKED","EMBEDDING","INDEXING","READY","FAILED","EXCLUDED","INTERRUPTED"],
        retrieval:{query:"",version:"",limit:10,loading:false,response:null,elapsedMs:null,requestId:0},
        retrievalMode:"legacy",
        semantic:{documentId:"",intent:"",page:0,requestId:0,buildRequestId:0,loading:false,response:null,elapsedMs:null,
          buildStatus:null,buildLoading:false,buildUnavailable:false,buildError:null,error:null,expandedClaimId:null},
        compare:{loading:false,requestId:0,legacyResponse:null,legacyElapsedMs:null,legacyError:null,
          semanticResponse:null,semanticElapsedMs:null,semanticError:null},
        intentOptions:["NORMATIVE","VALIDATION","PARAMETER","DOUBT","CONSISTENCY","IMPACT","GENERAL"],
        evaluations:[],
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
      },
      retrievalHits(){
        const response=this.retrieval.response;
        if(!response)return [];
        if(this.selectedBase && this.selectedBase.type==="CODE"){
          return response.codeHits||[];
        }
        return response.hits||[];
      },
      retrievalSourceCount(){
        return new Set(this.retrievalHits.map(hit=>hit.sourcePath||hit.filePath||hit.documentId).filter(Boolean)).size;
      },
      retrievalScope(){
        const response=this.retrieval.response;
        if(!response)return"—";
        if(this.selectedBase && this.selectedBase.type==="CODE"){
          const commits=[...new Set(this.retrievalHits.map(hit=>hit.commitSha).filter(Boolean))];
          if(commits.length===1)return this.shortHash(commits[0]);
          if(commits.length>1)return`${commits.length} 个提交`;
          return"未返回提交";
        }
        return response.version||this.retrieval.version||"当前版本";
      },
      retrievalDiagnostics(){
        return this.retrieval.response&&this.retrieval.response.stageDiagnostics||[];
      },
      // ---------------- 语义 Claim 检索 ----------------
      semanticClaims(){
        const response=this.semantic.response;
        return response&&response.claims||[];
      },
      semanticConflicts(){
        const response=this.semantic.response;
        return (response&&response.conflicts||[]).map(message=>({
          message,
          potential:String(message||"").startsWith("POTENTIAL_CROSS_SOURCE_CONFLICT:")
        }));
      },
      semanticWarnings(){
        return this.semantic.response&&this.semantic.response.warnings||[];
      },
      semanticDoubts(){
        return this.semantic.response&&this.semantic.response.doubts||[];
      },
      semanticRelations(){
        return this.semantic.response&&this.semantic.response.relations||[];
      },
      // 构建 ready：项目/版本范围内存在已发布的 active SUCCESS 代际（与检索范围一致，方案 §3.2）。
      // 注意：不看 generationActive（它只表示“最新一次 run 所属代际是否 active”，rev-2 失败但
      // rev-1 仍在线时 generationActive=false 而 activeGenerationStatus=SUCCESS）。
      semanticBuildReady(){
        const build=this.semantic.buildStatus;
        return Boolean(build&&build.hasActiveGeneration);
      },
      semanticBuildNotice(){
        if(this.semantic.buildLoading)return{tone:"neutral",text:"正在读取语义构建状态…"};
        // 非 404 构建状态查询失败必须优先展示，不能伪装成“模块未启用/未构建”。
        if(this.semantic.buildError){
          return{tone:"bad",text:"语义构建状态查询失败（非未启用），请稍后重试；本次结果不含语义候选状态说明。"};
        }
        if(this.semantic.buildUnavailable||!this.semantic.buildStatus){
          return{tone:"warn",text:"无法读取语义构建状态：语义模块可能未启用，或该项目/版本尚未执行语义构建。当前结果不含语义候选（其他来源仍可用）。"};
        }
        const build=this.semantic.buildStatus;
        // P1-1：检索开关关闭时，即使构建已发布也不能产生语义候选——
        // 必须明确提示，避免把“配置关闭”误判成“召回质量差”。
        if(build.candidateRetrievalEnabled===false){
          return{tone:"warn",text:"语义候选检索当前被配置关闭（candidate-retrieval-enabled=false）：构建已发布但候选不参与本次检索，本次结果不得作为语义召回评测数据。"};
        }
        // #6：当前响应自身携带不可评测 warning（normative 开关拦截/候选截断/来源加载失败）时，
        // 结果展示但不计入评测——与 semanticEvaluationUsable 的拒绝条件保持一致。
        const warnings=(this.semantic.response&&this.semantic.response.warnings)||[];
        const unusable=warnings.find(function(w){
          return /^SEMANTIC_NORMATIVE_RETRIEVAL_DISABLED|^SEMANTIC_CANDIDATE_TRUNCATED|^SEMANTIC_CANDIDATE_LOAD_FAILED|^MULTI_SOURCE_CANDIDATE_LOAD_FAILED/.test(w||"");
        });
        if(unusable){
          return{tone:"warn",text:"语义候选被开关/截断/加载失败拦截（"+unusable+"），本次结果不得作为语义召回评测数据。"};
        }
        if(this.semanticBuildReady){
          if(build.latestRunStatus&&build.latestRunStatus!=="SUCCESS"){
            return{tone:"warn",text:`语义构建最新一次执行为 ${build.latestRunStatus}，但仍有已发布的成功代际在线；本次结果仅供调试，不建议作为正式评测结果。`};
          }
          return{tone:"good",text:`语义构建已发布 · 覆盖 ${build.activeDocumentCount} 个文档 · 最新执行 ${build.latestRunStatus||"—"}`};
        }
        if(build.latestRunStatus==="PARTIAL_FAILURE"){
          return{tone:"warn",text:"语义构建部分失败，本次结果仅供调试，不建议作为正式评测结果。"};
        }
        if(build.latestRunStatus==="FAILED"){
          return{tone:"bad",text:"语义构建失败，不能把失败伪装成无召回结果；请先重新构建并确认成功。"};
        }
        return{tone:"warn",text:"语义构建尚未发布（无 active 代际），当前无法使用语义 Claim 检索；请先执行语义构建并确认构建成功。"};
      },
      // #6（Review 中）+ #5（Review 高）：本次语义结果是否可作评测——
      // #5：评测资格必须要求已捕获 active 代际（hasActiveGeneration=true、非空 activeBuildIds），
      // 且状态查询没有失败或不可用——否则聚合 200 null 时前端把缺失状态转 {}，只查开关与 warning
      // 就会 fail-open，用户可保存空 build identity 的 MISS。
      // #6：当前响应携带不可评测 warning（NORMATIVE 检索被关闭、候选被截断、语义来源加载失败）
      // 时拒绝写入，避免把"配置/加载问题"误记为"召回问题"。评测资格必须绑定当前响应。
      // 高（Review #2）：评测资格只读取 responseContext（请求发起时的不可变快照），
      // 不再读可变全局 buildStatus——用户等待状态期间编辑版本时，全局展示状态可能来自版本 A
      // 而结果属于版本 B，导致版本 B 的无代际结果被错误允许或有效响应被错误拒绝。
      // 高（Review #1）：semanticSourceAttempted 区分"语义源未参与"（DOUBT，IDs 空，不可评测）
      // 与"语义源参与但零命中"（IDs 非空，可评测）；空 activeBuildIds 是权威结果不 fail-open。
      semanticEvaluationUsable(){
        if(!this.semantic.response)return false;
        const ctx=this.semantic.responseContext||{};
        if(!ctx.semanticSourceAttempted)return false;
        if(ctx.buildUnavailable||ctx.buildError)return false;
        if(!ctx.hasActiveGeneration)return false;
        const buildIds=ctx.activeBuildIds||[];
        if(!buildIds.length)return false;
        if(ctx.candidateRetrievalEnabled===false)return false;
        const warnings=this.semantic.response.warnings||[];
        if(warnings.some(function(w){
          return /^SEMANTIC_NORMATIVE_RETRIEVAL_DISABLED|^SEMANTIC_CANDIDATE_TRUNCATED|^SEMANTIC_CANDIDATE_LOAD_FAILED|^SEMANTIC_CANDIDATE_RETRIEVAL_DISABLED|^MULTI_SOURCE_CANDIDATE_LOAD_FAILED/.test(w||"");
        }))return false;
        return true;
      },
      semanticScope(){
        const base=this.selectedBase;
        const build=this.semantic.buildStatus;
        const scope=this.semanticBuildReady&&build?`${build.activeDocumentCount} 个文档已构建`:"全部文档（未构建）";
        return `${base?base.projectId:"—"} / ${this.requirementVersion()||"—"} / ${scope}`;
      },
      // 代码知识库没有需求版本与语义候选：隐藏语义/对比入口（只提供传统 Chunk 检索）。
      semanticAvailable(){
        return this.selectedBase&&this.selectedBase.type!=="CODE";
      },
      evaluationSize(){return this.evaluations.length;}
    },
    methods:{
      async loadBases(page=0){
        this.loading=true;
        try{
          const data=await api.bases({projectId:this.filters.projectId,status:this.filters.status,type:this.filters.type,query:this.filters.query,page,size:this.basePage.size});
          this.bases=data.items;this.basePage=data;
          NexusShell.setContext({projectId:this.filters.projectId});
          this.syncRoute();this.schedulePoll();
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
      async openBase(base){this.selectedBase=base;this.selectedDocument=null;this.view="documents";this.revokeRetrievalRequests();NexusShell.setContext({projectId:base.projectId,version:base.publishedRevision||""});this.pushPath(`/knowledge/${encodeURIComponent(base.id)}/documents`);await this.loadDocuments(0);},
      async openDocument(doc){this.selectedDocument=doc;this.view="document";this.revokeRetrievalRequests();this.pushPath(`/knowledge/${encodeURIComponent(this.selectedBase.id)}/documents/${encodeURIComponent(doc.id)}`);await this.loadChunks(0);},
      openRetrieval(){this.view="retrieval";this.retrieval.version=this.selectedBase.publishedRevision||this.selectedBase.targetRevision||this.selectedBase.latestRequirementVersion||"";this.retrievalMode="legacy";this.revokeRetrievalRequests();this.semantic.buildStatus=null;this.semantic.buildUnavailable=false;this.semantic.buildError=null;NexusShell.setContext({projectId:this.selectedBase.projectId,version:this.retrieval.version});this.pushPath(`/knowledge/${encodeURIComponent(this.selectedBase.id)}/retrieval`);this.clearPoll();},
      async backToBase(){if(!this.selectedBase)return this.goHome();this.view="documents";this.retrieval.response=null;this.revokeRetrievalRequests();NexusShell.setContext({projectId:this.selectedBase.projectId,version:this.selectedBase.publishedRevision||""});this.pushPath(`/knowledge/${encodeURIComponent(this.selectedBase.id)}/documents`);await this.loadDocuments(this.documentPage.page);},
      async openChunk(chunk){try{this.selectedChunk=await api.chunk(this.selectedBase.id,chunk.chunkId);}catch(error){this.showError(error);}},
      async copyText(value,message){if(!value)return;await navigator.clipboard.writeText(value);NexusNotice.show(message,"success");},
      goHome(){this.view="bases";this.selectedBase=null;this.selectedDocument=null;this.selectedChunk=null;this.revokeRetrievalRequests();this.pushPath("/knowledge");this.loadBases(this.basePage.page);},
      async refresh(){if(this.view==="bases")return this.loadBases(this.basePage.page);if(this.view==="documents")return this.loadDocuments(this.documentPage.page);if(this.view==="document")return this.loadChunks(this.chunkPage.page);},
      async rebuildBase(){await this.action(()=>api.rebuild(this.selectedBase.id),"已提交知识库重建任务");},
      async retryDocument(doc){await this.action(()=>api.retryDocument(this.selectedBase.id,doc.id),"已提交文档重试任务");},
      async retryChunk(chunk){await this.action(()=>api.retryChunk(this.selectedBase.id,chunk.chunkId),"已按文档范围提交重试任务");this.selectedChunk=null;},
      async action(operation,message){this.actionBusy=true;try{await operation();NexusNotice.show(message,"success");await this.refresh();}catch(error){this.showError(error);}finally{this.actionBusy=false;}},
      // #3（Review 高）：页面导航统一吊销三种检索请求（legacy / semantic / compare）——
      // 旧请求慢返回不得写回新页面，也不得让隐藏的 loading 继续禁用提交按钮。
      // 传统检索与对比检索都在这里递增自己的 requestId 并释放 loading；语义由 resetSemanticState 处理。
      revokeRetrievalRequests(){
        this.retrieval.requestId=(this.retrieval.requestId||0)+1;
        this.retrieval.loading=false;
        this.compare.requestId=(this.compare.requestId||0)+1;
        this.compare.loading=false;
        this.resetCompareState();
        this.resetSemanticState();
      },
      async runRetrieval(){if(!this.retrieval.query)return;const requestId=++this.retrieval.requestId;this.retrieval.loading=true;this.retrieval.response=null;this.retrieval.elapsedMs=null;const started=performance.now();try{const response=await api.retrieval(this.selectedBase.id,{query:this.retrieval.query,version:this.retrieval.version||null,limit:this.retrieval.limit});if(requestId!==this.retrieval.requestId)return;this.retrieval.response=response;}catch(error){if(requestId!==this.retrieval.requestId)return;this.showError(error);}finally{if(requestId===this.retrieval.requestId){this.retrieval.elapsedMs=Math.max(0,Math.round(performance.now()-started));this.retrieval.loading=false;}}},
      // ---------------- 检索模式切换（方案 §4.1） ----------------
      submitRetrieval(){
        if(this.retrievalMode==="semantic")return this.runSemanticSearch(0);
        if(this.retrievalMode==="compare")return this.runCompareSearch();
        return this.runRetrieval();
      },
      setRetrievalMode(mode){
        if(this.retrievalMode===mode)return;
        this.retrievalMode=mode;
        // 风险 5：切换模式清空其它模式状态，避免结果串联与残留。
        // P2：离开对比模式时递增 compare.requestId 吊销在飞请求，并重置 loading——
        // 否则慢返回的旧对比请求仍会写入 compare.* 且继续阻塞其它模式提交。
        if(mode!=="semantic")this.resetSemanticState();
        if(mode!=="compare"){
          this.compare.requestId=(this.compare.requestId||0)+1;
          this.compare.loading=false;
          this.resetCompareState();
        }
        if(mode!=="legacy"){this.retrieval.response=null;this.retrieval.elapsedMs=null;}
      },
      resetSemanticState(){
        // #4（Review 高）：离开语义模式/切换项目/切换页面时吊销所有在飞语义请求——
        // 递增 requestId 使旧请求的竞态检查失败（不写回新页面），并释放 loading
        // 避免隐藏的 semantic.loading 继续禁用全局提交按钮。
        this.semantic.requestId=(this.semantic.requestId||0)+1;
        this.semantic.buildRequestId=(this.semantic.buildRequestId||0)+1;
        this.semantic.loading=false;this.semantic.buildLoading=false;
        this.semantic.response=null;this.semantic.elapsedMs=null;this.semantic.error=null;
        this.semantic.page=0;this.semantic.expandedClaimId=null;
        this.semantic.responseContext=null;
      },
      resetCompareState(){
        this.compare.legacyResponse=null;this.compare.legacyElapsedMs=null;this.compare.legacyError=null;
        this.compare.semanticResponse=null;this.compare.semanticElapsedMs=null;this.compare.semanticError=null;
      },
      // 语义构建状态：模块未启用或无构建记录时后端 404，均视为“不可用”，不伪装成“无结果”。
      // P1-4：状态范围与检索范围一致——按 projectId+version 拉取聚合构建状态（覆盖全部 active 文档），
      // 不查单文档状态；P1-3：构建状态查询有自己的 requestId，并在赋值前核对上下文快照（项目/版本），
      // 避免慢返回的旧上下文状态覆盖新上下文。
      async loadSemanticBuild(){
        const base=this.selectedBase;if(!base)return null;
        const projectId=base.projectId;
        const version=this.requirementVersion();
        const requestId=++this.semantic.buildRequestId;
        // P2-6：成功/异常/finally 共用同一上下文校验——期间用户改项目/版本但未触发新状态请求时，
        // 旧版本请求的失败也不会被写到新版本的页面。
        const stillCurrent=()=>requestId===this.semantic.buildRequestId
          && this.selectedBase&&this.selectedBase.projectId===projectId
          && this.requirementVersion()===version;
        this.semantic.buildLoading=true;this.semantic.buildUnavailable=false;this.semantic.buildError=null;
        try{
          const buildStatus=await api.semanticBuildAggregate({projectId,requirementVersion:version});
          // 展示状态仅在“当前可编辑上下文仍适用”时更新；请求私有结果无条件返回给发起者——
          // #2（Review 高）：编辑版本会让旧版本结果与错误代际混合——提交 v1、状态等待期间改成 v2 时，
          // v1 状态不再被“丢弃后从旧的/空的全局 buildStatus 提取代际”，而是由发起者（runSemanticSearch）
          // 直接使用这份私有结果构造评测上下文。
          if(stillCurrent())this.semantic.buildStatus=buildStatus;
          return {buildStatus, unavailable:false, error:null};
        }catch(error){
          if(stillCurrent()){
            this.semantic.buildStatus=null;
            if(error&&error.status===404)this.semantic.buildUnavailable=true;
            else this.semantic.buildError=error;
          }
          return {buildStatus:null, unavailable:Boolean(error&&error.status===404), error};
        }finally{
          // loading 跟随请求生命周期（requestId 仍是最新一次请求），而非展示上下文是否适用——
          // 否则版本变化导致旧状态被丢弃时 buildLoading 会永久卡住（#2）。
          if(requestId===this.semantic.buildRequestId)this.semantic.buildLoading=false;
        }
      },
      // 需求文档 ID：业务项目需求库取后端明确返回的 requirementDocumentId，不用展示名称猜测。
      semanticDocumentId(){
        const base=this.selectedBase;
        return (base&&base.requirementDocumentId)||"";
      },
      // 需求版本回退链：publishedRevision → targetRevision/latestRequirementVersion（业务项目通常只有后者）。
      requirementVersion(){
        const base=this.selectedBase;
        return this.retrieval.version||base.publishedRevision||base.targetRevision||base.latestRequirementVersion||"";
      },
      async runSemanticSearch(page=0){
        if(!this.retrieval.query)return;
        const version=this.requirementVersion();
        if(!version){this.semantic.error={message:"缺少需求版本，无法执行语义检索"};NexusNotice.show("缺少需求版本，无法执行语义检索","error");return;}
        // #3（Review 高）：先捕获本次请求的不可变参数快照——用户等待状态刷新期间修改表单
        // 不影响已发起的检索与评测绑定。state 查询、semanticSearch、responseContext 全部读快照。
        const params={
          projectId:this.selectedBase.projectId,
          version,
          query:this.retrieval.query,
          intent:this.semantic.intent||null,
          limit:this.retrieval.limit,
          page
        };
        this.semantic.loading=true;this.semantic.error=null;
        // #8（Review 中）：pending page 与已展示 page 分离——不在请求发起时提交 semantic.page，
        // 只在响应成功时提交，避免第二页失败后再次点击从失败页往后跳页。
        // #7（Review 中）：请求期间保留已展示的 responseContext（旧页结果评测仍按旧页 rank
        // 计算）；pending 上下文与已展示上下文分离，仅在请求成功且竞态通过后原子替换。
        if(page===0){this.semantic.response=null;this.semantic.elapsedMs=null;this.semantic.expandedClaimId=null;}
        const started=performance.now();
        const requestId=++this.semantic.requestId;
        try{
          // 先等待构建状态：避免结果已返回但状态条还短暂显示"不可用"。
          // #2（Review 高）：loadSemanticBuild 返回请求私有结果（而非写入全局 buildStatus），
          // 由本请求发起者使用——即使等待期间用户改了版本导致全局展示状态被丢弃，
          // 本请求仍用自己读到的状态构建评测上下文，不再从旧的/空的全局 buildStatus 提取代际。
          const state = await this.loadSemanticBuild();
          if(requestId!==this.semantic.requestId)return;
          const response=await api.semanticSearch({
            projectId:params.projectId,
            version:params.version,
            query:params.query,
            intent:params.intent,
            limit:params.limit,
            page:params.page
          });
          // 竞态保护：旧请求的响应不覆盖新查询。
          if(requestId!==this.semantic.requestId)return;
          // 高（Review #1+#2）：评测上下文绑定本次响应的权威、不可变快照——
          // #1：activeBuildIds 取 response.semanticBuildIds（实际读取代际），空列表是权威结果不回退——
          //     DOUBT 意图下语义源未参与时 IDs 为空且 semanticSourceAttempted=false，不补入 active IDs。
          // #2：构建状态（hasActiveGeneration/开关/unavailable/error）全部写入快照，
          //     semanticEvaluationUsable 只读该快照，不再读可变全局 buildStatus。
          const build=(state&&state.buildStatus)||{};
          this.semantic.responseContext={
            projectId:params.projectId,
            version:params.version,
            query:params.query,
            intent:params.intent,
            limit:typeof response.limit==="number"?response.limit:params.limit,
            page:typeof response.page==="number"?response.page:params.page,
            activeBuildIds:((response.semanticBuildIds||[])).slice(),
            activeDocumentCount:build.activeDocumentCount||0,
            hasActiveGeneration:build.hasActiveGeneration||false,
            candidateRetrievalEnabled:build.candidateRetrievalEnabled,
            normativeRetrievalEnabled:build.normativeRetrievalEnabled,
            buildUnavailable:!!(state&&state.unavailable),
            buildError:!!(state&&state.error),
            semanticSourceAttempted:response.semanticSourceAttempted||false
          };
          this.semantic.response=response;
          // #8：只在响应成功时提交当前页。
          this.semantic.page=typeof response.page==="number"?response.page:params.page;
        }catch(error){
          if(requestId!==this.semantic.requestId)return;
          this.semantic.error=error;this.showError(error);
        }finally{
          if(requestId===this.semantic.requestId){
            this.semantic.elapsedMs=Math.max(0,Math.round(performance.now()-started));
            this.semantic.loading=false;
          }
        }
      },
      // 对比检索：同一查询并行两条链路，任一侧失败不覆盖另一侧（方案 §6）。
      async runCompareSearch(){
        if(!this.retrieval.query)return;
        const version=this.requirementVersion();
        if(!version){NexusNotice.show("缺少需求版本，无法执行对比检索","error");return;}
        const base=this.selectedBase;
        // #3（Review 高）：对比流程使用发起时的参数快照——请求等待构建状态期间用户切项目/
        // 版本时，不得用新上下文去解引用旧的 selectedBase（null 解引用崩溃），也不得把项目 B 的
        // ID 与项目 A 的版本混拼。所有请求一律使用快照值。
        const params={
          baseId:base?base.id:null,
          projectId:base?base.projectId:null,
          version,
          query:this.retrieval.query,
          limit:this.retrieval.limit,
          intent:this.semantic.intent||null
        };
        // P2：compare 有自己的 requestId——连续点击或切换检索模式时，旧请求不得覆盖新结果。
        const requestId=++this.compare.requestId;
        this.compare.loading=true;this.resetCompareState();
        // 状态请求私有结果供本请求使用（#2），不依赖全局 buildStatus。
        await this.loadSemanticBuild();
        this.semantic.requestId=(this.semantic.requestId||0)+1;
        const stillCurrent=()=>requestId===this.compare.requestId;
        try{
          // P2：每条链路自记耗时（错误请求也记），不再等 allSettled 后统一计算。
          const legacyStarted=performance.now();
          const legacy=api.retrieval(params.baseId,{query:params.query,version:params.version,limit:params.limit})
            .then(response=>{
              const elapsed=Math.max(0,Math.round(performance.now()-legacyStarted));
              if(!stillCurrent())return;
              this.compare.legacyResponse=response;this.compare.legacyElapsedMs=elapsed;
            })
            .catch(error=>{
              const elapsed=Math.max(0,Math.round(performance.now()-legacyStarted));
              if(!stillCurrent())return;
              this.compare.legacyError=error;this.compare.legacyElapsedMs=elapsed;
            });
          const semanticStarted=performance.now();
          const semantic=api.semanticSearch({projectId:params.projectId,version:params.version,query:params.query,intent:params.intent,limit:params.limit,page:0})
            .then(response=>{
              const elapsed=Math.max(0,Math.round(performance.now()-semanticStarted));
              if(!stillCurrent())return;
              this.compare.semanticResponse=response;this.compare.semanticElapsedMs=elapsed;
            })
            .catch(error=>{
              const elapsed=Math.max(0,Math.round(performance.now()-semanticStarted));
              if(!stillCurrent())return;
              this.compare.semanticError=error;this.compare.semanticElapsedMs=elapsed;
            });
          await Promise.allSettled([legacy,semantic]);
        }finally{
          // #3：try/finally 保证 loading 一定释放；若已被新请求/导航吊销（requestId 不匹配），
          // 由新请求或 revokeRetrievalRequests 管理 loading。
          if(stillCurrent())this.compare.loading=false;
        }
        if(stillCurrent()){
          if(this.compare.legacyError)NexusNotice.show("传统 Chunk 检索失败，仅展示语义一侧","error");
          if(this.compare.semanticError)NexusNotice.show("语义 Claim 检索失败，仅展示传统一侧","error");
        }
      },
      changeSemanticPage(delta){
        const next=this.semantic.page+delta;
        if(next<0)return;
        this.runSemanticSearch(next);
      },
      // ---------------- 人工评测标记（方案 §7，第一阶段 localStorage） ----------------
      // 统一评测键：projectId/version/active 代际身份/query/intent 全部参与——不同版本、重建后代际或
      // 不同查询意图（NORMATIVE 与 VALIDATION 对同一 query 排序可能不同）不共享判断，避免跨污染。
      // 代际身份用 active 代际的 buildId 集合（排序拼接）：重建并成功发布后集合变化 → 键变化；
      // 仅失败重跑不改变 active 集合 → 键不变（与"结果来自 active 代际"语义一致）。
      // P1-2：评测永远读取请求发起时保存的不可变快照（responseContext），而不是可编辑表单——
      // 结果返回后用户再改查询/版本但未重新检索，旧结果的评测仍绑定实际执行的请求参数。
      evaluationKey(mode,resultId){
        const ctx=this.semantic.responseContext||{};
        const generationIdentity=(ctx.activeBuildIds||[]).slice().sort().join(",");
        return [
          ctx.projectId||"",
          ctx.version||"",
          generationIdentity,
          ctx.query||"",
          ctx.intent||"",
          mode,
          resultId===null||resultId===undefined?"__MISS__":resultId
        ].join("|");
      },
      evaluationContext(mode){
        const ctx=this.semantic.responseContext||{};
        const generationIdentity=(ctx.activeBuildIds||[]).slice().sort();
        return {
          key:this.evaluationKey(mode,undefined),
          query:ctx.query||"",
          intent:ctx.intent||"",
          mode,
          projectId:ctx.projectId||"",
          version:ctx.version||"",
          buildIds:generationIdentity,
          documentCount:ctx.activeDocumentCount||0,
          page:ctx.page||0,
          limit:ctx.limit||0,
          createdAt:new Date().toISOString()
        };
      },
      currentJudgement(mode,resultId){
        const key=this.evaluationKey(mode,resultId);
        const record=this.evaluations.find(item=>item.key===key);
        return record?record.judgement:null;
      },
      markJudgement(mode,resultId,rank,judgement){
        // P1-1：语义候选被配置关闭时，本次结果不得作为评测数据——直接拒绝写入并提示。
        if(mode==="SEMANTIC"&&!this.semanticEvaluationUsable){
          NexusNotice.show("语义候选检索当前被配置关闭，本次结果不可作为评测数据","error");
          return;
        }
        const key=this.evaluationKey(mode,resultId);
        const previous=this.currentJudgement(mode,resultId);
        this.evaluations=this.evaluations.filter(item=>item.key!==key);
        // 再次点击同一判断 = 取消；切换判断 = 替换。
        if(previous!==judgement){
          this.evaluations.push(Object.assign({},this.evaluationContext(mode),{key,resultId,rank,judgement}));
        }
        this.persistEvaluations();
      },
      markMissedRecall(mode){
        // P1-1：配置关闭时标记"漏召回"会把配置问题误记为召回问题，同样拒绝。
        if(mode==="SEMANTIC"&&!this.semanticEvaluationUsable){
          NexusNotice.show("语义候选检索当前被配置关闭，本次结果不可作为评测数据","error");
          return;
        }
        const key=this.evaluationKey(mode,null);
        const previouslyMarked=this.missRecallMarked(mode);
        this.evaluations=this.evaluations.filter(item=>item.key!==key);
        if(!previouslyMarked){
          this.evaluations.push(Object.assign({},this.evaluationContext(mode),
            {key,resultId:null,rank:null,judgement:"MISS"}));
        }
        this.persistEvaluations();
      },
      missRecallMarked(mode){
        return this.evaluations.some(item=>item.key===this.evaluationKey(mode,null));
      },
      persistEvaluations(){
        try{localStorage.setItem("nexusSemanticEvaluations",JSON.stringify(this.evaluations));}
        catch(error){NexusNotice.show("评测标记保存失败（本地存储不可用）","error");}
      },
      loadEvaluations(){
        try{this.evaluations=JSON.parse(localStorage.getItem("nexusSemanticEvaluations")||"[]");}
        catch(error){this.evaluations=[];}
      },
      exportEvaluations(){
        if(!this.evaluations.length){NexusNotice.show("暂无评测标记可导出","error");return;}
        const blob=new Blob([JSON.stringify(this.evaluations,null,2)],{type:"application/json"});
        const link=document.createElement("a");
        link.href=URL.createObjectURL(blob);
        link.download=`nexus-retrieval-evaluations-${new Date().toISOString().slice(0,10)}.json`;
        link.click();
        URL.revokeObjectURL(link.href);
      },
      clearEvaluations(){
        if(!this.evaluations.length)return;
        this.evaluations=[];this.persistEvaluations();NexusNotice.show("已清空本地评测标记","success");
      },
      // ---------------- 展示辅助 ----------------
      sourceTypeLabel(value){
        return {REQUIREMENT:"需求",REQUIREMENT_SEMANTIC:"需求语义",PARAMETER_TABLE:"参数表",
          TEST_CASE:"测试用例",TEST_RESULT:"测试结果",DOUBT:"存疑",CODE:"代码",WIKI:"Wiki"}[value]||value||"—";
      },
      sourceTypeClass(value){return "source-tag source-"+String(value||"").toLowerCase().replace(/_/g,"-");},
      warningText(value){
        const text=String(value||"");
        if(text==="SEMANTIC_CANDIDATE_TRUNCATED")return"语义候选达到上限被截断，结果可能不完整";
        if(text.startsWith("MULTI_SOURCE_CANDIDATE_LOAD_FAILED"))return`候选来源加载失败（${text.split(":")[1]||"—"}），该来源未参与本次结果`;
        if(text==="MULTI_SOURCE_DISABLED")return"多源检索未对该项目启用";
        return text;
      },
      judgementLabel(value){
        return {RELEVANT:"相关",PARTIAL:"部分相关",IRRELEVANT:"不相关",MISS:"漏召回"}[value]||value;
      },
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
      formatLatency(value){if(value===null||value===undefined)return"—";return value<1000?`${value} ms`:`${(value/1000).toFixed(2)} s`;},
      retrievalStageLabel(value){const stage=String(value||"");if(stage.includes("route")||stage.includes("routing"))return"项目路由";if(stage.includes("rerank")||stage.includes("bge"))return"候选重排";if(stage.includes("code"))return"代码召回";if(stage.includes("qdrant"))return"Qdrant 混合召回";if(stage.includes("corpus"))return"版本正文";if(stage.includes("requirement")||stage.includes("document"))return"需求召回";return stage||"检索阶段";},
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
        NexusShell.setContext({projectId:base.projectId,version:base.publishedRevision||""});
        if(parts[2]==="retrieval"){this.openRetrieval();return;}
        this.view="documents";await this.loadDocuments(0);
        if(parts[2]==="documents"&&parts[3]){
          const doc=this.documents.find(item=>item.id===parts[3])||await api.document(base.id,parts[3]);
          this.selectedDocument=doc;this.view="document";await this.loadChunks(0);
        }
      }
    },
    mounted(){
      this.loadEvaluations();
      this.restoreRoute();
      document.addEventListener("visibilitychange",()=>document.hidden?this.clearPoll():this.schedulePoll());
      window.addEventListener("popstate",()=>location.reload());
      window.addEventListener("keydown",event=>{if(event.key==="Escape")this.selectedChunk=null;});
    },
    beforeUnmount(){this.clearPoll();}
  }).mount("#app");
})();
