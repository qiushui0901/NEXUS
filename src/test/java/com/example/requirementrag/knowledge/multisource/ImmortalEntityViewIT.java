package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.knowledge.multisource.alignment.BusinessConceptService;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricAlignmentStore;
import com.example.requirementrag.knowledge.multisource.alignment.CodeSymbolLoader;
import com.example.requirementrag.knowledge.multisource.alignment.VersionContextService;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntitySearchResponse;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceAggregator;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionProperties;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionValidator;
import com.example.requirementrag.knowledge.multisource.entity.EntityFactPriorityService;
import com.example.requirementrag.knowledge.multisource.entity.EntityLlmAssistant;
import com.example.requirementrag.knowledge.multisource.entity.EntityQueryService;
import com.example.requirementrag.knowledge.multisource.entity.EntityQueryService.EntitySearchRequest;
import com.example.requirementrag.knowledge.multisource.entity.EntityResolverService;
import com.example.requirementrag.knowledge.multisource.entity.QuestionEntityAnalyzer;
import com.example.requirementrag.code.SQLiteSymbolGraphStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.List;

/**
 * 本地真实链路入口：导入 → 发布 → 实体构建 → 实体查询，输出按当前代码形成的真实数据。
 *
 * <p>只在显式开启系统属性时才运行：{@code -Dimmortal.import=true}（与 {@code ImmortalImportIT} 同门控）。
 */
@EnabledIfSystemProperty(named = "immortal.import", matches = "true")
class ImmortalEntityViewIT {

    @Test
    void runsRealPipelineAndPrintsEntitySearch() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String db = "data/multi-source-knowledge.db";
        MultiSourceKnowledgeStore store = new MultiSourceKnowledgeStore(db, objectMapper);
        CodeCentricAlignmentStore alignmentStore = new CodeCentricAlignmentStore(db);
        CodeSymbolLoader codeLoader = new CodeSymbolLoader(
                new SQLiteSymbolGraphStore("data/code-graph"));

        // 1. 当前导入过程（内容哈希幂等，未变化文件跳过）
        ImmortalKnowledgeImporter importer = new ImmortalKnowledgeImporter(store);
        Path root = Path.of(System.getProperty("immortal.root", "/Users/user/Documents/immortal"));
        var summary = importer.importAll("immortal", "5.1", root);
        System.out.println("[1-import] " + summary);

        // 2. 当前发布过程（所有 5.1 文档版本 → PUBLISHED + active manifest）
        List<String> versionIds = store.findDocumentVersionIds("immortal", "5.1");
        int published = 0;
        for (String dvId : versionIds) {
            store.publishDocumentVersion("immortal", "5.1", dvId);
            published++;
        }
        System.out.println("[2-publish] 文档版本 " + versionIds.size() + " 个，全部发布：" + published
                + "；已发布版本=" + store.findPublishedBusinessVersions("immortal"));

        // 3. 当前实体构建过程（只处理已发布版本，跨版本合并，代码只挂最新）
        BusinessConceptService conceptService = new BusinessConceptService(
                store, alignmentStore, codeLoader,
                new VersionContextService(alignmentStore, codeLoader));
        var buildResult = conceptService.buildProject("immortal");
        System.out.println("[3-buildProject] " + buildResult);

        // 4. 当前实体查询过程（规则解析 → 全版本聚合 → 事实评估；LLM 关闭走规则）
        EntityExtractionProperties properties = new EntityExtractionProperties(
                true, null, 8, 50_000, 200, 50, 100, 100, 0.7, false, 1);
        EntityExtractionValidator validator = new EntityExtractionValidator(properties);
        EntityLlmAssistant llm = new EntityLlmAssistant(null, null, properties, validator);
        EntityQueryService queryService = new EntityQueryService(
                new QuestionEntityAnalyzer(alignmentStore, properties, llm),
                new EntityResolverService(alignmentStore, properties, llm),
                new EntityEvidenceAggregator(store, alignmentStore, codeLoader),
                new EntityFactPriorityService());

        for (String query : List.of("等级上限是多少？", "攻击力是多少？", "传播时间是多少？", "击杀奖励")) {
            EntitySearchResponse response = queryService.search(new EntitySearchRequest(
                    "immortal", query, null, null, true, true, true, 20));
            System.out.println("[4-entity-search] query=" + query
                    + " intent=" + response.plan().intent()
                    + " mentions=" + response.plan().mentions().size()
                    + " entities=" + response.entities().size()
                    + " citations=" + response.citations().size()
                    + " warnings=" + response.warnings());
            for (var mention : response.plan().mentions()) {
                System.out.println("    mention text=" + mention.text() + " entityId=" + mention.entityId()
                        + " status=" + mention.status()
                        + " members=" + (mention.entityId() == null ? -1
                            : alignmentStore.findMembers("immortal", mention.entityId(), null).size()));
            }
            for (var view : response.entities()) {
                System.out.println("    entity=" + view.canonicalName() + " id=" + view.entityId()
                        + " code=" + view.currentFacts().code().size()
                        + " params=" + view.currentFacts().parameterTables().size()
                        + " testResults=" + view.currentFacts().testResults().size()
                        + " timeline=" + view.timeline().stream()
                            .map(b -> b.businessVersion() + "(req" + b.requirements().size()
                                    + "/param" + b.parameterTables().size() + "/test" + b.tests().size() + ")")
                            .toList()
                        + " conflicts=" + view.conflicts().size());
                for (var ref : view.currentFacts().parameterTables()) {
                    System.out.println("      paramRef claim=" + ref.claimId() + " subject=" + ref.subject()
                            + " value=" + ref.objectValue() + ref.unit());
                }
            }
        }
    }
}
