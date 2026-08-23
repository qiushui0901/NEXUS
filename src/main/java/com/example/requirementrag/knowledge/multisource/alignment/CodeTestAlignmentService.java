package com.example.requirementrag.knowledge.multisource.alignment;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.TestCaseClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.TestResultClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeStore;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.AlignmentRelation;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.BuildResult;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.CodeSymbolView;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DriftItem;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DriftType;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.LoadedCode;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.MatchMethod;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.TruthRole;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.VersionContext;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 代码—测试图谱（Phase 3）：测试用例 → 代码测试符号 → 被测符号的三段映射。
 *
 * <p>确定性规则把业务测试用例与代码测试符号按 testMethod / testCaseId / title 规范化匹配，
 * 建立 VERIFIES；测试结果按 testCaseId 建立 CONFIRMS；FAILED 观测生成 TEST_DRIFT 结论。
 */
@Service
public class CodeTestAlignmentService {

    /** 包含匹配仅在索引小时启用，避免大代码库上的全量扫描。 */
    private static final int CONTAINS_MATCH_MAX_INDEX_SIZE = 2000;

    private final MultiSourceKnowledgeStore knowledgeStore;
    private final CodeCentricAlignmentStore alignmentStore;
    private final CodeSymbolLoader codeSymbolLoader;
    private final VersionContextService versionContextService;

    public CodeTestAlignmentService(MultiSourceKnowledgeStore knowledgeStore,
                                    CodeCentricAlignmentStore alignmentStore,
                                    CodeSymbolLoader codeSymbolLoader,
                                    VersionContextService versionContextService) {
        this.knowledgeStore = knowledgeStore;
        this.alignmentStore = alignmentStore;
        this.codeSymbolLoader = codeSymbolLoader;
        this.versionContextService = versionContextService;
    }

    /** 构建代码—测试对齐关系（幂等重建 Phase 3 的关系，按 VersionContext 隔离）。 */
    public BuildResult build(String projectId, String version, String environment) {
        VersionContext context = versionContextService.resolve(projectId, version, environment);
        LoadedCode loaded = codeSymbolLoader.load(projectId);
        List<TestCaseClaim> testCases = knowledgeStore.findTestCases(projectId, version);
        List<TestResultClaim> testResults = knowledgeStore.findTestResults(projectId, version);

        alignmentStore.deleteAlignmentRelationsByType(projectId, version, context.contextId(), "VERIFIES");
        alignmentStore.deleteAlignmentRelationsByType(projectId, version, context.contextId(), "CONFIRMS");
        alignmentStore.deleteAlignmentRelationsByType(projectId, version, context.contextId(), "TEST_DRIFT");
        alignmentStore.deleteDriftItemsByType(projectId, version, context.contextId(), "TEST_DRIFT");

        Map<String, List<CodeSymbolView>> testSymbols = new HashMap<>();
        Map<String, List<CodeSymbolView>> allSymbols = new HashMap<>();
        if (loaded.commitSha() != null) {
            for (CodeSymbolView symbol : loaded.symbols()) {
                allSymbols.computeIfAbsent(AlignmentNaming.normalize(symbol.simpleName()),
                        ignored -> new ArrayList<>()).add(symbol);
                if (symbol.testSymbol()) {
                    testSymbols.computeIfAbsent(AlignmentNaming.normalize(symbol.simpleName()),
                            ignored -> new ArrayList<>()).add(symbol);
                }
            }
        }

        int relations = 0;
        int drifts = 0;
        Map<String, TestCaseClaim> byTestCaseId = new HashMap<>();
        Map<String, List<String>> testCaseToCode = new HashMap<>();
        Set<String> seen = new HashSet<>();
        List<AlignmentRelation> relationBatch = new ArrayList<>();
        List<DriftItem> driftBatch = new ArrayList<>();

        for (TestCaseClaim testCase : testCases) {
            byTestCaseId.put(testCase.testCaseId(), testCase);
            List<CodeSymbolView> matches = matchTestCase(testCase, testSymbols, allSymbols);
            if (matches.isEmpty()) continue;
            List<String> codeIds = new ArrayList<>();
            for (CodeSymbolView symbol : matches) {
                String matchMethod = exactMatch(testCase, symbol)
                        ? MatchMethod.TEST_SYMBOL_EXACT.name() : MatchMethod.HEURISTIC.name();
                String relationId = relationId(projectId, version, context.contextId(),
                        testCase.claimId(), symbol.id(), "VERIFIES");
                if (seen.add(relationId)) {
                    relationBatch.add(new AlignmentRelation(
                            relationId, projectId, version, context.contextId(),
                            testCase.claimId(), null, "TEST_CASE",
                            null, symbol.id(), "CODE", "VERIFIES",
                            matchMethod, "RULE_CONFIRMED", 0.85,
                            evidenceId(testCase.claimId(), testCase.evidenceLocation()),
                            context.contextId(), context.contextId(),
                            "测试用例[" + testCase.testCaseId() + "] 验证代码符号 " + symbol.simpleName()
                                    + " (" + symbol.filePath() + ":" + symbol.startLine() + "-" + symbol.endLine() + ")",
                            null, null));
                    relations++;
                }
                codeIds.add(symbol.id());
            }
            testCaseToCode.put(testCase.testCaseId(), codeIds);
        }

        for (TestResultClaim result : testResults) {
            TestCaseClaim testCase = byTestCaseId.get(result.testCaseId());
            if (testCase == null) continue;
            String confirmId = relationId(projectId, version, context.contextId(),
                    result.claimId(), testCase.claimId(), "CONFIRMS");
            if (seen.add(confirmId)) {
                relationBatch.add(new AlignmentRelation(
                        confirmId, projectId, version, context.contextId(),
                        result.claimId(), null, "TEST_RESULT",
                        testCase.claimId(), null, "TEST_CASE", "CONFIRMS",
                        MatchMethod.TEST_CASE_ID_EXACT.name(), "RULE_CONFIRMED", 1.0,
                        evidenceId(result.claimId(), result.evidenceLocation()),
                        context.contextId(), context.contextId(),
                        "测试结果[" + result.testRunId() + "]=" + result.executionStatus()
                                + " 确认测试用例 " + testCase.testCaseId(),
                        null, null));
                relations++;
            }
            if ("FAILED".equalsIgnoreCase(result.executionStatus())) {
                List<String> codeIds = testCaseToCode.getOrDefault(result.testCaseId(), List.of());
                String driftId = driftId(projectId, version, context.contextId(), result.claimId(), "TEST_DRIFT");
                if (codeIds.isEmpty()) {
                    driftBatch.add(new DriftItem(
                            driftId, projectId, version, context.contextId(),
                            "UNKNOWN", "test:" + AlignmentNaming.keySegment(testCase.module()),
                            DriftType.TEST_DRIFT.name(), "ERROR", TruthRole.OBSERVATION.name(),
                            result.claimId(), testCase.claimId(), result.executionStatus(),
                            testCase.expectedResult(),
                            "测试用例[" + testCase.testCaseId() + "] 最近结果为 FAILED，未映射到代码符号",
                            "OPEN", null, Instant.now().toString()));
                } else {
                    for (String codeId : codeIds) {
                        driftBatch.add(new DriftItem(
                                driftId + ":" + codeId, projectId, version, context.contextId(),
                                "UNKNOWN", "test:" + AlignmentNaming.keySegment(testCase.module()),
                                DriftType.TEST_DRIFT.name(), "ERROR", TruthRole.OBSERVATION.name(),
                                result.claimId(), testCase.claimId(), result.executionStatus(),
                                testCase.expectedResult(),
                                "测试用例[" + testCase.testCaseId() + "] 最近结果为 FAILED，关联代码 " + codeId,
                                "OPEN", null, Instant.now().toString()));
                    }
                }
                drifts++;
            }
        }
        alignmentStore.saveAlignmentRelations(relationBatch);
        alignmentStore.saveDriftItems(driftBatch);
        return new BuildResult(0, 0, 0, relations, drifts);
    }

    /** 查询指定环境下代码—测试对齐关系。 */
    public List<AlignmentRelation> relations(String projectId, String version, String environment,
                                             String relationType) {
        VersionContext context = versionContextService.resolve(projectId, version, environment);
        return alignmentStore.findAlignmentRelations(projectId, version, context.contextId(), relationType);
    }

    private List<CodeSymbolView> matchTestCase(TestCaseClaim testCase, Map<String, List<CodeSymbolView>> testSymbols,
                                               Map<String, List<CodeSymbolView>> allSymbols) {
        List<String> candidates = new ArrayList<>();
        if (testCase.testMethod() != null && !testCase.testMethod().isBlank()) {
            candidates.add(testCase.testMethod());
        }
        if (testCase.testCaseId() != null && !testCase.testCaseId().isBlank()) {
            candidates.add(testCase.testCaseId());
        }
        if (testCase.title() != null && !testCase.title().isBlank()) {
            candidates.add(testCase.title());
        }
        for (String candidate : candidates) {
            List<CodeSymbolView> exact = testSymbols.get(AlignmentNaming.normalize(candidate));
            if (exact != null && !exact.isEmpty()) return cap(exact);
        }
        for (String candidate : candidates) {
            List<CodeSymbolView> exact = allSymbols.get(AlignmentNaming.normalize(candidate));
            if (exact != null && !exact.isEmpty()) return cap(exact);
        }
        for (String candidate : candidates) {
            if (testSymbols.size() > CONTAINS_MATCH_MAX_INDEX_SIZE) break;
            for (Map.Entry<String, List<CodeSymbolView>> entry : testSymbols.entrySet()) {
                if (AlignmentNaming.namesRelated(entry.getKey(), candidate)) {
                    return cap(entry.getValue());
                }
            }
        }
        return List.of();
    }

    private boolean exactMatch(TestCaseClaim testCase, CodeSymbolView symbol) {
        String symbolName = AlignmentNaming.normalize(symbol.simpleName());
        return symbolName.equals(AlignmentNaming.normalize(testCase.testMethod()))
                || symbolName.equals(AlignmentNaming.normalize(testCase.testCaseId()))
                || symbolName.equals(AlignmentNaming.normalize(testCase.title()));
    }

    private List<CodeSymbolView> cap(List<CodeSymbolView> symbols) {
        return symbols.size() > 3 ? symbols.subList(0, 3) : symbols;
    }

    private String evidenceId(String claimId, String fallback) {
        List<String> evidence = knowledgeStore.findEvidenceIdsByClaimId(claimId);
        return evidence.isEmpty() ? fallback : evidence.get(0);
    }

    private String relationId(String projectId, String version, String versionContextId,
                                  String sourceClaimId, String targetExternalId, String type) {
        return "ar:" + sha256(projectId + "|" + version + "|" + versionContextId + "|" + sourceClaimId
                + "|" + targetExternalId + "|" + type).substring(0, 32);
    }

    private String driftId(String projectId, String version, String versionContextId,
                           String sourceClaimId, String type) {
        return "di:" + sha256(projectId + "|" + version + "|" + versionContextId + "|" + sourceClaimId
                + "|" + type).substring(0, 32);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}