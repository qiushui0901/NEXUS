package com.example.requirementrag.service;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.KnowledgeClaim;
import com.example.requirementrag.conflict.KnowledgeConflictModels.KnowledgeConflictReport;
import com.example.requirementrag.conflict.KnowledgeConflictModels.KnowledgeEvidence;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.conflict.KnowledgeConflictService;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.DevelopmentPlanResponse;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.model.RagStageDiagnostic;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.observability.RagObservability;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalPipeline;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 面向“某个需求怎么入手”的开发方案服务。
 *
 * <p>这里不直接读取原始文档或 ZIP，只从已经进入向量库的需求分块与代码分块中取证据。</p>
 */
@Service
public class DevelopmentPlanService {

    private static final int MAX_DOCUMENT_CONTEXT_CHARS = 12_000;
    private static final int MAX_CODE_CONTEXT_CHARS = 8_000;

    private final RagProperties properties;
    private final RetrievalPipeline retrievalPipeline;
    private final ChatClient chatClient;
    private final RagObservability observability;
    private final KnowledgeConflictService conflictService;

    @Autowired
    public DevelopmentPlanService(RagProperties properties, RetrievalPipeline retrievalPipeline,
                                  ChatClient chatClient, RagObservability observability,
                                  KnowledgeConflictService conflictService) {
        this.properties = properties;
        this.retrievalPipeline = retrievalPipeline;
        this.chatClient = chatClient;
        this.observability = observability;
        this.conflictService = conflictService;
    }

    /** Backward-compatible constructor kept for focused unit tests and embedded consumers. */
    public DevelopmentPlanService(RagProperties properties, RetrievalPipeline retrievalPipeline,
                                  ChatClient chatClient, RagObservability observability) {
        this(properties, retrievalPipeline, chatClient, observability, new KnowledgeConflictService());
    }

    /** Backward-compatible constructor kept for focused unit tests and embedded consumers. */
    public DevelopmentPlanService(RagProperties properties, ProjectRegistry projectRegistry,
                                  QueryRouter queryRouter, QdrantHybridStore documentStore,
                                  CodeKnowledgeService codeKnowledgeService, ChatClient chatClient,
                                  RagObservability observability) {
        this(properties, new RetrievalPipeline(properties, projectRegistry, queryRouter, documentStore,
                codeKnowledgeService, observability), chatClient, observability, new KnowledgeConflictService());
    }

    /** 结合需求文档与代码检索结果，生成可落地的开发入手建议。 */
    public DevelopmentPlanResponse plan(String query, String documentId, String version, String projectId, Integer limit) {
        RagOutcome<RetrievalBundle> retrieval = retrievalPipeline.execute(new RetrievalRequest(
                query, RetrievalProfile.DEVELOPMENT_PLAN, projectId, documentId, version, limit));
        RetrievalBundle bundle = retrieval.data();
        String resolvedDocumentId = bundle.documentId();
        String resolvedVersion = bundle.version();
        List<ChunkRecord> documents = bundle.requirementEvidence();
        List<CodeChunk> code = bundle.codeEvidence();

        List<RagWarning> warnings = new ArrayList<>(retrieval.warnings());
        List<RagStageDiagnostic> diagnostics = new ArrayList<>(retrieval.stageDiagnostics());

        RagOutcome<PlanDraft> draftOutcome = draftWithProductContext(query, documents, code,
                resolvedDocumentId, resolvedVersion);
        collect(draftOutcome, warnings, diagnostics);
        PlanDraft draft = draftOutcome.data();
        RagOutcomeStatus status = overallStatus(documents, code, warnings);

        return new DevelopmentPlanResponse(
                query,
                resolvedDocumentId,
                resolvedVersion,
                hasText(draft.summary()) ? draft.summary() : summary(query, documents, code),
                firstNonEmpty(draft.productUnderstanding(), productUnderstandingFallback(query, documents)),
                firstNonEmpty(draft.developmentConstraints(), developmentConstraintsFallback(documents)),
                similarModule(query, code),
                firstNonEmpty(draft.chainOverview(), chainOverview(query)),
                sections(query, code, draft),
                firstNonEmpty(draft.implementationOrder(), implementationOrder(query)),
                steps(query, documents, code),
                firstNonEmpty(draft.risks(), risks(documents, code)),
                documents.stream()
                        .map(chunk -> new DevelopmentPlanResponse.DocumentReference(chunk.filename(), excerpt(chunk.parentText(), 260)))
                        .toList(),
                code,
                status,
                warnings,
                diagnostics,
                retrievalConflictReport(bundle));
    }

    private KnowledgeConflictReport retrievalConflictReport(RetrievalBundle bundle) {
        List<KnowledgeClaim> claims = new ArrayList<>();
        for (ChunkRecord chunk : bundle.requirementEvidence()) {
            String identity = hasText(chunk.parentId()) ? chunk.parentId()
                    : Objects.toString(chunk.filename(), "") + ":" + chunk.parentOrder();
            String value = hasText(chunk.contentHash()) ? chunk.contentHash() : chunk.parentText();
            claims.add(new KnowledgeClaim(null, bundle.resolvedProjectId(),
                    hasText(chunk.version()) ? chunk.version() : bundle.version(), "retrieval.requirement:" + identity,
                    Objects.toString(value, ""), SourceType.REQUIREMENT, Authority.PRIMARY,
                    new KnowledgeEvidence(hasText(chunk.id()) ? chunk.id() : identity, chunk.filename(),
                            chunk.filename(), "parentOrder=" + chunk.parentOrder(),
                            excerpt(chunk.parentText(), 260)), List.of()));
        }
        for (CodeChunk chunk : bundle.codeEvidence()) {
            String identity = hasText(chunk.id()) ? chunk.id()
                    : Objects.toString(chunk.filePath(), "") + ":"
                    + Objects.toString(chunk.symbolName(), "") + ":" + chunk.startLine();
            String value = hasText(chunk.contentHash()) ? chunk.contentHash() : chunk.text();
            claims.add(new KnowledgeClaim(null, hasText(chunk.projectId()) ? chunk.projectId() : bundle.resolvedProjectId(),
                    bundle.version(), "retrieval.code:" + identity, Objects.toString(value, ""),
                    SourceType.CODE, Authority.PRIMARY,
                    new KnowledgeEvidence(identity, chunk.symbolName(), chunk.filePath(),
                            chunk.filePath() + ":" + chunk.startLine(), excerpt(chunk.text(), 260)), List.of()));
        }
        return conflictService.analyze(bundle.resolvedProjectId(), bundle.version(), claims);
    }

    private RagOutcome<PlanDraft> draftWithProductContext(String query, List<ChunkRecord> documents,
                                                           List<CodeChunk> code, String documentId, String version) {
        long started = System.nanoTime();
        if (documents.isEmpty()) {
            RagOutcome<PlanDraft> outcome = RagOutcome.of(RagOutcomeStatus.NO_RESULTS, PlanDraft.empty(), "llm.generate.plan",
                    elapsedMillis(started), 0);
            recordOutcome(outcome, documentId, version);
            return outcome;
        }
        try {
            PlanDraft draft = chatClient.prompt()
                    .system("""
                            你是一名资深游戏后端主程，任务是基于“产品需求片段 + 当前项目代码命中”输出开发入手方案。
                            要求：
                            1. 必须先理解产品规则，再映射到代码链路，不能只给通用技术建议。
                            2. 不要说“我参考了文档”，直接输出结论。
                            3. 每个开发环节都要体现它服务哪个产品规则。
                            4. 不要生成“产品存疑/待确认问题”，开发方案只输出规则理解、开发约束和落地路径。
                            5. 如果需求片段没写清，只把它转成开发假设或实现约束，不要包装成评审存疑。
                            5. 输出中文，短句，便于页面卡片展示。
                            """)
                    .user("""
                            用户问题：
                            %s

                            产品需求片段：
                            %s

                            代码命中片段：
                            %s

                            请生成：
                            - summary：一句话说明当前需求的开发切入点。
                            - productUnderstanding：5-8 条产品规则理解，必须来自需求片段或由需求片段合理推导。
                            - developmentConstraints：5-8 条开发约束/影响，例如状态设计、接口字段、配置要求、幂等要求、展示影响。
                            - chainOverview：完整链路，按“产品规则 → 技术环节”表达。
                            - sections：7 个左右开发环节，每个包含 title、purpose、keyQuestions、changeSuggestions。
                            - implementationOrder：落地顺序。
                            - risks：风险点。
                            """.formatted(query, documentContext(documents), codeContext(code)))
                    .options(GenerationChatOptions.forModel(properties.llm().generationModel()))
                    .call()
                    .entity(PlanDraft.class);
            if (draft == null) {
                long durationMs = elapsedMillis(started);
                RagOutcome<PlanDraft> outcome = RagOutcome.degraded(PlanDraft.empty(), "llm.generate.plan",
                        "PLAN_GENERATION_FALLBACK", "模型未返回有效方案，已使用规则化方案", durationMs, 0);
                recordOutcome(outcome, documentId, version);
                return outcome;
            }
            RagOutcome<PlanDraft> outcome = RagOutcome.of(RagOutcomeStatus.SUCCESS, draft,
                    "llm.generate.plan", elapsedMillis(started), 1);
            recordOutcome(outcome, documentId, version);
            return outcome;
        }
        catch (RuntimeException exception) {
            long durationMs = elapsedMillis(started);
            RagOutcome<PlanDraft> outcome = RagOutcome.degraded(PlanDraft.empty(), "llm.generate.plan",
                    "PLAN_GENERATION_FALLBACK", "模型生成失败，已使用规则化方案", durationMs, 0);
            observability.outcome("llm.generate.plan", documentId, version, RagOutcomeStatus.DEGRADED,
                    durationMs, "PLAN_GENERATION_FALLBACK", exception);
            return outcome;
        }
    }

    private void collect(RagOutcome<?> outcome, List<RagWarning> warnings, List<RagStageDiagnostic> diagnostics) {
        warnings.addAll(outcome.warnings());
        diagnostics.addAll(outcome.stageDiagnostics());
    }

    private void recordOutcome(RagOutcome<?> outcome, String documentId, String version) {
        for (RagStageDiagnostic diagnostic : outcome.stageDiagnostics()) {
            String warningCode = outcome.warnings().isEmpty() ? null : outcome.warnings().getFirst().code();
            observability.outcome(diagnostic.stage(), documentId, version, diagnostic.status(),
                    diagnostic.durationMs(), warningCode, null);
        }
    }

    private RagOutcomeStatus overallStatus(List<ChunkRecord> documents, List<CodeChunk> code,
                                           List<RagWarning> warnings) {
        if (!warnings.isEmpty()) {
            return RagOutcomeStatus.DEGRADED;
        }
        return documents.isEmpty() && code.isEmpty() ? RagOutcomeStatus.NO_RESULTS : RagOutcomeStatus.SUCCESS;
    }

    private long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private String summary(String query, List<ChunkRecord> documents, List<CodeChunk> code) {
        if (documents.isEmpty() && code.isEmpty()) {
            return "没有从向量库命中足够证据。建议先确认需求文档和 Java 代码是否已完成索引，再重新分析「" + query + "」。";
        }
        return "围绕「" + query + "」已从向量库命中 " + documents.size() + " 段需求材料、"
                + code.size() + " 个代码片段。可以先用需求片段确认规则边界，再沿代码命中的类/方法定位入口和改动点。";
    }

    private List<String> steps(String query, List<ChunkRecord> documents, List<CodeChunk> code) {
        List<String> steps = new ArrayList<>();
        steps.add("先确认需求边界：围绕「" + query + "」梳理触发条件、购买/领取限制、奖励发放、展示入口和异常兜底。");
        if (!documents.isEmpty()) {
            steps.add("阅读命中的需求片段，抽出字段、状态流转、前端展示文案、运营配置项，形成一张小的规则清单。");
        }
        if (!code.isEmpty()) {
            steps.add("从代码命中最高的类/方法开始看入口，优先确认 Controller/API、Service 业务编排、配置读取、奖励发放、数据持久化这几层。");
            steps.add("按链路图逐个点开源码片段，标记需要新增或修改的方法；如果命中的是相邻系统，可以用类图切换到更粗粒度定位。");
        }
        steps.add("最后补测试：至少覆盖首次进入、购买/激活、奖励领取、重复领取、配置缺失、跨天/跨等级等关键路径。");
        return steps;
    }

    private List<String> productUnderstandingFallback(String query, List<ChunkRecord> documents) {
        if (documents.isEmpty()) {
            return List.of("没有命中明确的产品规则，需要先确认需求文档是否已导入向量库，或换产品里的业务名重新检索。");
        }
        List<String> understanding = new ArrayList<>();
        understanding.add("先围绕「" + query + "」确认触发条件、展示入口、购买/激活方式、奖励档位、领取限制和活动周期。");
        documents.stream()
                .map(ChunkRecord::parentText)
                .map(text -> excerpt(text, 140))
                .filter(this::hasText)
                .limit(5)
                .forEach(text -> understanding.add("命中规则片段：" + text));
        return understanding;
    }

    private List<String> developmentConstraintsFallback(List<ChunkRecord> documents) {
        List<String> constraints = new ArrayList<>();
        constraints.add("活动周期会影响 Redis key 设计：终身活动用固定版本，分期活动必须带 activityVersion/activityStartTime。");
        constraints.add("购买前已达成等级是否可补领会影响详情状态计算：详情接口需要实时读取角色等级和领取记录。");
        constraints.add("奖励档位应使用 rewardId/level 离散记录，避免后续插档导致最大已领取等级方案失效。");
        constraints.add("红点和入口展示应与购买态、等级达成、未领取状态解耦，避免未购买时持续打扰。");
        constraints.add("发奖和领取标记需要幂等保护，高价值奖励不能只靠前端按钮防重。");
        if (documents.isEmpty()) {
            constraints.add("当前没有产品片段支撑，开发约束只能按通用活动模型兜底；导入需求文档后可进一步细化。");
        }
        return constraints;
    }

    private DevelopmentPlanResponse.SimilarModule similarModule(String query, List<CodeChunk> code) {
        List<CodeChunk> passLike = filterCode(code, "pass", "fund", "activity", "reward", "recharge", "buy");
        String name = passLike.stream()
                .filter(chunk -> chunk.symbolName().toLowerCase(Locale.ROOT).contains("pass"))
                .findFirst()
                .map(chunk -> "Pass/通行证链路")
                .orElse("现有活动/奖励/充值链路");
        String reason = "先找最像「" + query + "」的老功能，不要从零写。优先复用活动开放、配置读取、购买扣费、奖励发放、领取状态、红点入口这些成熟链路；数据模型按新需求单独设计。";
        return new DevelopmentPlanResponse.SimilarModule(name, reason, passLike.stream().limit(6).toList());
    }

    private List<String> chainOverview(String query) {
        return List.of(
                "活动主配置/开关：判断「" + query + "」是否开放、属于哪一期、入口是否展示。",
                "接口入口：详情、购买页、购买动作、领奖动作要分开看，避免把展示和写操作混在一起。",
                "配置层：定义档位、价格、奖励、展示顺序、限购和活动期次。",
                "用户状态：保存是否购买、哪些档位已领取，key 必须能区分活动期次或终身版本。",
                "购买链路：校验开放 → 查充值配置 → 校验未购买 → 扣费/下单 → 发即时奖励 → 标记购买/事件。",
                "领奖链路：用户锁 → 校验购买态/等级/档位/未领取 → 发奖励 → 幂等标记领取。",
                "入口和红点：有可领取奖励时提示，未购买是否红点要由产品确认。",
                "测试和故障：重复购买、重复领奖、并发领奖、扣费成功响应超时、发奖后状态失败都要覆盖。"
        );
    }

    private List<DevelopmentPlanResponse.PlanSection> sections(String query, List<CodeChunk> code, PlanDraft draft) {
        if (!draft.sections().isEmpty()) {
            return sectionsFromDraft(draft.sections(), code);
        }
        List<DevelopmentPlanResponse.PlanSection> sections = new ArrayList<>();
        sections.add(section(
                "1. 先看活动开放机制",
                "确认这个需求是不是一个活动、如何按期次开放、入口什么时候出现。这里决定后续 Redis key 和配置是否要带活动开始时间/版本号。",
                filterCode(code, "activity", "open", "config", "main", "type"),
                List.of("是否终身一次，还是每期重置？", "活动时间按自然时间、注册时间还是服务器配置？", "过期后是否还能补领？"),
                List.of("新增活动类型，例如 GROWTH_FUND。", "在活动主配置中补入口、开放方式、期次字段。", "把活动期次纳入用户状态 key，避免跨期串数据。")
        ));
        sections.add(section(
                "2. 看接口层怎么组织",
                "先按读写拆接口：详情只读，购买和领奖是写操作，写操作需要用户锁或幂等保护。",
                filterCode(code, "service", "moa", "controller", "detail", "buy", "receive", "reward"),
                List.of("客户端需要哪些字段展示按钮状态？", "购买页和详情页是否合并？", "领奖是单档领取还是一键领取？"),
                List.of("增加详情、购买页、购买、领奖接口。", "DTO 返回 bought、currentLevel、remainTime、rewardList/status。", "购买/领奖接口不要信任客户端传入奖励内容，只传 fundId/rewardId。")
        ));
        sections.add(section(
                "3. 配置层",
                "把运营可调内容全部放配置，代码只负责解释配置。成长基金通常至少包含活动主配置、基金档位配置、充值配置。",
                filterCode(code, "config", "recharge", "reward", "level", "param"),
                List.of("每个档位按 level 还是 rewardId 唯一？", "购买即时奖励放哪里？", "价格和限购是否复用充值配置？"),
                List.of("新增 ConfigGrowthFund：fundId、activityNum、level、reward、order、show。", "新增 RechargeType.GROWTH_FUND，并关联 fundId/activityNum。", "确认 Config* 是否由配置平台生成，避免只改业务仓库。")
        ));
        sections.add(section(
                "4. 数据模型和持久化",
                "成长基金奖励通常是离散档位，不建议只存最大已领取等级；以后插档或改配置会出问题。",
                filterCode(code, "dao", "redis", "cache", "key", "receive", "buy", "state"),
                List.of("购买态和领取态是否要分开？", "key 用活动开始时间、activityNum 还是固定版本？", "领取标记是否需要流水幂等？"),
                List.of("购买态：growth_fund:buy:{fundId}:{activityVersion}。", "领取态：growth_fund:receive:{fundId}:{activityVersion}，用 rewardId/level 做成员。", "DAO 提供 isBought、grabBuy、getReceived、grabReceive、markReceived、rollbackLock。")
        ));
        sections.add(section(
                "5. 购买链路",
                "购买不是简单置状态，要和支付/扣费、即时奖励、充值事件、幂等状态串起来。",
                filterCode(code, "buy", "recharge", "pay", "balance", "gift", "submit"),
                List.of("扣费成功但响应超时怎么办？", "重复点击购买是否会重复扣费？", "购买即时奖励是否必须和购买态原子化？"),
                List.of("校验活动开放和未购买。", "查 ConfigRecharge 并 checkBalance。", "用 Redis NX 或订单流水抢购买资格。", "扣费成功后发即时奖励并触发充值成功事件。")
        ));
        sections.add(section(
                "6. 详情和领奖链路",
                "详情负责告诉前端每档状态；领奖负责强校验并发奖，必须在服务端重新计算状态。",
                filterCode(code, "detail", "receive", "reward", "add", "bonus", "level", "tier"),
                List.of("购买前是否能看到奖励？", "达成等级后是否可补领以前档位？", "领奖失败是否能重试且不重复发奖？"),
                List.of("详情返回 LOCKED / RECEIVABLE / RECEIVED。", "领奖流程：用户锁 → 开放校验 → 已购买 → 等级达标 → 未领取 → canAdd → 发奖 → 标记领取。", "高价值奖励建议用 momoId + fundId + version + rewardId 做幂等号。")
        ));
        sections.add(section(
                "7. 活动入口、红点和功能解锁",
                "功能上线不只接口能跑，还要能被玩家看到，并且红点不能乱闪。",
                filterCode(code, "index", "red", "point", "module", "function", "welfare", "bonus"),
                List.of("未购买时是否展示红点？", "角色等级未到是否展示入口？", "活动结束后入口是否隐藏？"),
                List.of("接入活动首页/福利入口。", "新增 ActivityModuleType/FunctionType 之类的入口枚举。", "红点条件建议：已购买 && 存在已达等级未领取档位。")
        ));
        return sections;
    }

    private List<DevelopmentPlanResponse.PlanSection> sectionsFromDraft(List<DraftSection> draftSections, List<CodeChunk> code) {
        List<DevelopmentPlanResponse.PlanSection> sections = new ArrayList<>();
        for (DraftSection draft : draftSections) {
            sections.add(new DevelopmentPlanResponse.PlanSection(
                    Objects.toString(draft.title(), "开发环节"),
                    Objects.toString(draft.purpose(), ""),
                    sectionTargets(draft, code),
                    safeList(draft.keyQuestions()),
                    safeList(draft.changeSuggestions())));
        }
        return sections;
    }

    private List<CodeChunk> sectionTargets(DraftSection draft, List<CodeChunk> code) {
        String title = Objects.toString(draft.title(), "");
        String purpose = Objects.toString(draft.purpose(), "");
        String text = (title + " " + purpose).toLowerCase(Locale.ROOT);
        if (containsAny(text, "开放", "活动", "入口")) {
            return filterCode(code, "activity", "open", "config", "main", "index").stream().limit(6).toList();
        }
        if (containsAny(text, "接口", "详情", "service", "controller")) {
            return filterCode(code, "service", "moa", "controller", "detail").stream().limit(6).toList();
        }
        if (containsAny(text, "配置", "档位", "奖励", "价格")) {
            return filterCode(code, "config", "recharge", "reward", "level", "param").stream().limit(6).toList();
        }
        if (containsAny(text, "状态", "持久", "redis", "dao", "领取态", "购买态")) {
            return filterCode(code, "dao", "redis", "cache", "key", "receive", "buy", "state").stream().limit(6).toList();
        }
        if (containsAny(text, "购买", "支付", "扣费", "充值")) {
            return filterCode(code, "buy", "recharge", "pay", "balance", "gift", "submit").stream().limit(6).toList();
        }
        if (containsAny(text, "领奖", "领取", "发奖")) {
            return filterCode(code, "receive", "reward", "add", "bonus", "level", "tier").stream().limit(6).toList();
        }
        return code.stream().limit(6).toList();
    }

    private DevelopmentPlanResponse.PlanSection section(String title, String purpose, List<CodeChunk> inspectTargets,
                                                        List<String> keyQuestions, List<String> changeSuggestions) {
        return new DevelopmentPlanResponse.PlanSection(title, purpose, inspectTargets.stream().limit(6).toList(),
                keyQuestions, changeSuggestions);
    }

    private List<String> implementationOrder(String query) {
        return List.of(
                "确认规则：" + query + " 是终身一次还是分期，购买前达成等级能否补领，到期后是否补领。",
                "补配置：活动主配置、基金档位配置、充值配置和展示文案。",
                "补枚举：ActivityType、ActivityModuleType、RechargeType、奖励/扣费/BI 来源。",
                "实现 DAO：购买态、领取态、抢占锁、幂等标记。",
                "实现 ConfigModel：按活动期次读取基金档位和充值配置。",
                "实现业务 Model：详情、购买、领奖、红点状态。",
                "实现 API DTO 和 MoaService/Controller 接口。",
                "接入活动入口、福利页、功能解锁和红点聚合。",
                "补测试：重复购买、重复领奖、并发领奖、跨期、配置缺失、扣费成功但响应失败。",
                "压一遍故障：发奖后状态写入失败、活动临界过期、配置热更新插档。"
        );
    }

    private List<String> risks(List<ChunkRecord> documents, List<CodeChunk> code) {
        Set<String> risks = new LinkedHashSet<>();
        if (documents.isEmpty()) {
            risks.add("需求文档没有命中：方案可能只基于代码相似模块，需要先补充或重新导入需求文档向量。");
        }
        if (code.isEmpty()) {
            risks.add("代码没有命中：可能代码尚未索引，或关键词与项目命名不一致，需要换业务别名再查。");
        }
        risks.add("成长基金通常会跨配置、付费/激活、奖励、红点/入口展示、日志埋点，开发时不要只改单个接口。");
        risks.add("如果已有类似基金/月卡/战令系统，优先复用它们的数据结构和发奖链路，避免新增一套孤立状态。");
        return new ArrayList<>(risks);
    }

    private List<CodeChunk> filterCode(List<CodeChunk> code, String... keywords) {
        return code.stream()
                .filter(chunk -> matches(chunk, keywords))
                .toList();
    }

    private boolean matches(CodeChunk chunk, String... keywords) {
        String haystack = (Objects.toString(chunk.symbolName(), "") + " "
                + Objects.toString(chunk.filePath(), "") + " "
                + Objects.toString(chunk.text(), "")).toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (haystack.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String documentContext(List<ChunkRecord> documents) {
        StringBuilder builder = new StringBuilder();
        for (ChunkRecord document : documents) {
            if (builder.length() >= MAX_DOCUMENT_CONTEXT_CHARS) {
                break;
            }
            builder.append("来源：").append(document.filename()).append('\n')
                    .append(excerpt(document.parentText(), 1_200)).append("\n---\n");
        }
        return builder.toString();
    }

    private String codeContext(List<CodeChunk> code) {
        StringBuilder builder = new StringBuilder();
        for (CodeChunk chunk : code) {
            if (builder.length() >= MAX_CODE_CONTEXT_CHARS) {
                break;
            }
            builder.append(chunk.symbolType()).append(" ").append(chunk.symbolName())
                    .append(" @ ").append(chunk.filePath()).append(":").append(chunk.startLine()).append('\n')
                    .append(excerpt(chunk.text(), 700)).append("\n---\n");
        }
        return builder.toString();
    }

    private List<String> firstNonEmpty(List<String> preferred, List<String> fallback) {
        return preferred == null || preferred.isEmpty() ? fallback : preferred;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String excerpt(String text, int maxChars) {
        String normalized = Objects.toString(text, "").replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars - 1) + "…";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record PlanDraft(
            String summary,
            List<String> productUnderstanding,
            List<String> developmentConstraints,
            List<String> chainOverview,
            List<DraftSection> sections,
            List<String> implementationOrder,
            List<String> risks
    ) {
        private PlanDraft {
            productUnderstanding = productUnderstanding == null ? List.of() : productUnderstanding;
            developmentConstraints = developmentConstraints == null ? List.of() : developmentConstraints;
            chainOverview = chainOverview == null ? List.of() : chainOverview;
            sections = sections == null ? List.of() : sections;
            implementationOrder = implementationOrder == null ? List.of() : implementationOrder;
            risks = risks == null ? List.of() : risks;
        }

        private static PlanDraft empty() {
            return new PlanDraft("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    private record DraftSection(String title, String purpose, List<String> keyQuestions, List<String> changeSuggestions) {
    }
}
