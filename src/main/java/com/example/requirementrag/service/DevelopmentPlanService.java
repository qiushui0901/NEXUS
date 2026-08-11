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
import com.example.requirementrag.evidence.CitedText;
import com.example.requirementrag.evidence.EvidenceCitationService;
import com.example.requirementrag.evidence.EvidenceRegistry;
import com.example.requirementrag.evidence.PlanCitationBundle;
import com.example.requirementrag.evidence.PlanSectionCitation;
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
    private final EvidenceCitationService citationService;

    @Autowired
    public DevelopmentPlanService(RagProperties properties, RetrievalPipeline retrievalPipeline,
                                  ChatClient chatClient, RagObservability observability,
                                  KnowledgeConflictService conflictService,
                                  EvidenceCitationService citationService) {
        this.properties = properties;
        this.retrievalPipeline = retrievalPipeline;
        this.chatClient = chatClient;
        this.observability = observability;
        this.conflictService = conflictService;
        this.citationService = citationService;
    }

    /** 向后兼容构造器，供聚焦单元测试与嵌入式调用方使用。 */
    public DevelopmentPlanService(RagProperties properties, RetrievalPipeline retrievalPipeline,
                                  ChatClient chatClient, RagObservability observability,
                                  KnowledgeConflictService conflictService) {
        this(properties, retrievalPipeline, chatClient, observability, conflictService,
                new EvidenceCitationService());
    }

    /** 向后兼容构造器，供聚焦单元测试与嵌入式调用方使用。 */
    public DevelopmentPlanService(RagProperties properties, RetrievalPipeline retrievalPipeline,
                                  ChatClient chatClient, RagObservability observability) {
        this(properties, retrievalPipeline, chatClient, observability, new KnowledgeConflictService(),
                new EvidenceCitationService());
    }

    /** 向后兼容构造器，供聚焦单元测试与嵌入式调用方使用。 */
    public DevelopmentPlanService(RagProperties properties, ProjectRegistry projectRegistry,
                                  QueryRouter queryRouter, QdrantHybridStore documentStore,
                                  CodeKnowledgeService codeKnowledgeService, ChatClient chatClient,
                                  RagObservability observability) {
        this(properties, new RetrievalPipeline(properties, projectRegistry, queryRouter, documentStore,
                codeKnowledgeService, observability), chatClient, observability, new KnowledgeConflictService(),
                new EvidenceCitationService());
    }

    /**
     * 结合需求文档与代码检索结果，生成可落地的开发入手建议。
     * 各环节优先采用模型生成内容，缺省或模型失败时回退到规则化内容，并统一完成证据引用。
     *
     * @param query      用户提出的需求问题
     * @param documentId 需求文档 ID，可空（由检索管线解析）
     * @param version    需求文档版本，可空（由检索管线解析）
     * @param projectId  项目 ID，可空（由路由解析）
     * @param limit      检索证据条数上限，可空（使用默认值）
     * @return 含各开发环节、证据引用与状态诊断的完整开发方案
     */
    public DevelopmentPlanResponse plan(String query, String documentId, String version, String projectId, Integer limit) {
        RagOutcome<RetrievalBundle> retrieval = retrievalPipeline.execute(new RetrievalRequest(
                query, RetrievalProfile.DEVELOPMENT_PLAN, projectId, documentId, version, limit));
        RetrievalBundle bundle = retrieval.data();
        String resolvedDocumentId = bundle.documentId();
        String resolvedVersion = bundle.version();
        List<ChunkRecord> documents = bundle.requirementEvidence();
        List<CodeChunk> code = bundle.codeEvidence();
        EvidenceRegistry registry = EvidenceRegistry.from(bundle);
        EvidenceCitationService.Session citationSession = citationService.open(registry);

        List<RagWarning> warnings = new ArrayList<>(retrieval.warnings());
        List<RagStageDiagnostic> diagnostics = new ArrayList<>(retrieval.stageDiagnostics());

        EvidenceRegistry.ContextSlice requirementSlice = registry.requirementContextSlice(
                documents, MAX_DOCUMENT_CONTEXT_CHARS);
        if (requirementSlice.omittedChunks() > 0) {
            String message = "需求正文超出上下文预算，省略 " + requirementSlice.omittedChunks()
                    + " 个块（覆盖 " + requirementSlice.coveredModules() + " 个模块）";
            warnings.add(new RagWarning("llm.generate.plan", "CONTEXT_TRUNCATED", message, 0));
            diagnostics.add(new RagStageDiagnostic("llm.generate.plan", RagOutcomeStatus.DEGRADED,
                    0, requirementSlice.includedChunks()));
        }

        RagOutcome<PlanDraft> draftOutcome = draftWithProductContext(query, documents, code,
                resolvedDocumentId, resolvedVersion, registry, requirementSlice);
        collect(draftOutcome, warnings, diagnostics);
        PlanDraft draft = draftOutcome.data();

        DraftCitedText summaryDraft = preferredClaim(draft.summary(), summary(query, documents, code));
        List<DraftCitedText> productDrafts = preferredClaims(draft.productUnderstanding(),
                productUnderstandingFallback(query, documents));
        List<DraftCitedText> constraintDrafts = preferredClaims(draft.developmentConstraints(),
                developmentConstraintsFallback(documents));
        List<DraftCitedText> chainDrafts = preferredClaims(draft.chainOverview(), chainOverview(query));
        List<DraftCitedText> implementationDrafts = preferredClaims(draft.implementationOrder(),
                implementationOrder(query));
        List<DraftCitedText> stepDrafts = preferredClaims(draft.steps(), steps(query, documents, code));
        List<DraftCitedText> riskDrafts = preferredClaims(draft.risks(), risks(documents, code));

        DevelopmentPlanResponse.SimilarModule similarModule = similarModule(query, code);
        List<DevelopmentPlanResponse.PlanSection> planSections = sections(query, code, draft);

        CitedText summaryCitation = cite(citationSession, summaryDraft);
        List<CitedText> productCitations = cite(citationSession, productDrafts);
        List<CitedText> constraintCitations = cite(citationSession, constraintDrafts);
        CitedText similarCitation = citationSession.cite(
                similarModule.name() + "：" + similarModule.reason(),
                registry.evidenceIdsForCode(similarModule.references()));
        List<CitedText> chainCitations = cite(citationSession, chainDrafts);
        List<PlanSectionCitation> sectionCitations = sectionCitations(citationSession, registry,
                planSections, draft.sections());
        List<CitedText> implementationCitations = cite(citationSession, implementationDrafts);
        List<CitedText> stepCitations = cite(citationSession, stepDrafts);
        List<CitedText> riskCitations = cite(citationSession, riskDrafts);

        appendWarnings(warnings, citationSession.warnings());
        RagOutcomeStatus status = overallStatus(documents, code, warnings);
        PlanCitationBundle citations = new PlanCitationBundle(
                summaryCitation,
                productCitations,
                constraintCitations,
                similarCitation,
                chainCitations,
                sectionCitations,
                implementationCitations,
                stepCitations,
                riskCitations,
                citationSession.references(),
                citationSession.quality());

        return new DevelopmentPlanResponse(
                query,
                resolvedDocumentId,
                resolvedVersion,
                summaryDraft.text(),
                texts(productDrafts),
                texts(constraintDrafts),
                similarModule,
                texts(chainDrafts),
                planSections,
                texts(implementationDrafts),
                texts(stepDrafts),
                texts(riskDrafts),
                documents.stream()
                        .map(chunk -> new DevelopmentPlanResponse.DocumentReference(chunk.filename(), excerpt(chunk.parentText(), 260)))
                        .toList(),
                code,
                status,
                warnings,
                diagnostics,
                retrievalConflictReport(bundle),
                citations);
    }

    /** 将检索到的需求与代码证据转换为知识声明，交给冲突分析服务做版本一致性检查。 */
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

    /** 带产品上下文调用生成模型产出方案草稿；无证据或模型失败时降级为空草稿并记录可观测结果。 */
    private RagOutcome<PlanDraft> draftWithProductContext(String query, List<ChunkRecord> documents,
                                                           List<CodeChunk> code, String documentId, String version,
                                                           EvidenceRegistry registry,
                                                           EvidenceRegistry.ContextSlice requirementSlice) {
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
                            6. 每条结论必须输出 text 和 evidenceIds；evidenceIds 只能从上下文的 [evidenceId=...] 中选择，不得编造。
                            7. 一个结论没有直接证据时 evidenceIds 返回空数组，不能用无关证据凑数。
                            8. 输出中文，短句，便于页面卡片展示。
                            """)
                    .user("""
                            用户问题：
                            %s

                            产品需求片段：
                            %s

                            代码命中片段：
                            %s

                            请生成：
                            - summary：对象 {text, evidenceIds}，一句话说明当前需求的开发切入点。
                            - productUnderstanding：5-8 个 {text, evidenceIds}，必须来自需求证据或由它合理推导。
                            - developmentConstraints：5-8 个 {text, evidenceIds}，说明状态、接口、配置、幂等或展示影响。
                            - chainOverview：若干 {text, evidenceIds}，按“产品规则 → 技术环节”表达。
                            - sections：7 个左右开发环节，每个包含 title、purpose、keyQuestions、changeSuggestions、evidenceIds。
                            - implementationOrder：若干 {text, evidenceIds}。
                            - steps：若干 {text, evidenceIds}，描述具体落地步骤。
                            - risks：若干 {text, evidenceIds}。
                            """.formatted(query,
                            requirementSlice.text(),
                            registry.promptCodeContext(code, MAX_CODE_CONTEXT_CHARS)))
                    .options(GenerationChatOptions.forModel(properties.llm().resolvedDevelopmentPlanModel()))
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
            String warningCode = outcome.warnings().isEmpty() ? null : outcome.warnings().get(0).code();
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
        return "围绕“" + query + "”，先核对 " + documents.size() + " 条需求证据，再从 "
                + code.size() + " 个代码命中中确认入口、状态和验证链路。";
    }

    private List<String> steps(String query, List<ChunkRecord> documents, List<CodeChunk> code) {
        return List.of(
                "确认问题边界：明确“" + query + "”涉及的输入、输出、状态变化和不变条件。",
                "逐条阅读命中的需求父块，区分明确规则、合理推导和缺失信息。",
                "从代码命中定位调用入口、核心服务、持久化位置和外部依赖。",
                "按最小闭环拆分改动，并为每个环节补充可回查证据。",
                "覆盖正常、重复、并发、失败重试和版本兼容场景，再进行灰度验证。",
                "当前证据规模：需求 " + documents.size() + " 条，代码 " + code.size() + " 条。"
        );
    }

    private List<String> productUnderstandingFallback(String query, List<ChunkRecord> documents) {
        if (documents.isEmpty()) {
            return List.of("当前没有命中需求证据，不能把“" + query + "”解释为确定规则。需要补充或重新检索需求文档。");
        }
        List<String> result = new ArrayList<>();
        int index = 1;
        for (ChunkRecord document : documents.stream().limit(6).toList()) {
            result.add("需求片段 " + index++ + "：" + excerpt(document.parentText(), 220));
        }
        return result;
    }

    private List<String> developmentConstraintsFallback(List<ChunkRecord> documents) {
        List<String> constraints = new ArrayList<>();
        constraints.add("接口与数据模型必须能表达需求中的状态、边界和版本条件，不能只依赖页面展示逻辑。");
        constraints.add("写操作需要明确幂等键、并发保护、失败补偿和可观测记录。");
        constraints.add("配置、缓存和持久化键需要包含足够的业务范围，避免不同版本或范围互相污染。");
        constraints.add("所有推导性结论必须标记证据不足，不能替代需求确认。当前命中需求父块 " + documents.size() + " 个。");
        return constraints;
    }

    private DevelopmentPlanResponse.SimilarModule similarModule(String query, List<CodeChunk> code) {
        if (code.isEmpty()) {
            return new DevelopmentPlanResponse.SimilarModule("未命中可复用模块",
                    "代码检索没有返回证据，不能可靠判断复用入口。", List.of());
        }
        List<CodeChunk> references = code.stream().limit(6).toList();
        CodeChunk first = references.get(0);
        String name = hasText(first.symbolName()) ? first.symbolName() : first.filePath();
        return new DevelopmentPlanResponse.SimilarModule(name,
                "这些代码命中与“" + query + "”最接近，应先确认真实调用关系，再决定复用或新增。", references);
    }

    private List<String> chainOverview(String query) {
        return List.of(
                "需求规则 → 输入输出与边界条件",
                "接口入口 → 参数校验与权限范围",
                "领域服务 → 状态迁移与业务不变式",
                "持久化/缓存 → 幂等、并发和版本隔离",
                "外部依赖 → 超时、重试、补偿和审计",
                "测试与发布 → 回归、灰度、监控和回滚",
                "目标问题 → " + query
        );
    }

    private List<DevelopmentPlanResponse.PlanSection> sections(String query, List<CodeChunk> code, PlanDraft draft) {
        if (!draft.sections().isEmpty()) {
            return sectionsFromDraft(draft.sections(), code);
        }
        return List.of(
                section("1. 规则与边界", "把命中的需求证据整理为输入、输出、状态、边界和待确认项。",
                        code.stream().limit(4).toList(),
                        List.of("哪些内容是明确规则？", "哪些结论只是推导？", "版本和范围条件是什么？"),
                        List.of("先形成规则清单和证据映射，再开始设计接口与数据结构。")),
                section("2. 调用入口与契约", "定位现有入口、请求模型和返回模型，确认改动是否能保持兼容。",
                        filterCode(code, "controller", "api", "request", "response", "handler", "service"),
                        List.of("入口是否已有？", "字段变更是否向后兼容？", "权限和参数由哪层校验？"),
                        List.of("优先扩展现有契约；新增字段保持可选，并补充兼容测试。")),
                section("3. 状态与持久化", "确认核心状态迁移、持久化位置、缓存范围和并发策略。",
                        filterCode(code, "service", "repository", "dao", "store", "cache", "redis", "state"),
                        List.of("状态由谁拥有？", "并发写入如何串行化？", "失败后如何恢复？"),
                        List.of("显式定义状态迁移、幂等键、事务边界和补偿路径。")),
                section("4. 验证与发布", "用真实测试结果和运行指标证明改动可用，而不是把建议当作执行结论。",
                        filterCode(code, "test", "spec", "config", "metric", "log"),
                        List.of("哪些场景必须回归？", "如何灰度和回滚？", "哪些指标能发现异常？"),
                        List.of("补充单元、集成和失败场景测试，并记录可复现的执行快照。"))
        );
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
        String text = (Objects.toString(draft.title(), "") + " "
                + Objects.toString(draft.purpose(), "")).toLowerCase(Locale.ROOT);
        if (containsAny(text, "接口", "入口", "controller", "api", "request")) {
            return filterCode(code, "controller", "api", "request", "response", "handler", "service").stream()
                    .limit(6).toList();
        }
        if (containsAny(text, "状态", "持久", "缓存", "数据", "repository", "dao", "redis")) {
            return filterCode(code, "service", "repository", "dao", "store", "cache", "redis", "state").stream()
                    .limit(6).toList();
        }
        if (containsAny(text, "测试", "验证", "发布", "监控", "test", "metric")) {
            return filterCode(code, "test", "spec", "config", "metric", "log").stream().limit(6).toList();
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
                "先确认“" + query + "”的规则边界和证据缺口。",
                "再定位入口、调用链和状态所有者。",
                "随后设计兼容的数据契约、状态迁移和异常策略。",
                "按最小闭环实现并补齐测试。",
                "最后用真实执行结果完成灰度、监控和回滚验证。"
        );
    }

    private List<String> risks(List<ChunkRecord> documents, List<CodeChunk> code) {
        Set<String> risks = new LinkedHashSet<>();
        if (documents.isEmpty()) {
            risks.add("没有命中需求证据，当前方案不能作为确定业务规则使用。");
        }
        if (code.isEmpty()) {
            risks.add("没有命中代码证据，当前方案不能确认真实改动位置和调用影响。");
        }
        risks.add("需求、代码和 Wiki 可能处于不同版本，实施前必须核对项目、版本和提交范围。");
        risks.add("模型生成内容可能包含合理但未证实的推导，必须以服务端验证后的引用状态为准。");
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

    private DraftCitedText preferredClaim(DraftCitedText preferred, String fallback) {
        return preferred != null && hasText(preferred.text())
                ? preferred
                : new DraftCitedText(fallback, List.of());
    }

    private List<DraftCitedText> preferredClaims(List<DraftCitedText> preferred, List<String> fallback) {
        if (preferred != null && !preferred.isEmpty()) return preferred;
        return fallback.stream().map(text -> new DraftCitedText(text, List.of())).toList();
    }

    private CitedText cite(EvidenceCitationService.Session session, DraftCitedText claim) {
        return session.cite(claim.text(), claim.evidenceIds());
    }

    private List<CitedText> cite(EvidenceCitationService.Session session, List<DraftCitedText> claims) {
        return claims.stream().map(claim -> cite(session, claim)).toList();
    }

    private List<String> texts(List<DraftCitedText> claims) {
        return claims.stream().map(DraftCitedText::text).filter(this::hasText).toList();
    }

    private List<PlanSectionCitation> sectionCitations(EvidenceCitationService.Session session,
                                                       EvidenceRegistry registry,
                                                       List<DevelopmentPlanResponse.PlanSection> sections,
                                                       List<DraftSection> drafts) {
        List<PlanSectionCitation> result = new ArrayList<>();
        for (int index = 0; index < sections.size(); index++) {
            DevelopmentPlanResponse.PlanSection section = sections.get(index);
            List<String> evidenceIds = index < drafts.size()
                    ? drafts.get(index).evidenceIds()
                    : registry.evidenceIdsForCode(section.inspectTargets());
            result.add(session.citeSection(index, section.title(), evidenceIds));
        }
        return List.copyOf(result);
    }

    private void appendWarnings(List<RagWarning> target, List<RagWarning> additions) {
        Set<String> existing = new LinkedHashSet<>();
        for (RagWarning warning : target) {
            existing.add(warning.code() + "|" + warning.message());
        }
        for (RagWarning warning : additions) {
            if (existing.add(warning.code() + "|" + warning.message())) {
                target.add(warning);
            }
        }
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

    /** 模型生成的完整方案草稿，各字段在构造时归一化，避免下游空指针。 */
    private record PlanDraft(
            DraftCitedText summary,
            List<DraftCitedText> productUnderstanding,
            List<DraftCitedText> developmentConstraints,
            List<DraftCitedText> chainOverview,
            List<DraftSection> sections,
            List<DraftCitedText> implementationOrder,
            List<DraftCitedText> steps,
            List<DraftCitedText> risks
    ) {
        private PlanDraft {
            summary = summary == null ? DraftCitedText.empty() : summary;
            productUnderstanding = productUnderstanding == null ? List.of() : List.copyOf(productUnderstanding);
            developmentConstraints = developmentConstraints == null ? List.of() : List.copyOf(developmentConstraints);
            chainOverview = chainOverview == null ? List.of() : List.copyOf(chainOverview);
            sections = sections == null ? List.of() : List.copyOf(sections);
            implementationOrder = implementationOrder == null ? List.of() : List.copyOf(implementationOrder);
            steps = steps == null ? List.of() : List.copyOf(steps);
            risks = risks == null ? List.of() : List.copyOf(risks);
        }

        private static PlanDraft empty() {
            return new PlanDraft(DraftCitedText.empty(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of());
        }
    }

    /** 带证据引用的草稿文本，文本与证据列表均不允许为 null。 */
    private record DraftCitedText(String text, List<String> evidenceIds) {
        private DraftCitedText {
            text = text == null ? "" : text;
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }

        private static DraftCitedText empty() {
            return new DraftCitedText("", List.of());
        }
    }

    /** 模型生成的单个开发环节草稿，关键问题与改动建议列表不允许为 null。 */
    private record DraftSection(String title, String purpose, List<String> keyQuestions,
                                List<String> changeSuggestions, List<String> evidenceIds) {
        private DraftSection {
            keyQuestions = keyQuestions == null ? List.of() : List.copyOf(keyQuestions);
            changeSuggestions = changeSuggestions == null ? List.of() : List.copyOf(changeSuggestions);
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

}
