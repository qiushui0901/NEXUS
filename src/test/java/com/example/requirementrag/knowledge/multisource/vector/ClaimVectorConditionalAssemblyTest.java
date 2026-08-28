package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeStore;
import com.example.requirementrag.knowledge.multisource.SourceFilterStrategy;
import com.example.requirementrag.retrieval.EmbeddingBatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 条件 Bean 组合装配测试（高：Review 7/10a——旧实现 enabled=true + shadow-query-enabled=false
 * 因 QualityGate 强制注入非条件 Bean 的 ShadowEvaluator 导致启动失败；且 enabled 不是总开关）。
 * <p>用 ApplicationContextRunner 验证三件事：</p>
 * <ol>
 *   <li>{@code enabled=true + shadow-query-enabled=false} 时 QualityGate 可装配（shadowEvaluator 为
 *       {@code @Nullable} 可选注入），BuildService/Adapter 装配，ShadowEvaluator 不装配。</li>
 *   <li>{@code enabled=true + shadow-query-enabled=true} 时 ShadowEvaluator 装配。</li>
 *   <li>{@code enabled=false} 时所有 Claim 向量组件（含 QualityGate/Controller 依赖）均不装配。</li>
 * </ol>
 */
class ClaimVectorConditionalAssemblyTest {

    private KnowledgeClaimVectorProperties props(boolean enabled, boolean shadow) {
        return new KnowledgeClaimVectorProperties(
                enabled, true, true, shadow,
                "knowledge_claims_live", "knowledge-claim-vector-v1", "knowledge-claim-text-v1",
                200, 3, 32, 3, 2, "data/conditional-test.db", "ACTIVE_DOC");
    }

    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final MultiSourceKnowledgeStore msStore =
            new MultiSourceKnowledgeStore("data/conditional-ms.db", new ObjectMapper());

    @Configuration
    @Import({
            SQLiteKnowledgeClaimVectorStore.class,
            KnowledgeClaimVectorQdrantStore.class,
            KnowledgeClaimVectorTextComposer.class,
            ClaimVectorQualityGate.class,
            ClaimVectorShadowEvaluator.class,
            KnowledgeClaimVectorBuildService.class,
            ClaimVectorCandidateAdapter.class,
            SourceFilterStrategy.class
    })
    static class ClaimVectorComponents {}

    private ApplicationContextRunner runner(boolean enabled, boolean shadow) {
        return new ApplicationContextRunner()
                .withPropertyValues(
                        "app.rag.multi-source.claim-vector.enabled=" + enabled,
                        "app.rag.multi-source.claim-vector.build-enabled=true",
                        "app.rag.multi-source.claim-vector.candidate-retrieval-enabled=true",
                        "app.rag.multi-source.claim-vector.shadow-query-enabled=" + shadow)
                .withBean(KnowledgeClaimVectorProperties.class, () -> props(enabled, shadow))
                .withBean(EmbeddingModel.class, () -> embeddingModel)
                .withBean(EmbeddingBatcher.class, () -> new EmbeddingBatcher(embeddingModel))
                .withBean(MultiSourceKnowledgeStore.class, () -> msStore)
                .withBean(RestClient.class, () -> RestClient.builder().build())
                .withUserConfiguration(ClaimVectorComponents.class);
    }

    /** 高（Review 7）：构建阶段 enabled=true + shadow=false 不再因缺 ShadowEvaluator 而启动失败。 */
    @Test
    void enabledWithoutShadowQueryStillAssemblesQualityGate() {
        runner(true, false).run(context -> {
            assertThat(context).hasNotFailed();
            // 质量门存在且可用（shadowEvaluator 可选注入为 null）
            assertThat(context).hasSingleBean(ClaimVectorQualityGate.class);
            // 影子评估器未装配（shadow-query-enabled=false）
            assertThat(context).doesNotHaveBean(ClaimVectorShadowEvaluator.class);
            // 构建服务与候选适配器按各自开关装配（Review 10a：enabled 参与条件）
            assertThat(context).hasSingleBean(KnowledgeClaimVectorBuildService.class);
            assertThat(context).hasSingleBean(ClaimVectorCandidateAdapter.class);
            // 质量门在影子缺失时可执行检查（不抛 Bean 缺失异常）
            ClaimVectorQualityGate gate = context.getBean(ClaimVectorQualityGate.class);
            ClaimVectorQualityGate.QualityGateReport report = gate.check("proj-x", "v1");
            assertThat(report.readyToPublish()).isFalse(); // 无活跃代际
        });
    }

    /** shadow-query-enabled=true 时影子评估器装配。 */
    @Test
    void shadowQueryEnabledAssemblesShadowEvaluator() {
        runner(true, true).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ClaimVectorShadowEvaluator.class);
        });
    }

    /** 高（Review 10a）：enabled=false 时所有 Claim 向量组件不装配（enabled 是真正总开关）。 */
    @Test
    void disabledDoesNotAssembleAnyClaimVectorComponent() {
        runner(false, true).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ClaimVectorQualityGate.class);
            assertThat(context).doesNotHaveBean(KnowledgeClaimVectorBuildService.class);
            assertThat(context).doesNotHaveBean(ClaimVectorCandidateAdapter.class);
            assertThat(context).doesNotHaveBean(ClaimVectorShadowEvaluator.class);
        });
    }
}