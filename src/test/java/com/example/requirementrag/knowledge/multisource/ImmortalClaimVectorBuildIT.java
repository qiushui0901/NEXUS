package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntityRecallResponse;
import com.example.requirementrag.knowledge.multisource.entity.EntityQueryService.EntitySearchRequest;
import com.example.requirementrag.knowledge.multisource.entity.EntityRecallService;
import com.example.requirementrag.knowledge.multisource.entity.RecallMode;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorBuildService;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.ClaimVectorGenerationManifest;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.GenerationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * 本地真实链路：构建 ALL_PUBLISHED Claim 向量代际（真实网关 text-embedding-v4 嵌入 + 本地 Qdrant），
 * 再验证图/向量增强实体检索的向量补召回在真实数据上生效。
 *
 * <p>只在显式开启系统属性且环境就绪时运行：
 * {@code -Dimmortal.vector=true}；依赖 Qdrant（tools/qdrant-start.sh）与 .env 的 OPENAI_API_KEY/OPENAI_BASE_URL。
 */
@SpringBootTest(properties = {
        "logging.structured.format.console=",
        "management.tracing.sampling.probability=0",
        "app.rag.knowledge.bootstrap-enabled=false",
        "app.rag.gitlab.enabled=false",
        "app.rag.auth.enabled=false",
        "app.rag.multi-source.claim-vector.enabled=true",
        "app.rag.multi-source.claim-vector.build-enabled=true",
        "app.rag.multi-source.claim-vector.candidate-retrieval-enabled=true",
        "app.rag.multi-source.claim-vector.build-scope=ALL_PUBLISHED"
})
@EnabledIfSystemProperty(named = "immortal.vector", matches = "true")
class ImmortalClaimVectorBuildIT {

    @Autowired
    private EmbeddingModel embeddingModel;
    @Autowired
    private KnowledgeClaimVectorBuildService buildService;
    @Autowired
    private EntityRecallService recallService;

    @Test
    void embeddingGatewayProbe() {
        List<float[]> vectors = embeddingModel.embed(List.of("攻击力是多少", "等级上限是多少"));
        for (float[] vector : vectors) {
            if (vector == null || vector.length == 0) {
                throw new IllegalStateException("网关嵌入返回空向量");
            }
        }
        System.out.println("[probe] text-embedding-v4 dims=" + vectors.get(0).length
                + " 网关连通 OK (" + embeddingModel.getClass().getSimpleName() + ")");
    }

    @Test
    void buildsAllPublishedClaimsAndGraphVectorSearches() {
        long started = System.currentTimeMillis();
        ClaimVectorGenerationManifest manifest = buildService.build("immortal", "5.1", "ALL_PUBLISHED");
        long elapsedSec = (System.currentTimeMillis() - started) / 1000;
        System.out.println("[build] generationId=" + manifest.generationId()
                + " status=" + manifest.status()
                + " buildScope=" + manifest.buildScope()
                + " expected=" + manifest.expectedPointCount()
                + " indexed=" + manifest.indexedPointCount()
                + " collection=" + manifest.physicalCollection()
                + " elapsedSec=" + elapsedSec);
        if (manifest.status() != GenerationStatus.ACTIVE) {
            throw new IllegalStateException("代际未 ACTIVE: " + manifest.status());
        }

        // 图/向量增强检索：确定性实体 + 局部图 + 向量补召回
        for (String query : List.of("攻击力是多少", "等级上限是多少", "传播时间是多少")) {
            EntityRecallResponse recall = recallService.search(new EntitySearchRequest(
                    "immortal", query, null, null, true, true, true, 20), RecallMode.GRAPH_VECTOR);
            System.out.println("[graph-vector] query=" + query
                    + " recallMode=" + recall.recallMode()
                    + " mentions=" + recall.plan().mentions().size()
                    + " entities=" + recall.entities().size()
                    + " graphLinks=" + recall.graph().links().size()
                    + " relatedEntityCount=" + recall.relatedEntityCount()
                    + " vectorHits=" + recall.vectorHits().size()
                    + " warnings=" + recall.warnings());
            for (var hit : recall.vectorHits().stream().limit(8).toList()) {
                System.out.println("    vectorHit claimId=" + hit.claimId()
                        + " subject=" + hit.subject() + " source=" + hit.sourceType());
            }
        }
    }
}