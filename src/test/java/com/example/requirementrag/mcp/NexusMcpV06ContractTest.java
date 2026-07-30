package com.example.requirementrag.mcp;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.code.GitDiffService;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.DevelopmentPlanResponse;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.model.SourceSnippet;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.model.UserRole;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalPipeline;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import com.example.requirementrag.security.ProjectAuthorizationService;
import com.example.requirementrag.security.UnauthenticatedException;
import com.example.requirementrag.service.DevelopmentPlanService;
import com.example.requirementrag.versioning.VersionComparisonService;
import com.example.requirementrag.versioning.VersionModels;
import com.example.requirementrag.web.AccessDeniedException;
import com.example.requirementrag.wiki.WikiModels;
import com.example.requirementrag.wiki.WikiRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Executable NEXUS 0.6 contract matrix.  Each enum-driven test is one explicit
 * six-tool row across the input, permission, degradation, or truncation column.
 */
class NexusMcpV06ContractTest {
    enum Tool { REQUIREMENTS, CODE, SOURCE, PLAN, WIKI, DIFF }

    private static final String PROJECT = "project-a";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RetrievalPipeline retrieval;
    private CodeKnowledgeService code;
    private DevelopmentPlanService plans;
    private WikiRepository wiki;
    private VersionComparisonService versions;
    private NexusMcpTools tools;

    private void setUp() {
        retrieval = mock(RetrievalPipeline.class);
        code = mock(CodeKnowledgeService.class);
        plans = mock(DevelopmentPlanService.class);
        wiki = mock(WikiRepository.class);
        versions = mock(VersionComparisonService.class);
        McpResponsePolicy policy = new McpResponsePolicy(new McpProperties(true, 20, 200, 2_000, 40, 200_000), JSON);
        tools = new NexusMcpTools(retrieval, code, plans, wiki, versions, policy,
                new McpToolInvocationService(new ProjectAuthorizationService(mock(ProjectRegistry.class)),
                        new SimpleMeterRegistry(), policy));
    }

    @ParameterizedTest(name = "{0}: invalid request is rejected before downstream invocation")
    @EnumSource(Tool.class)
    void inputValidationContract(Tool tool) {
        setUp();
        assertThrows(IllegalArgumentException.class, () -> invalid(tool));
        verifyNoInteractions(retrieval, code, plans, wiki, versions);
    }

    @ParameterizedTest(name = "{0}: unauthenticated request is rejected before downstream invocation")
    @EnumSource(Tool.class)
    void authenticationContract(Tool tool) {
        setUp();
        assertThrows(UnauthenticatedException.class, () -> invoke(tool, null));
        verifyNoInteractions(retrieval, code, plans, wiki, versions);
    }

    @ParameterizedTest(name = "{0}: project allow-list is enforced before downstream invocation")
    @EnumSource(Tool.class)
    void projectAllowListContract(Tool tool) {
        setUp();
        assertThrows(AccessDeniedException.class, () -> invoke(tool, context(UserRole.DEVELOPER, "other-project")));
        verifyNoInteractions(retrieval, code, plans, wiki, versions);
    }

    @ParameterizedTest(name = "{0}: expected dependency failure returns a safe degraded response")
    @EnumSource(Tool.class)
    void degradationContract(Tool tool) throws Exception {
        setUp();
        failDependency(tool, new IllegalStateException("private dependency detail at http://qdrant.internal:6333"));

        McpToolResponse<?> response = invoke(tool, context(UserRole.DEVELOPER, PROJECT));

        assertNull(response.data());
        assertEquals("NEXUS_" + toolName(tool) + "_UNAVAILABLE", response.warnings().getFirst().code());
        assertFalse(response.truncated());
        String serialized = JSON.writeValueAsString(response);
        assertFalse(serialized.contains("private dependency detail"));
        assertFalse(serialized.contains("qdrant.internal"));
    }

    @ParameterizedTest(name = "{0}: response data is bounded and marked truncated")
    @EnumSource(Tool.class)
    void truncationContract(Tool tool) throws Exception {
        setUp();
        stubOversized(tool);

        McpToolResponse<?> response = invokeForTruncation(tool);

        assertTrue(response.truncated());
        assertTruncatedPayload(tool, response);
    }

    @ParameterizedTest(name = "{0}: a single mapped-field reduction is never silent")
    @EnumSource(Tool.class)
    void silentTruncationContract(Tool tool) throws Exception {
        setUp();
        stubSingleFieldTruncation(tool);

        McpToolResponse<?> response = invoke(tool, context(UserRole.DEVELOPER, PROJECT));

        assertTrue(response.truncated());
        assertSingleFieldWasBounded(tool, response);
    }

    @ParameterizedTest(name = "{0}: PUBLIC_READ tools permit READONLY")
    @EnumSource(value = Tool.class, names = {"REQUIREMENTS", "CODE", "SOURCE", "WIKI", "DIFF"})
    void publicReadPermissionContract(Tool tool) throws Exception {
        setUp();
        stubSuccess(tool);

        assertDoesNotThrow(() -> invoke(tool, context(UserRole.READONLY, PROJECT)));
        verifyDependencyCalled(tool);
    }

    @Test
    void developmentPlanRequiresOperate() {
        setUp();
        assertThrows(AccessDeniedException.class, () -> invoke(Tool.PLAN, context(UserRole.READONLY, PROJECT)));
        verifyNoInteractions(retrieval, code, plans, wiki, versions);
    }

    @Test
    void developmentPlanAllowsOperateRole() throws Exception {
        setUp();
        stubSuccess(Tool.PLAN);

        assertDoesNotThrow(() -> invoke(Tool.PLAN, context(UserRole.DEVELOPER, PROJECT)));
        verify(plans).plan(any(), any(), any(), eq(PROJECT), anyInt());
    }

    @ParameterizedTest(name = "{0}: caller error is not converted into degradation")
    @EnumSource(Tool.class)
    void callerErrorIsNotDegraded(Tool tool) throws Exception {
        setUp();
        failDependency(tool, new IllegalArgumentException("caller bug"));

        assertThrows(IllegalArgumentException.class, () -> invoke(tool, context(UserRole.DEVELOPER, PROJECT)));
    }

    @Test
    void wikiNotFoundIsNotDegraded() {
        setUp();
        when(wiki.getPage(any(), any(), any())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "not found"));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> invoke(Tool.WIKI, context(UserRole.DEVELOPER, PROJECT)));
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }

    @Test
    void wikiServerFailureIsDegraded() {
        setUp();
        when(wiki.getPage(any(), any(), any())).thenThrow(new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, "private wiki storage detail"));

        McpToolResponse<?> response = invoke(Tool.WIKI, context(UserRole.DEVELOPER, PROJECT));
        assertNull(response.data());
        assertEquals("NEXUS_WIKI_PAGE_UNAVAILABLE", response.warnings().getFirst().code());
        assertFalse(JSON.writeValueAsString(response).contains("private wiki storage detail"));
    }

    @Test
    void sourceRangeValidationAndCapContract() throws Exception {
        setUp();
        McpSyncRequestContext context = context(UserRole.DEVELOPER, PROJECT);
        assertThrows(IllegalArgumentException.class, () -> tools.getSource(context, "src/Main.java", PROJECT, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> tools.getSource(context, "src/Main.java", PROJECT, 3, 2));

        when(code.source(eq(PROJECT), eq("src/Main.java"), eq(1), eq(200)))
                .thenReturn(new SourceSnippet("src/Main.java", 1, 200, "line\n".repeat(600)));
        McpToolResponse<SourceSnippet> response = tools.getSource(context, "src/Main.java", PROJECT, 1, 500);
        assertTrue(response.truncated());
        assertEquals(200, response.data().endLine());
        assertTrue(response.data().text().length() <= 2_001);
    }

    private void invalid(Tool tool) {
        McpSyncRequestContext context = context(UserRole.DEVELOPER, PROJECT);
        switch (tool) {
            case REQUIREMENTS -> tools.searchRequirements(context, " ", PROJECT, null, null, 10);
            case CODE -> tools.searchCode(context, " ", PROJECT, 10);
            case SOURCE -> tools.getSource(context, "../secret", PROJECT, 1, 2);
            case PLAN -> tools.developmentPlan(context, " ", PROJECT, null, null, 10);
            case WIKI -> tools.wikiPage(context, " ", "feature", PROJECT);
            case DIFF -> tools.versionDiff(context, "5.1", "5.1", PROJECT);
        }
    }

    private McpToolResponse<?> invoke(Tool tool, McpSyncRequestContext context) {
        return switch (tool) {
            case REQUIREMENTS -> tools.searchRequirements(context, "query", PROJECT, "doc-a", "5.1", 10);
            case CODE -> tools.searchCode(context, "query", PROJECT, 10);
            case SOURCE -> tools.getSource(context, "src/Main.java", PROJECT, 1, 2);
            case PLAN -> tools.developmentPlan(context, "query", PROJECT, "doc-a", "5.1", 10);
            case WIKI -> tools.wikiPage(context, "5.1", "feature-a", PROJECT);
            case DIFF -> tools.versionDiff(context, "5.0", "5.1", PROJECT);
        };
    }

    private McpToolResponse<?> invokeForTruncation(Tool tool) {
        McpSyncRequestContext context = context(UserRole.DEVELOPER, PROJECT);
        return switch (tool) {
            case REQUIREMENTS -> tools.searchRequirements(context, "query", PROJECT, "doc-a", "5.1", 20);
            case CODE -> tools.searchCode(context, "query", PROJECT, 20);
            case SOURCE -> tools.getSource(context, "src/Main.java", PROJECT, 1, 500);
            case PLAN -> tools.developmentPlan(context, "query", PROJECT, "doc-a", "5.1", 20);
            case WIKI -> tools.wikiPage(context, "5.1", "feature-a", PROJECT);
            case DIFF -> tools.versionDiff(context, "5.0", "5.1", PROJECT);
        };
    }

    private void assertTruncatedPayload(Tool tool, McpToolResponse<?> response) {
        switch (tool) {
            case REQUIREMENTS, CODE -> assertEquals(20, ((List<?>) response.data()).size());
            case SOURCE -> {
                SourceSnippet snippet = (SourceSnippet) response.data();
                assertEquals(200, snippet.endLine());
                assertTrue(snippet.text().length() <= 2_001);
            }
            case PLAN -> {
                Map<?, ?> data = (Map<?, ?>) response.data();
                assertEquals(20, ((List<?>) data.get("productUnderstanding")).size());
                assertEquals(20, ((List<?>) data.get("sections")).size());
            }
            case WIKI -> {
                Map<?, ?> data = (Map<?, ?>) response.data();
                assertEquals(20, ((List<?>) data.get("productRules")).size());
                assertEquals(20, ((List<?>) data.get("codeEntries")).size());
                assertEquals(20, ((List<?>) data.get("relations")).size());
                assertEquals(40, response.evidence().size());
            }
            case DIFF -> {
                Map<?, ?> data = (Map<?, ?>) response.data();
                assertEquals(20, ((List<?>) ((Map<?, ?>) data.get("requirements")).get("changes")).size());
                assertEquals(20, ((List<?>) ((Map<?, ?>) data.get("code")).get("changes")).size());
                assertEquals(20, ((List<?>) ((Map<?, ?>) data.get("tests")).get("cases")).size());
                assertEquals(20, ((List<?>) ((Map<?, ?>) data.get("wiki")).get("pages")).size());
            }
        }
    }

    private void assertSingleFieldWasBounded(Tool tool, McpToolResponse<?> response) {
        switch (tool) {
            case REQUIREMENTS -> assertTrue(((McpResponsePolicy.RequirementHit)
                    ((List<?>) response.data()).getFirst()).excerpt().length() <= 2_001);
            case CODE -> assertTrue(((McpResponsePolicy.CodeHit)
                    ((List<?>) response.data()).getFirst()).excerpt().length() <= 2_001);
            case SOURCE -> assertTrue(((SourceSnippet) response.data()).text().length() <= 2_001);
            case PLAN -> assertTrue(((String) ((Map<?, ?>) response.data()).get("summary")).length() <= 2_001);
            case WIKI -> assertEquals(20, ((List<?>) ((Map<?, ?>) response.data()).get("productRules")).size());
            case DIFF -> {
                Map<?, ?> requirements = (Map<?, ?>) ((Map<?, ?>) response.data()).get("requirements");
                Map<?, ?> change = (Map<?, ?>) ((List<?>) requirements.get("changes")).getFirst();
                assertTrue(((String) change.get("beforeExcerpt")).length() <= 2_001);
            }
        }
    }

    private void stubSuccess(Tool tool) throws Exception {
        switch (tool) {
            case REQUIREMENTS -> when(retrieval.execute(any())).thenReturn(requirementOutcome(1));
            case CODE -> when(code.search(any(), any(), anyInt())).thenReturn(codeChunks(1));
            case SOURCE -> when(code.source(any(), any(), any(), any())).thenReturn(new SourceSnippet("src/Main.java", 1, 2, "ok"));
            case PLAN -> when(plans.plan(any(), any(), any(), any(), anyInt())).thenReturn(plan(1));
            case WIKI -> when(wiki.getPage(any(), any(), any())).thenReturn(page(1, 1, 1, 1));
            case DIFF -> when(versions.compare(any(), any(), any())).thenReturn(report(1));
        }
    }

    private void stubOversized(Tool tool) throws Exception {
        switch (tool) {
            case REQUIREMENTS -> when(retrieval.execute(any())).thenReturn(requirementOutcome(25));
            case CODE -> when(code.search(any(), any(), anyInt())).thenReturn(codeChunks(25));
            case SOURCE -> when(code.source(eq(PROJECT), eq("src/Main.java"), eq(1), eq(200)))
                    .thenReturn(new SourceSnippet("src/Main.java", 1, 200, "line\n".repeat(600)));
            case PLAN -> when(plans.plan(any(), any(), any(), any(), anyInt())).thenReturn(plan(25));
            case WIKI -> when(wiki.getPage(any(), any(), any())).thenReturn(page(25, 25, 25, 45));
            case DIFF -> when(versions.compare(any(), any(), any())).thenReturn(report(25));
        }
    }

    private void stubSingleFieldTruncation(Tool tool) throws Exception {
        String oversized = "x".repeat(2_001);
        switch (tool) {
            case REQUIREMENTS -> when(retrieval.execute(any())).thenReturn(requirementOutcome(List.of(
                    new ChunkRecord("req-0", "doc-a", "5.1", "docs/spec.md", "parent-0", oversized,
                            "requirement", "hash", 0, 0))));
            case CODE -> when(code.search(any(), any(), anyInt())).thenReturn(List.of(
                    new CodeChunk("code-0", PROJECT, "sha", "src/Feature.java", "method", "run",
                            1, 2, oversized, "hash", "java")));
            case SOURCE -> when(code.source(eq(PROJECT), eq("src/Main.java"), eq(1), eq(2)))
                    .thenReturn(new SourceSnippet("src/Main.java", 1, 2, oversized));
            case PLAN -> when(plans.plan(any(), any(), any(), any(), anyInt()))
                    .thenReturn(planWithSummary(oversized));
            case WIKI -> when(wiki.getPage(any(), any(), any())).thenReturn(page(21, 1, 1, 1));
            case DIFF -> when(versions.compare(any(), any(), any())).thenReturn(reportWithExcerpt(oversized));
        }
    }

    private void failDependency(Tool tool, RuntimeException failure) throws Exception {
        switch (tool) {
            case REQUIREMENTS -> when(retrieval.execute(any())).thenThrow(failure);
            case CODE -> when(code.search(any(), any(), anyInt())).thenThrow(failure);
            case SOURCE -> when(code.source(any(), any(), any(), any())).thenThrow(failure);
            case PLAN -> when(plans.plan(any(), any(), any(), any(), anyInt())).thenThrow(failure);
            case WIKI -> when(wiki.getPage(any(), any(), any())).thenThrow(failure);
            case DIFF -> when(versions.compare(any(), any(), any())).thenThrow(failure);
        }
    }

    private void verifyDependencyCalled(Tool tool) throws Exception {
        switch (tool) {
            case REQUIREMENTS -> verify(retrieval).execute(any());
            case CODE -> verify(code).search(any(), eq(PROJECT), anyInt());
            case SOURCE -> verify(code).source(eq(PROJECT), any(), any(), any());
            case PLAN -> verify(plans).plan(any(), any(), any(), eq(PROJECT), anyInt());
            case WIKI -> verify(wiki).getPage(eq(PROJECT), any(), any());
            case DIFF -> verify(versions).compare(eq(PROJECT), any(), any());
        }
    }

    private RagOutcome<RetrievalBundle> requirementOutcome(int size) {
        return requirementOutcome(chunks(size));
    }

    private RagOutcome<RetrievalBundle> requirementOutcome(List<ChunkRecord> requirements) {
        return new RagOutcome<>(RagOutcomeStatus.SUCCESS,
                new RetrievalBundle("query", RetrievalProfile.REQUIREMENT_REVIEW, PROJECT, "doc-a", "5.1",
                        requirements, List.of()), List.of(), List.of());
    }

    private List<ChunkRecord> chunks(int size) {
        return IntStream.range(0, size).mapToObj(index -> new ChunkRecord("req-" + index, "doc-a", "5.1",
                "docs/spec-" + index + ".md", "parent-" + index, "parent text", "requirement " + index,
                "hash", index, 0)).toList();
    }

    private List<CodeChunk> codeChunks(int size) {
        return IntStream.range(0, size).mapToObj(index -> codeChunk(index)).toList();
    }

    private CodeChunk codeChunk(int index) {
        return new CodeChunk("code-" + index, PROJECT, "sha", "src/Feature" + index + ".java", "method",
                "run" + index, 1, 2, "code " + index, "hash", "java");
    }

    private DevelopmentPlanResponse plan(int size) {
        List<String> values = IntStream.range(0, size).mapToObj(index -> "item-" + index).toList();
        List<DevelopmentPlanResponse.PlanSection> sections = IntStream.range(0, size)
                .mapToObj(index -> new DevelopmentPlanResponse.PlanSection("section-" + index, "purpose",
                        List.of(codeChunk(index)), values, values)).toList();
        return new DevelopmentPlanResponse("query", "doc-a", "5.1", "summary", values, values, null,
                values, sections, values, values, values, List.of(), codeChunks(size), RagOutcomeStatus.SUCCESS,
                List.of(), List.of(), null);
    }

    private DevelopmentPlanResponse planWithSummary(String summary) {
        List<String> values = List.of("item");
        List<DevelopmentPlanResponse.PlanSection> sections = List.of(
                new DevelopmentPlanResponse.PlanSection("section", "purpose", List.of(codeChunk(0)), values, values));
        return new DevelopmentPlanResponse("query", "doc-a", "5.1", summary, values, values, null,
                values, sections, values, values, values, List.of(), codeChunks(1), RagOutcomeStatus.SUCCESS,
                List.of(), List.of(), null);
    }

    private WikiModels.Page page(int listSize, int codeEntries, int relations, int evidence) {
        List<String> values = IntStream.range(0, listSize).mapToObj(index -> "value-" + index).toList();
        List<WikiModels.CodeEntry> entries = IntStream.range(0, codeEntries)
                .mapToObj(index -> new WikiModels.CodeEntry("role", "src/Feature" + index + ".java", "run", "sha", "ADDED", "VERIFIED"))
                .toList();
        List<WikiModels.Relation> relationList = IntStream.range(0, relations)
                .mapToObj(index -> new WikiModels.Relation("feature-" + index, "depends-on", "label", "description"))
                .toList();
        List<WikiModels.Evidence> evidenceList = IntStream.range(0, evidence)
                .mapToObj(index -> new WikiModels.Evidence("requirement", "title", "docs/spec.md", "5.1",
                        "line " + index, "excerpt", "sha", "docs/spec.md", "symbol", "VERIFIED"))
                .toList();
        return new WikiModels.Page(PROJECT, "Project", "5.1", "5.1", "base", "sha", "now", "feature-a",
                "Feature", "category", "5.1", WikiModels.Status.FULLY_VERIFIED, List.of(), "summary", List.of(),
                values, values, entries, values, values, values, values, values, null, null,
                new WikiModels.KnowledgeQuality("VERIFIED", evidence, codeEntries, true, List.of()), values,
                relationList, evidenceList, "wiki/feature-a.md");
    }

    private VersionModels.VersionComparisonReport report(int size) {
        List<VersionModels.RequirementChange> requirements = IntStream.range(0, size)
                .mapToObj(index -> new VersionModels.RequirementChange(VersionModels.ChangeType.ADDED, "spec.md",
                        "parent", index, null, "hash", "before", "after")).toList();
        List<GitDiffService.GitFileChange> codeChanges = IntStream.range(0, size)
                .mapToObj(index -> new GitDiffService.GitFileChange(GitDiffService.ChangeType.ADDED, null,
                        "src/Feature" + index + ".java")).toList();
        List<VersionModels.TestCaseChange> cases = IntStream.range(0, size)
                .mapToObj(index -> new VersionModels.TestCaseChange(VersionModels.ChangeType.ADDED, "case-" + index,
                        "case", null, VersionModels.TestCaseStatus.PASSED)).toList();
        List<VersionModels.WikiPageChange> pages = IntStream.range(0, size)
                .mapToObj(index -> new VersionModels.WikiPageChange(VersionModels.ChangeType.ADDED, "feature-" + index,
                        "Feature", null, WikiModels.Status.FULLY_VERIFIED, 1, true)).toList();
        return new VersionModels.VersionComparisonReport(PROJECT, "5.0", "5.1", "now",
                new VersionModels.RequirementDiff(VersionModels.Availability.AVAILABLE, size, 0, 0, requirements),
                new GitDiffService.GitDiffResult(GitDiffService.Availability.AVAILABLE, size, size, 0, 0, 0,
                        size, 0, 0, codeChanges),
                new VersionModels.TestDiff(VersionModels.Availability.AVAILABLE, VersionModels.TestRunStatus.PASSED,
                        VersionModels.TestRunStatus.PASSED, size, size, 0, 0, cases),
                new VersionModels.WikiDiff(VersionModels.Availability.AVAILABLE, size, 0, 0, pages), List.of());
    }

    private VersionModels.VersionComparisonReport reportWithExcerpt(String excerpt) {
        List<VersionModels.RequirementChange> requirements = List.of(
                new VersionModels.RequirementChange(VersionModels.ChangeType.MODIFIED, "spec.md", "parent",
                        0, "before-hash", "after-hash", excerpt, "after"));
        return new VersionModels.VersionComparisonReport(PROJECT, "5.0", "5.1", "now",
                new VersionModels.RequirementDiff(VersionModels.Availability.AVAILABLE, 0, 1, 0, requirements),
                new GitDiffService.GitDiffResult(GitDiffService.Availability.AVAILABLE, 0, 0, 0, 0, 0,
                        0, 0, 0, List.of()),
                new VersionModels.TestDiff(VersionModels.Availability.AVAILABLE,
                        VersionModels.TestRunStatus.PASSED, VersionModels.TestRunStatus.PASSED,
                        0, 0, 0, 0, List.of()),
                new VersionModels.WikiDiff(VersionModels.Availability.AVAILABLE, 0, 0, 0, List.of()), List.of());
    }

    private String toolName(Tool tool) {
        return switch (tool) {
            case REQUIREMENTS -> "SEARCH_REQUIREMENTS";
            case CODE -> "SEARCH_CODE";
            case SOURCE -> "GET_SOURCE";
            case PLAN -> "DEVELOPMENT_PLAN";
            case WIKI -> "WIKI_PAGE";
            case DIFF -> "VERSION_DIFF";
        };
    }

    private McpSyncRequestContext context(UserRole role, String... projects) {
        McpSyncRequestContext context = mock(McpSyncRequestContext.class);
        when(context.transportContext()).thenReturn(McpTransportContext.create(Map.of(
                McpTransportConfiguration.USER_CONTEXT_KEY, new UserContext("actor", role, List.of(projects)))));
        return context;
    }
}
