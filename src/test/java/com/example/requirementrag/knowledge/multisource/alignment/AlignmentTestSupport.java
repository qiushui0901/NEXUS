package com.example.requirementrag.knowledge.multisource.alignment;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeDocument;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeDocumentVersion;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.TestCaseClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.TestResultClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeStore;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.CodeSymbolView;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.LoadedCode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 对齐子系统测试支撑：共用同一 SQLite 文件的知识库 + 对齐库，播种数据与桩代码加载器。 */
final class AlignmentTestSupport {
    private AlignmentTestSupport() {
    }

    record Stores(MultiSourceKnowledgeStore multiSource, CodeCentricAlignmentStore alignment) {
    }

    static Stores stores(Path dir) {
        ObjectMapper mapper = new ObjectMapper();
        MultiSourceKnowledgeStore multiSource =
                new MultiSourceKnowledgeStore(dir.resolve("knowledge.db").toString(), mapper);
        CodeCentricAlignmentStore alignment =
                new CodeCentricAlignmentStore(dir.resolve("knowledge.db").toString());
        return new Stores(multiSource, alignment);
    }

    /** 播种参数/存疑/测试用例/测试结果与需求声明（含统一 Claim 同步）。 */
    static void seed(Stores stores, String projectId, String version,
                     List<ParameterClaim> parameters, List<DoubtClaim> doubts,
                     List<TestCaseClaim> testCases, List<TestResultClaim> testResults,
                     List<KnowledgeClaimRecord> requirements) {
        MultiSourceKnowledgeStore ms = stores.multiSource();
        ms.replaceSnapshot(projectId, version, parameters, doubts, testCases, testResults);
        String docId = "doc-" + projectId;
        ms.registerDocument(new KnowledgeDocument(docId, projectId, SourceType.REQUIREMENT,
                "alignment-sample", "alignment-sample", "file://alignment", Authority.PRIMARY, null));
        String dvId = "dv-" + projectId + "-" + version;
        ms.upsertDocumentVersion(new KnowledgeDocumentVersion(dvId, docId, projectId, version,
                "hash-" + version, "v1", "v1", null, "DRAFT", null, null));
        ms.syncSnapshotClaims(projectId, version, dvId, Map.of());
        for (KnowledgeClaimRecord requirement : requirements) {
            ms.saveClaim(requirement);
        }
    }

    /** 构造需求统一 Claim（subject=需求名，object=需求值）。 */
    static KnowledgeClaimRecord requirement(String projectId, String version, String claimId,
                                            String subject, String value) {
        String dvId = "dv-" + projectId + "-" + version;
        String factKey = projectId + "|" + version + "|" + "" + "|" + subject.toLowerCase() + "|rule";
        return new KnowledgeClaimRecord(
                claimId, projectId, dvId, SourceType.REQUIREMENT, Authority.PRIMARY,
                factKey, subject, "rule", value, "TEXT", null,
                "VERIFIED", null, null, null, "RULE", null, null, null);
    }

    /** 构造代码符号视图。 */
    static CodeSymbolView symbol(String id, String kind, String qualifiedName, String simpleName,
                                 String filePath, int start, int end, boolean testSymbol) {
        return new CodeSymbolView(id, "immortal-game-service", "abc123", kind, qualifiedName,
                simpleName, filePath, start, end, false, testSymbol);
    }

    static LoadedCode loadedCode(List<CodeSymbolView> symbols) {
        return new LoadedCode("immortal-game-service", "abc123", symbols);
    }

    /** 桩代码加载器：绕过真实代码符号图。 */
    static CodeSymbolLoader stubLoader(LoadedCode code) {
        return new CodeSymbolLoader(null) {
            @Override
            public LoadedCode load(String projectId) {
                return code;
            }

            @Override
            public String codeProjectId(String projectId) {
                return code == null ? projectId : code.codeProjectId();
            }
        };
    }
}