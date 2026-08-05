package com.example.requirementrag.mcp;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.code.CodeIntelligenceService;
import com.example.requirementrag.conflict.KnowledgeConflictModels.KnowledgeClaim;
import com.example.requirementrag.conflict.KnowledgeConflictModels.KnowledgeConflictReport;
import com.example.requirementrag.conflict.KnowledgeConflictService;
import com.example.requirementrag.evidence.EvidenceRef;
import com.example.requirementrag.evidence.EvidenceRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.DevelopmentPlanResponse;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.model.SourceSnippet;
import com.example.requirementrag.model.CodeIntelligenceResponse;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalPipeline;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import com.example.requirementrag.service.DevelopmentPlanService;
import com.example.requirementrag.service.ReviewFacadeService;
import com.example.requirementrag.model.ReviewRequest;
import com.example.requirementrag.model.DoubtBatch;
import com.example.requirementrag.versioning.VersionComparisonService;
import com.example.requirementrag.versioning.VersionModels.VersionComparisonReport;
import com.example.requirementrag.wiki.WikiModels;
import com.example.requirementrag.wiki.WikiRepository;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面向既有 NEXUS 领域服务的只读 MCP 工具门面：
 * 暴露需求检索、代码检索、源码读取、开发计划、Wiki 页面、版本差异、
 * 代码图/影响分析、需求存疑与冲突检查等工具，全部经 {@link McpToolInvocationService}
 * 统一完成认证、权限、审计与指标，返回结果经 {@link McpResponsePolicy} 做边界约束与脱敏。
 */
@Component
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NexusMcpTools {

    private final RetrievalPipeline retrievalPipeline;
    private final CodeKnowledgeService codeKnowledgeService;
    private final DevelopmentPlanService developmentPlanService;
    private final WikiRepository wikiRepository;
    private final VersionComparisonService versionComparisonService;
    private final McpResponsePolicy policy;
    private final McpToolInvocationService invocations;
    private final CodeIntelligenceService codeIntelligenceService;
    private final ReviewFacadeService reviewFacadeService;
    private final KnowledgeConflictService knowledgeConflictService;

    @Autowired
    public NexusMcpTools(RetrievalPipeline retrievalPipeline, CodeKnowledgeService codeKnowledgeService,
                         DevelopmentPlanService developmentPlanService, WikiRepository wikiRepository,
                         VersionComparisonService versionComparisonService, McpResponsePolicy policy,
                         McpToolInvocationService invocations,
                         CodeIntelligenceService codeIntelligenceService,
                         ReviewFacadeService reviewFacadeService,
                         KnowledgeConflictService knowledgeConflictService) {
        this.retrievalPipeline = retrievalPipeline;
        this.codeKnowledgeService = codeKnowledgeService;
        this.developmentPlanService = developmentPlanService;
        this.wikiRepository = wikiRepository;
        this.versionComparisonService = versionComparisonService;
        this.policy = policy;
        this.invocations = invocations;
        this.codeIntelligenceService = codeIntelligenceService;
        this.reviewFacadeService = reviewFacadeService;
        this.knowledgeConflictService = knowledgeConflictService;
    }

    /** 为未提供代码图/评审/冲突服务的旧版调用方（0.7 之前）保留的兼容构造器。 */
    NexusMcpTools(RetrievalPipeline retrievalPipeline, CodeKnowledgeService codeKnowledgeService,
                  DevelopmentPlanService developmentPlanService, WikiRepository wikiRepository,
                  VersionComparisonService versionComparisonService, McpResponsePolicy policy,
                  McpToolInvocationService invocations) {
        this(retrievalPipeline, codeKnowledgeService, developmentPlanService, wikiRepository,
                versionComparisonService, policy, invocations, null, null, null);
    }

    /** 为提供代码图与评审服务但尚未接入冲突服务的 0.7 调用方保留的兼容构造器。 */
    NexusMcpTools(RetrievalPipeline retrievalPipeline, CodeKnowledgeService codeKnowledgeService,
                  DevelopmentPlanService developmentPlanService, WikiRepository wikiRepository,
                  VersionComparisonService versionComparisonService, McpResponsePolicy policy,
                  McpToolInvocationService invocations, CodeIntelligenceService codeIntelligenceService,
                  ReviewFacadeService reviewFacadeService) {
        this(retrievalPipeline, codeKnowledgeService, developmentPlanService, wikiRepository,
                versionComparisonService, policy, invocations, codeIntelligenceService, reviewFacadeService, null);
    }

    /**
     * 检索版本作用域下的需求证据，返回带稳定证据 ID 的命中列表。
     * 结果条数收敛到 [1,20]，证据条目受限，且标记需求正文是否被截断。
     *
     * @param context    MCP 同步请求上下文
     * @param query      自然语言需求查询
     * @param projectId  项目 ID，null 时走默认项目
     * @param documentId 需求文档 ID，可为 null
     * @param version    需求版本，可为 null
     * @param limit      最大命中条数，1-20
     * @return 需求命中列表 + 证据 + 状态/截断标记
     */
    @McpTool(
            name = "nexus_search_requirements",
            description = "Search version-scoped requirement evidence. Returns stable requirement evidence IDs.",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public McpToolResponse<List<McpResponsePolicy.RequirementHit>> searchRequirements(
            McpSyncRequestContext context,
            @McpToolParam(description = "Natural-language requirement query") String query,
            @McpToolParam(description = "Project ID; defaults to the configured project", required = false)
            String projectId,
            @McpToolParam(description = "Requirement document ID", required = false) String documentId,
            @McpToolParam(description = "Requirement version", required = false) String version,
            @McpToolParam(description = "Maximum hits, 1-20", required = false) Integer limit) {
        return invocations.invoke("nexus_search_requirements", context, projectId, version, Permission.PUBLIC_READ,
                effectiveProject -> {
                    int resolvedLimit = policy.limit(limit);
                    RagOutcome<RetrievalBundle> outcome = dependency(() -> retrievalPipeline.execute(new RetrievalRequest(
                            policy.required(query, "query"), RetrievalProfile.REQUIREMENT_REVIEW, effectiveProject, documentId, version,
                            resolvedLimit)));
                    RetrievalBundle bundle = outcome.data();
                    EvidenceRegistry registry = EvidenceRegistry.from(bundle);
                    List<McpResponsePolicy.RequirementHit> hits = bundle.requirementEvidence().stream()
                            .limit(resolvedLimit)
                            .map(chunk -> policy.requirement(chunk, registry.evidenceId(chunk).orElse("")))
                            .toList();
                    List<EvidenceRef> evidence = policy.evidence(registry.references());
                    boolean truncated = policy.truncated(rawLimit(limit), bundle.requirementEvidence().size(),
                            registry.references())
                            || bundle.requirementEvidence().stream().limit(resolvedLimit)
                            .anyMatch(chunk -> policy.textTruncated(chunk.parentText()));
                    return new McpToolResponse<>(scope(bundle.resolvedProjectId(), bundle.version(),
                            bundle.documentId()), hits, evidence, Map.of("status", outcome.status()),
                            outcome.warnings(), truncated);
                });
    }

    /**
     * 检索仓库代码，返回带稳定代码证据 ID 的截断摘录列表。
     *
     * @param context   MCP 同步请求上下文
     * @param query     自然语言代码查询
     * @param projectId 项目 ID，null 时走默认项目
     * @param limit     最大命中条数，1-20
     * @return 代码命中列表 + 证据 + 截断标记
     */
    @McpTool(
            name = "nexus_search_code",
            description = "Search repository code and return bounded excerpts with stable code evidence IDs.",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public McpToolResponse<List<McpResponsePolicy.CodeHit>> searchCode(
            McpSyncRequestContext context,
            @McpToolParam(description = "Natural-language code query") String query,
            @McpToolParam(description = "Project ID; defaults to the configured project", required = false)
            String projectId,
            @McpToolParam(description = "Maximum hits, 1-20", required = false) Integer limit) {
        return invocations.invoke("nexus_search_code", context, projectId, null, Permission.PUBLIC_READ,
                effectiveProject -> {
                    int resolvedLimit = policy.limit(limit);
                    List<CodeChunk> chunks = dependency(() -> codeKnowledgeService.search(policy.required(query, "query"), effectiveProject, resolvedLimit));
                    RetrievalBundle bundle = new RetrievalBundle(query, RetrievalProfile.DEVELOPMENT_PLAN,
                            effectiveProject, null, null, List.of(), chunks);
                    EvidenceRegistry registry = EvidenceRegistry.from(bundle);
                    List<McpResponsePolicy.CodeHit> hits = chunks.stream()
                            .limit(resolvedLimit)
                            .map(chunk -> policy.code(chunk, registry.evidenceId(chunk).orElse("")))
                            .toList();
                    return new McpToolResponse<>(scope(effectiveProject, null, null), hits,
                            policy.evidence(registry.references()), Map.of("status", "SUCCESS"), List.of(),
                            policy.truncated(rawLimit(limit), chunks.size(), registry.references())
                                    || chunks.stream().limit(resolvedLimit)
                                    .anyMatch(chunk -> policy.textTruncated(chunk.text())));
                });
    }

    /**
     * 从仓库相对路径读取一段受长度限制的源码摘录。
     * 路径会校验为仓库相对路径，结束行收敛到起始行后的 200 行内。
     *
     * @param context    MCP 同步请求上下文
     * @param filePath   仓库相对源码路径
     * @param projectId  项目 ID，null 时走默认项目
     * @param startLine  起始行（从 1 计），可为 null
     * @param endLine    结束行，上限 200 行，可为 null
     * @return 源码摘录 + 证据 + 截断标记
     */
    @McpTool(
            name = "nexus_get_source",
            description = "Read a bounded source excerpt from a repository-relative path.",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public McpToolResponse<SourceSnippet> getSource(
            McpSyncRequestContext context,
            @McpToolParam(description = "Repository-relative source path") String filePath,
            @McpToolParam(description = "Project ID; defaults to the configured project", required = false)
            String projectId,
            @McpToolParam(description = "First line, one-based", required = false) Integer startLine,
            @McpToolParam(description = "Last line, capped to 200 lines", required = false) Integer endLine) {
        return invocations.invoke("nexus_get_source", context, projectId, null, Permission.PUBLIC_READ,
                effectiveProject -> {
                    String safePath = policy.relativePath(policy.required(filePath, "filePath"));
                    int safeEnd = policy.endLine(startLine, endLine);
                    SourceSnippet source = dependency(() -> codeKnowledgeService.source(
                            effectiveProject, safePath, startLine, safeEnd));
                    SourceSnippet snippet = policy.source(source);
                        CodeChunk sourceChunk = new CodeChunk("source:" + safePath + ":" + snippet.startLine(),
                                effectiveProject, null, safePath, "source", safePath, snippet.startLine(),
                                snippet.endLine(), snippet.text(), null);
                        RetrievalBundle bundle = new RetrievalBundle(safePath, RetrievalProfile.DEVELOPMENT_PLAN,
                                effectiveProject, null, null, List.of(), List.of(sourceChunk));
                        EvidenceRegistry registry = EvidenceRegistry.from(bundle);
                    return new McpToolResponse<>(scope(effectiveProject, null, null), snippet,
                            policy.evidence(registry.references()), Map.of("status", "SUCCESS"), List.of(),
                            (endLine != null && endLine > safeEnd) || policy.textTruncated(source.text()));
                });
    }

    /**
     * 为需求与代码范围生成带证据引用的开发计划（需 OPERATE 权限）。
     *
     * @param context    MCP 同步请求上下文
     * @param query      开发任务或需求查询
     * @param projectId  项目 ID，null 时走默认项目
     * @param documentId 需求文档 ID，可为 null
     * @param version    需求版本，可为 null
     * @param limit      最大证据命中条数，1-20
     * @return 开发计划数据 + 证据引用 + 质量信息 + 截断标记
     */
    @McpTool(
            name = "nexus_development_plan",
            description = "Generate an evidence-cited development plan for a requirement and code scope.",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public McpToolResponse<Map<String, Object>> developmentPlan(
            McpSyncRequestContext context,
            @McpToolParam(description = "Development task or requirement query") String query,
            @McpToolParam(description = "Project ID; defaults to the configured project", required = false)
            String projectId,
            @McpToolParam(description = "Requirement document ID", required = false) String documentId,
            @McpToolParam(description = "Requirement version", required = false) String version,
            @McpToolParam(description = "Maximum evidence hits, 1-20", required = false) Integer limit) {
        return invocations.invoke("nexus_development_plan", context, projectId, version, Permission.OPERATE,
                effectiveProject -> {
                    DevelopmentPlanResponse plan = dependency(() -> developmentPlanService.plan(policy.required(query, "query"), documentId, version,
                            effectiveProject, policy.limit(limit)));
                    Map<String, Object> data = developmentPlanData(plan);
                    List<EvidenceRef> evidence = policy.evidence(plan.citations().references());
                    boolean truncated = policy.truncated(rawLimit(limit), plan.codeReferences().size(),
                            plan.citations().references()) || developmentPlanTruncated(plan);
                    return new McpToolResponse<>(scope(effectiveProject, plan.version(), plan.documentId()), data,
                            evidence, plan.citations().quality(), plan.warnings(), truncated);
                });
    }

    /**
     * 读取一篇已发布、按版本作用的 NEXUS Wiki 页面。
     *
     * @param context    MCP 同步请求上下文
     * @param version    已发布的 Wiki 版本
     * @param featureId  稳定的 Wiki 特性 ID
     * @param projectId  项目 ID，null 时走默认项目
     * @return 页面数据 + 受限证据列表 + 截断标记
     */
    @McpTool(
            name = "nexus_wiki_page",
            description = "Read a published, versioned NEXUS Wiki page.",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public McpToolResponse<Map<String, Object>> wikiPage(
            McpSyncRequestContext context,
            @McpToolParam(description = "Published Wiki version") String version,
            @McpToolParam(description = "Stable Wiki feature ID") String featureId,
            @McpToolParam(description = "Project ID; defaults to the configured project", required = false)
            String projectId) {
        return invocations.invoke("nexus_wiki_page", context, projectId, version, Permission.PUBLIC_READ,
                effectiveProject -> {
                    WikiModels.Page page = dependency(() -> wikiRepository.getPage(effectiveProject, policy.required(version, "version"),
                            policy.required(featureId, "featureId")));
                    List<McpResponsePolicy.WikiEvidence> evidence = page.evidence().stream()
                            .limit(40)
                            .map(policy::wikiEvidence)
                            .toList();
                    return new McpToolResponse<>(scope(effectiveProject, page.version(), null), wikiData(page),
                            evidence, page.quality(), List.of(), wikiTruncated(page));
                });
    }

    /**
     * 比较两个版本之间的需求、代码、测试与 Wiki 知识差异；
     * 起止版本必填且必须不同。
     *
     * @param context      MCP 同步请求上下文
     * @param fromVersion  基准版本
     * @param toVersion    目标版本
     * @param projectId    项目 ID，null 时走默认项目
     * @return 四类差异数据 + 起止版本信息 + 截断标记
     */
    @McpTool(
            name = "nexus_version_diff",
            description = "Compare requirement, code, test, and Wiki knowledge between two versions.",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public McpToolResponse<Map<String, Object>> versionDiff(
            McpSyncRequestContext context,
            @McpToolParam(description = "Base version") String fromVersion,
            @McpToolParam(description = "Target version") String toVersion,
            @McpToolParam(description = "Project ID; defaults to the configured project", required = false)
            String projectId) {
        return invocations.invoke("nexus_version_diff", context, projectId, toVersion, Permission.PUBLIC_READ,
                effectiveProject -> {
                    String safeFrom = policy.required(fromVersion, "fromVersion");
                    String safeTo = policy.required(toVersion, "toVersion");
                    policy.distinct(safeFrom, safeTo, "fromVersion and toVersion must differ");
                    VersionComparisonReport report = dependency(() -> versionComparisonService.compare(
                            effectiveProject, safeFrom, safeTo));
                    return new McpToolResponse<>(scope(report.projectId(), report.toVersion(), null),
                            versionDiffData(report),
                            List.of(), Map.of("fromVersion", report.fromVersion(), "toVersion", report.toVersion()),
                            report.warnings(), versionDiffTruncated(report));
                });
    }

    /**
     * 遍历最新项目/提交作用域下的静态符号调用图。
     *
     * @param context   MCP 同步请求上下文
     * @param symbol    限定名或简单符号名
     * @param projectId 项目 ID，null 时走默认项目
     * @param direction inbound 或 outbound，可为 null
     * @param depth     遍历深度，1-5，可为 null
     * @param limit     最大图关系数，1-200，可为 null
     * @return 代码图数据 + 可用性/警告 + 截断标记
     */
    @McpTool(
            name = "nexus_code_graph",
            description = "Traverse the latest project/commit-scoped static symbol call graph.",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public McpToolResponse<CodeIntelligenceResponse> codeGraph(
            McpSyncRequestContext context,
            @McpToolParam(description = "Qualified or simple symbol name") String symbol,
            @McpToolParam(description = "Project ID; defaults to the configured project", required = false)
            String projectId,
            @McpToolParam(description = "inbound or outbound", required = false) String direction,
            @McpToolParam(description = "Traversal depth, 1-5", required = false) Integer depth,
            @McpToolParam(description = "Maximum graph relations, 1-200", required = false) Integer limit) {
        return invocations.invoke("nexus_code_graph", context, projectId, null, Permission.PUBLIC_READ,
                effectiveProject -> {
                    CodeIntelligenceResponse data = codeIntelligenceService.graph(
                            effectiveProject, symbol, direction, depth, limit);
                    return new McpToolResponse<>(scope(effectiveProject, null, null), data, List.of(),
                            Map.of("availability", data.availability()), graphWarnings(data), data.truncated());
                });
    }

    /**
     * 分析单个符号或 Git 提交区间（fromCommit+toCommit）的入向影响；
     * 两种模式必须恰好选择一种。
     *
     * @param context     MCP 同步请求上下文
     * @param projectId   项目 ID，null 时走默认项目
     * @param symbol      符号选择器，与提交模式互斥
     * @param fromCommit  基准 Git 提交，可为 null
     * @param toCommit    目标 Git 提交，可为 null
     * @param depth       遍历深度，1-5，可为 null
     * @param limit       最大图关系数，1-200，可为 null
     * @return 影响分析数据 + 可用性/警告 + 截断标记
     */
    @McpTool(
            name = "nexus_impact_analysis",
            description = "Analyze inbound impact for one symbol or a Git commit range.",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public McpToolResponse<CodeIntelligenceResponse> impactAnalysis(
            McpSyncRequestContext context,
            @McpToolParam(description = "Project ID; defaults to the configured project", required = false)
            String projectId,
            @McpToolParam(description = "Symbol selector; exclusive with commits", required = false) String symbol,
            @McpToolParam(description = "Base Git commit", required = false) String fromCommit,
            @McpToolParam(description = "Target Git commit", required = false) String toCommit,
            @McpToolParam(description = "Traversal depth, 1-5", required = false) Integer depth,
            @McpToolParam(description = "Maximum graph relations, 1-200", required = false) Integer limit) {
        return invocations.invoke("nexus_impact_analysis", context, projectId, toCommit, Permission.PUBLIC_READ,
                effectiveProject -> {
                    boolean bySymbol = symbol != null && !symbol.isBlank();
                    boolean byCommits = fromCommit != null && !fromCommit.isBlank()
                            && toCommit != null && !toCommit.isBlank();
                    if (bySymbol == byCommits) {
                        throw new IllegalArgumentException(
                                "Select exactly one impact mode: symbol or fromCommit+toCommit");
                    }
                    CodeIntelligenceResponse data = bySymbol
                            ? codeIntelligenceService.impactSymbol(effectiveProject, symbol, depth, limit)
                            : codeIntelligenceService.impactCommits(
                            effectiveProject, fromCommit, toCommit, depth, limit);
                    return new McpToolResponse<>(scope(effectiveProject, toCommit, null), data, List.of(),
                            Map.of("availability", data.availability()), graphWarnings(data), data.truncated());
                });
    }

    /** 把代码图服务的警告收敛为统一 code-graph 域警告，最多 20 条。 */
    private List<RagWarning> graphWarnings(CodeIntelligenceResponse data) {
        return data.warnings().stream()
                .limit(20)
                .map(message -> new RagWarning("code-graph", "CODE_GRAPH_DEGRADED",
                        policy.bounded(message), 0))
                .toList();
    }

    /**
     * 基于版本作用域证据生成受长度限制的需求存疑清单（需 OPERATE 权限）。
     *
     * @param context    MCP 同步请求上下文
     * @param documentId 需求文档 ID
     * @param version    需求版本
     * @param module     可选模块过滤
     * @param projectId  项目 ID，null 时走默认项目
     * @return 存疑命中列表（限 50 条）+ 条数 + 截断标记
     */
    @McpTool(
            name = "nexus_review_doubts",
            description = "Generate a bounded requirement doubt list from version-scoped evidence.",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public McpToolResponse<List<McpResponsePolicy.DoubtHit>> reviewDoubts(
            McpSyncRequestContext context,
            @McpToolParam(description = "Requirement document ID") String documentId,
            @McpToolParam(description = "Requirement version") String version,
            @McpToolParam(description = "Optional module filter", required = false) String module,
            @McpToolParam(description = "Project ID; defaults to the configured project", required = false)
            String projectId) {
        return invocations.invoke("nexus_review_doubts", context, projectId, version, Permission.OPERATE,
                effectiveProject -> {
                    DoubtBatch raw = reviewFacadeService.review(
                            new ReviewRequest(documentId, version, module, effectiveProject));
                    List<McpResponsePolicy.DoubtHit> bounded = raw.doubts().stream().limit(50)
                            .map(policy::doubt).toList();
                    return new McpToolResponse<>(scope(effectiveProject, version, documentId), bounded, List.of(),
                            Map.of("count", bounded.size()), List.of(), raw.doubts().size() > bounded.size());
                });
    }

    /**
     * 对结构化需求/代码/测试/Wiki claims 做确定性冲突检查（需 OPERATE 权限）。
     *
     * @param context   MCP 同步请求上下文
     * @param version   所有 claims 必须归属的业务版本
     * @param claims    待比较的结构化证据 claims
     * @param projectId 项目 ID，null 时走默认项目
     * @return 冲突报告 + 状态/冲突数 + 规范化警告 + 截断标记
     */
    @McpTool(
            name = "nexus_conflict_check",
            description = "Deterministically check structured requirement, code, test, and Wiki claims for conflicts.",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public McpToolResponse<KnowledgeConflictReport> conflictCheck(
            McpSyncRequestContext context,
            @McpToolParam(description = "Business version that all claims must belong to") String version,
            @McpToolParam(description = "Structured evidence-backed claims to compare") List<KnowledgeClaim> claims,
            @McpToolParam(description = "Project ID; defaults to the configured project", required = false)
            String projectId) {
        return invocations.invoke("nexus_conflict_check", context, projectId, version, Permission.OPERATE,
                effectiveProject -> {
                    KnowledgeConflictReport report = knowledgeConflictService.analyze(
                            effectiveProject, version, claims);
                    List<RagWarning> warnings = report.warnings().stream().limit(20)
                            .map(message -> new RagWarning("knowledge.conflict", "CONFLICT_INPUT_NORMALIZED",
                                    policy.bounded(message), 0))
                            .toList();
                    return new McpToolResponse<>(scope(effectiveProject, version, null), report, List.of(),
                            Map.of("status", report.status(), "conflictCount", report.conflictCount()),
                            warnings, report.conflicts().size() > 50);
                });
    }

    /**
     * 执行底层依赖调用，把「预期内不可用」统一转换为 {@link McpDependencyUnavailableException}：
     * IO 异常、状态异常直接转换；5xx 的 HTTP 状态异常也视为依赖不可用，其余状态原样抛出。
     *
     * @param call 底层依赖调用
     * @param <T>  返回值类型
     * @return 依赖调用结果
     */
    private <T> T dependency(DependencyCall<T> call) {
        try {
            return call.get();
        }
        catch (IOException | IllegalStateException exception) {
            throw new McpDependencyUnavailableException(exception);
        }
        catch (ResponseStatusException exception) {
            if (exception.getStatusCode().is5xxServerError()) {
                throw new McpDependencyUnavailableException(exception);
            }
            throw exception;
        }
    }

    /** 底层依赖调用抽象：允许抛出 IO 异常。 */
    @FunctionalInterface
    private interface DependencyCall<T> {
        T get() throws IOException;
    }

    /** 把开发计划响应整理为对外 Map：各文本字段截断、分节限 20 条。 */
    private Map<String, Object> developmentPlanData(DevelopmentPlanResponse plan) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", plan.status());
        data.put("summary", policy.bounded(plan.summary()));
        data.put("productUnderstanding", bounded(plan.productUnderstanding()));
        data.put("developmentConstraints", bounded(plan.developmentConstraints()));
        data.put("chainOverview", bounded(plan.chainOverview()));
        data.put("implementationOrder", bounded(plan.implementationOrder()));
        data.put("steps", bounded(plan.steps()));
        data.put("risks", bounded(plan.risks()));
        data.put("sections", plan.sections().stream().limit(20)
                .map(section -> Map.of(
                        "title", policy.bounded(section.title()),
                        "purpose", policy.bounded(section.purpose()),
                        "keyQuestions", bounded(section.keyQuestions()),
                        "changeSuggestions", bounded(section.changeSuggestions())))
                .toList());
        data.put("conflictReport", plan.conflictReport());
        return Map.copyOf(data);
    }

    /** 开发计划是否因任一字段超长或分节超限（20 条）而被截断。 */
    private boolean developmentPlanTruncated(DevelopmentPlanResponse plan) {
        if (policy.textTruncated(plan.summary())
                || policy.textListTruncated(plan.productUnderstanding())
                || policy.textListTruncated(plan.developmentConstraints())
                || policy.textListTruncated(plan.chainOverview())
                || policy.textListTruncated(plan.implementationOrder())
                || policy.textListTruncated(plan.steps())
                || policy.textListTruncated(plan.risks())
                || policy.collectionTruncated(plan.sections())) {
            return true;
        }
        return plan.sections().stream().limit(20).anyMatch(section ->
                policy.textTruncated(section.title())
                        || policy.textTruncated(section.purpose())
                        || policy.textListTruncated(section.keyQuestions())
                        || policy.textListTruncated(section.changeSuggestions()));
    }

    /** 把版本比较报告整理为对外 Map：四类差异（需求/代码/测试/Wiki）各限 20 条变更，路径做相对化处理。 */
    private Map<String, Object> versionDiffData(VersionComparisonReport report) {
        Map<String, Object> requirements = Map.of(
                "availability", report.requirements().availability(),
                "added", report.requirements().added(),
                "modified", report.requirements().modified(),
                "removed", report.requirements().removed(),
                "changes", report.requirements().changes().stream().limit(20)
                        .map(change -> Map.of(
                                "type", change.type(),
                                "filename", policy.bounded(change.filename()),
                                "parentOrder", change.parentOrder(),
                                "beforeExcerpt", policy.bounded(change.beforeExcerpt()),
                                "afterExcerpt", policy.bounded(change.afterExcerpt())))
                        .toList());
        Map<String, Object> code = Map.of(
                "availability", report.code().availability(),
                "changedFiles", report.code().changedFiles(),
                "added", report.code().added(),
                "modified", report.code().modified(),
                "deleted", report.code().deleted(),
                "renamed", report.code().renamed(),
                "changes", report.code().changes().stream().limit(20)
                        .map(change -> Map.of(
                                "type", change.type(),
                                "oldPath", safeRelativePath(change.oldPath()),
                                "newPath", safeRelativePath(change.newPath())))
                        .toList());
        Map<String, Object> tests = Map.of(
                "availability", report.tests().availability(),
                "beforeStatus", String.valueOf(report.tests().beforeStatus()),
                "afterStatus", String.valueOf(report.tests().afterStatus()),
                "totalDelta", report.tests().totalDelta(),
                "passedDelta", report.tests().passedDelta(),
                "failedDelta", report.tests().failedDelta(),
                "skippedDelta", report.tests().skippedDelta(),
                "cases", report.tests().cases().stream().limit(20)
                        .map(change -> Map.of(
                                "type", change.type(),
                                "caseId", policy.bounded(change.caseId()),
                                "name", policy.bounded(change.name()),
                                "beforeStatus", String.valueOf(change.beforeStatus()),
                                "afterStatus", String.valueOf(change.afterStatus())))
                        .toList());
        Map<String, Object> wiki = Map.of(
                "availability", report.wiki().availability(),
                "added", report.wiki().added(),
                "modified", report.wiki().modified(),
                "removed", report.wiki().removed(),
                "pages", report.wiki().pages().stream().limit(20)
                        .map(change -> Map.of(
                                "type", change.type(),
                                "featureId", policy.bounded(change.featureId()),
                                "title", policy.bounded(change.title()),
                                "beforeStatus", String.valueOf(change.beforeStatus()),
                                "afterStatus", String.valueOf(change.afterStatus()),
                                "evidenceDelta", change.evidenceDelta(),
                                "summaryChanged", change.summaryChanged()))
                        .toList());
        return Map.of(
                "generatedAt", report.generatedAt(),
                "requirements", requirements,
                "code", code,
                "tests", tests,
                "wiki", wiki);
    }

    /** 版本比较结果是否因任一变更列表超 20 条或字段超长而被截断。 */
    private boolean versionDiffTruncated(VersionComparisonReport report) {
        return report.requirements().changes().size() > 20
                || report.code().changes().size() > 20
                || report.tests().cases().size() > 20
                || report.wiki().pages().size() > 20
                || report.requirements().changes().stream().limit(20).anyMatch(change ->
                policy.textTruncated(change.filename())
                        || policy.textTruncated(change.beforeExcerpt())
                        || policy.textTruncated(change.afterExcerpt()))
                || report.tests().cases().stream().limit(20).anyMatch(change ->
                policy.textTruncated(change.caseId()) || policy.textTruncated(change.name()))
                || report.wiki().pages().stream().limit(20).anyMatch(change ->
                policy.textTruncated(change.featureId()) || policy.textTruncated(change.title()));
    }

    /** 尽力把路径转为仓库相对路径；失败（如空串、URI 或越界路径）时返回空串。 */
    private String safeRelativePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            return policy.relativePath(path);
        }
        catch (IllegalArgumentException exception) {
            return "";
        }
    }

    /** 把 Wiki 页面整理为对外 Map：各文本列表截断（限 20 条）、代码条目与关系受限。 */
    private Map<String, Object> wikiData(WikiModels.Page page) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("featureId", page.featureId());
        data.put("title", policy.bounded(page.title()));
        data.put("status", page.status());
        data.put("summary", policy.bounded(page.summary()));
        data.put("productRules", bounded(page.productRules()));
        data.put("processSteps", bounded(page.processSteps()));
        data.put("dataImpacts", bounded(page.dataImpacts()));
        data.put("boundaryConditions", bounded(page.boundaryConditions()));
        data.put("acceptanceCriteria", bounded(page.acceptanceCriteria()));
        data.put("testPoints", bounded(page.testPoints()));
        data.put("risks", bounded(page.risks()));
        data.put("codeEntries", page.codeEntries().stream().limit(20).map(policy::wikiCodeEntry).toList());
        data.put("relations", page.relations().stream().limit(20).toList());
        return Map.copyOf(data);
    }

    /** Wiki 页面是否因证据超 40 条、任一字段超长或列表超限而被截断。 */
    private boolean wikiTruncated(WikiModels.Page page) {
        return page.evidence().size() > 40
                || policy.textTruncated(page.title())
                || policy.textTruncated(page.summary())
                || policy.textListTruncated(page.productRules())
                || policy.textListTruncated(page.processSteps())
                || policy.textListTruncated(page.dataImpacts())
                || policy.textListTruncated(page.boundaryConditions())
                || policy.textListTruncated(page.acceptanceCriteria())
                || policy.textListTruncated(page.testPoints())
                || policy.textListTruncated(page.risks())
                || policy.collectionTruncated(page.codeEntries())
                || page.codeEntries().stream().limit(20)
                .anyMatch(entry -> policy.textTruncated(entry.role()))
                || policy.collectionTruncated(page.relations())
                || page.evidence().stream().limit(40).anyMatch(evidence ->
                policy.textTruncated(evidence.title())
                        || policy.textTruncated(evidence.location())
                        || policy.textTruncated(evidence.excerpt()));
    }

    /** 字符串列表统一截断：最多 20 条，每条经 {@code policy} 截断；null 视为空列表。 */
    private List<String> bounded(List<String> values) {
        return values == null ? List.of() : values.stream().limit(20).map(policy::bounded).toList();
    }

    private int rawLimit(Integer limit) {
        return limit == null ? 10 : limit;
    }

    private McpToolResponse.ResolvedScope scope(String projectId, String version, String documentId) {
        return new McpToolResponse.ResolvedScope(projectId, version, documentId);
    }
}
