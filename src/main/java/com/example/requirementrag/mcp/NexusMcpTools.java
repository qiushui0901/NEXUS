package com.example.requirementrag.mcp;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.evidence.EvidenceRef;
import com.example.requirementrag.evidence.EvidenceRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.DevelopmentPlanResponse;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.SourceSnippet;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalPipeline;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import com.example.requirementrag.service.DevelopmentPlanService;
import com.example.requirementrag.versioning.VersionComparisonService;
import com.example.requirementrag.versioning.VersionModels.VersionComparisonReport;
import com.example.requirementrag.wiki.WikiModels;
import com.example.requirementrag.wiki.WikiRepository;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only MCP facade over the existing NEXUS domain services. */
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

    public NexusMcpTools(RetrievalPipeline retrievalPipeline, CodeKnowledgeService codeKnowledgeService,
                         DevelopmentPlanService developmentPlanService, WikiRepository wikiRepository,
                         VersionComparisonService versionComparisonService, McpResponsePolicy policy,
                         McpToolInvocationService invocations) {
        this.retrievalPipeline = retrievalPipeline;
        this.codeKnowledgeService = codeKnowledgeService;
        this.developmentPlanService = developmentPlanService;
        this.wikiRepository = wikiRepository;
        this.versionComparisonService = versionComparisonService;
        this.policy = policy;
        this.invocations = invocations;
    }

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
                    RagOutcome<RetrievalBundle> outcome = retrievalPipeline.execute(new RetrievalRequest(
                            query, RetrievalProfile.REQUIREMENT_REVIEW, effectiveProject, documentId, version,
                            resolvedLimit));
                    RetrievalBundle bundle = outcome.data();
                    EvidenceRegistry registry = EvidenceRegistry.from(bundle);
                    List<McpResponsePolicy.RequirementHit> hits = bundle.requirementEvidence().stream()
                            .limit(resolvedLimit)
                            .map(chunk -> policy.requirement(chunk, registry.evidenceId(chunk).orElse("")))
                            .toList();
                    List<EvidenceRef> evidence = policy.evidence(registry.references());
                    boolean truncated = policy.truncated(rawLimit(limit), bundle.requirementEvidence().size(),
                            registry.references());
                    return new McpToolResponse<>(scope(bundle.resolvedProjectId(), bundle.version(),
                            bundle.documentId()), hits, evidence, Map.of("status", outcome.status()),
                            outcome.warnings(), truncated);
                });
    }

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
                    List<CodeChunk> chunks = codeKnowledgeService.search(query, effectiveProject, resolvedLimit);
                    RetrievalBundle bundle = new RetrievalBundle(query, RetrievalProfile.DEVELOPMENT_PLAN,
                            effectiveProject, null, null, List.of(), chunks);
                    EvidenceRegistry registry = EvidenceRegistry.from(bundle);
                    List<McpResponsePolicy.CodeHit> hits = chunks.stream()
                            .limit(resolvedLimit)
                            .map(chunk -> policy.code(chunk, registry.evidenceId(chunk).orElse("")))
                            .toList();
                    return new McpToolResponse<>(scope(effectiveProject, null, null), hits,
                            policy.evidence(registry.references()), Map.of("status", "SUCCESS"), List.of(),
                            policy.truncated(rawLimit(limit), chunks.size(), registry.references()));
                });
    }

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
                    String safePath = policy.relativePath(filePath);
                    int safeEnd = policy.endLine(startLine, endLine);
                    try {
                        SourceSnippet snippet = policy.source(codeKnowledgeService.source(
                                effectiveProject, safePath, startLine, safeEnd));
                        CodeChunk sourceChunk = new CodeChunk("source:" + safePath + ":" + snippet.startLine(),
                                effectiveProject, null, safePath, "source", safePath, snippet.startLine(),
                                snippet.endLine(), snippet.text(), null);
                        RetrievalBundle bundle = new RetrievalBundle(safePath, RetrievalProfile.DEVELOPMENT_PLAN,
                                effectiveProject, null, null, List.of(), List.of(sourceChunk));
                        EvidenceRegistry registry = EvidenceRegistry.from(bundle);
                        return new McpToolResponse<>(scope(effectiveProject, null, null), snippet,
                                policy.evidence(registry.references()), Map.of("status", "SUCCESS"), List.of(),
                                endLine != null && endLine > safeEnd);
                    }
                    catch (IOException exception) {
                        throw new IllegalStateException("Source is not available");
                    }
                });
    }

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
                    DevelopmentPlanResponse plan = developmentPlanService.plan(query, documentId, version,
                            effectiveProject, policy.limit(limit));
                    Map<String, Object> data = developmentPlanData(plan);
                    List<EvidenceRef> evidence = policy.evidence(plan.citations().references());
                    boolean truncated = policy.truncated(rawLimit(limit), plan.codeReferences().size(),
                            plan.citations().references());
                    return new McpToolResponse<>(scope(effectiveProject, plan.version(), plan.documentId()), data,
                            evidence, plan.citations().quality(), plan.warnings(), truncated);
                });
    }

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
                    WikiModels.Page page = wikiRepository.getPage(effectiveProject, version, featureId);
                    List<McpResponsePolicy.WikiEvidence> evidence = page.evidence().stream()
                            .limit(40)
                            .map(policy::wikiEvidence)
                            .toList();
                    return new McpToolResponse<>(scope(effectiveProject, page.version(), null), wikiData(page),
                            evidence, page.quality(), List.of(), page.evidence().size() > evidence.size());
                });
    }

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
                    VersionComparisonReport report = versionComparisonService.compare(
                            effectiveProject, fromVersion, toVersion);
                    return new McpToolResponse<>(scope(report.projectId(), report.toVersion(), null),
                            versionDiffData(report),
                            List.of(), Map.of("fromVersion", report.fromVersion(), "toVersion", report.toVersion()),
                            report.warnings(), versionDiffTruncated(report));
                });
    }

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

    private boolean versionDiffTruncated(VersionComparisonReport report) {
        return report.requirements().changes().size() > 20
                || report.code().changes().size() > 20
                || report.tests().cases().size() > 20
                || report.wiki().pages().size() > 20;
    }

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
