package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.KnowledgeClaimVectorPoint;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Claim 向量 Qdrant 发布器测试：验证点结构（payload + 确定性 ID + dense-only vector）。
 * 不连接真实 Qdrant——只测试 {@link KnowledgeClaimVectorQdrantStore#buildPointMap}。
 */
class KnowledgeClaimVectorQdrantStoreTest {

    private final KnowledgeClaimVectorProperties properties = new KnowledgeClaimVectorProperties(
            true, true, true, true,
            "knowledge_claims_live", "knowledge-claim-vector-v1", "knowledge-claim-text-v1",
            200, 3, 32, 3, 2, null);
    private final KnowledgeClaimVectorQdrantStore store =
            new KnowledgeClaimVectorQdrantStore(RestClient.builder().build(), properties);

    @Test
    void buildPointMapProducesDeterministicIdAndCorrectPayload() {
        KnowledgeClaimVectorPoint point = new KnowledgeClaimVectorPoint(
                "proj-1", "v1.0", "claim-001", "doc-ver-1",
                SourceType.REQUIREMENT, Authority.PRIMARY,
                "ACTIVE", "authn#login#requirement",
                "系统需要支持密码登录", "必须支持", null, null, List.of("ev-1", "ev-2"),
                "cv-gen-1", "knowledge-claim-vector-v1",
                "MockEmbeddingModel:1024", "abc123hash");

        float[] vector = {0.1f, 0.2f, 0.3f};
        Map<String, Object> pointMap = store.buildPointMap(point, vector);

        // 确定性 ID
        String expectedId = KnowledgeClaimVectorModels.deterministicPointId(
                "proj-1", "v1.0", "claim-001", "knowledge-claim-vector-v1");
        assertThat(pointMap.get("id")).isEqualTo(expectedId);

        // dense-only vector（无 sparse）
        @SuppressWarnings("unchecked")
        Map<String, Object> vectorMap = (Map<String, Object>) pointMap.get("vector");
        assertThat(vectorMap).containsOnlyKeys("dense");
        assertThat(vectorMap.get("dense")).isSameAs(vector);

        // payload 字段
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) pointMap.get("payload");
        assertThat(payload.get("projectId")).isEqualTo("proj-1");
        assertThat(payload.get("businessVersion")).isEqualTo("v1.0");
        assertThat(payload.get("claimId")).isEqualTo("claim-001");
        assertThat(payload.get("sourceType")).isEqualTo("REQUIREMENT");
        assertThat(payload.get("authority")).isEqualTo("PRIMARY");
        assertThat(payload.get("knowledgeStatus")).isEqualTo("ACTIVE");
        assertThat(payload.get("factKey")).isEqualTo("authn#login#requirement");
        assertThat(payload.get("subject")).isEqualTo("系统需要支持密码登录");
        assertThat(payload.get("projectionGenerationId")).isEqualTo("cv-gen-1");
        assertThat(payload.get("projectionSchemaVersion")).isEqualTo("knowledge-claim-vector-v1");
        assertThat(payload.get("embeddingModel")).isEqualTo("MockEmbeddingModel:1024");
        assertThat(payload.get("textHash")).isEqualTo("abc123hash");
        @SuppressWarnings("unchecked")
        List<String> evidenceIds = (List<String>) payload.get("evidenceIds");
        assertThat(evidenceIds).containsExactly("ev-1", "ev-2");
    }

    @Test
    void buildPointMapDifferentSchemaVersionProducesDifferentId() {
        KnowledgeClaimVectorPoint point = new KnowledgeClaimVectorPoint(
                "proj-1", "v1.0", "claim-001", "doc-ver-1",
                SourceType.REQUIREMENT, Authority.PRIMARY,
                "ACTIVE", "fk", "subj", "pred", null, null, List.of(),
                "gen-1", "knowledge-claim-vector-v1",
                "model:1024", "hash");

        Map<String, Object> pointV1 = store.buildPointMap(point, new float[]{0.1f});
        KnowledgeClaimVectorPoint pointV2 = new KnowledgeClaimVectorPoint(
                "proj-1", "v1.0", "claim-001", "doc-ver-1",
                SourceType.REQUIREMENT, Authority.PRIMARY,
                "ACTIVE", "fk", "subj", "pred", null, null, List.of(),
                "gen-1", "knowledge-claim-vector-v2",
                "model:1024", "hash");
        Map<String, Object> pointV2Map = store.buildPointMap(pointV2, new float[]{0.1f});

        assertThat(pointV1.get("id")).isNotEqualTo(pointV2Map.get("id"));
    }

    @Test
    void buildPointMapNullOptionalFieldsRenderedAsEmptyString() {
        KnowledgeClaimVectorPoint point = new KnowledgeClaimVectorPoint(
                "proj-2", "v2.0", "claim-002", "doc-ver-2",
                SourceType.PARAMETER_TABLE, Authority.PRIMARY,
                null, "param#timeout", "超时时间", null, "INTEGER", "秒", List.of(),
                "gen-2", "knowledge-claim-vector-v1",
                "model:1024", "hash2");

        Map<String, Object> pointMap = store.buildPointMap(point, new float[]{0.5f});
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) pointMap.get("payload");
        assertThat(payload.get("knowledgeStatus")).isEqualTo("");
        assertThat(payload.get("predicate")).isEqualTo("");
        assertThat(payload.get("sourceType")).isEqualTo("PARAMETER_TABLE");
        assertThat(payload.get("valueType")).isEqualTo("INTEGER");
        assertThat(payload.get("unit")).isEqualTo("秒");
    }

    // ── search 边界 ────────────────────────────────────────────────────

    @Test
    void searchWithBlankAliasThrows() {
        assertThatThrownBy(() -> store.search("", new float[]{0.1f}, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alias must not be blank");
    }

    @Test
    void searchWithEmptyVectorThrows() {
        assertThatThrownBy(() -> store.search("knowledge_claims_live", new float[0], 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queryVector must not be empty");
    }

    @Test
    void searchWithNullVectorThrows() {
        assertThatThrownBy(() -> store.search("knowledge_claims_live", null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queryVector must not be empty");
    }

    @Test
    void claimVectorHitRecordFieldsAccessible() {
        KnowledgeClaimVectorPoint point = new KnowledgeClaimVectorPoint(
                "proj-1", "v1", "c-1", "dv-1",
                SourceType.REQUIREMENT, Authority.PRIMARY, "ACTIVE",
                "fk", "subj", "pred", "TEXT", "",
                List.of(), "gen", "schema-v1", "model", "hash");
        KnowledgeClaimVectorQdrantStore.ClaimVectorHit hit =
                new KnowledgeClaimVectorQdrantStore.ClaimVectorHit("c-1", 0.95, point);
        assertThat(hit.claimId()).isEqualTo("c-1");
        assertThat(hit.score()).isEqualTo(0.95);
        assertThat(hit.point()).isSameAs(point);
    }

    // ── search 请求协议（高：Review 1）──────────────────────────────────

    /**
     * 验证 search() 向 /points/query 发送命名向量查询（query + using="dense"）——
     * 而非先前与命名向量不兼容的 /points/search + 裸 vector。请求体断言直接钉死协议。
     */
    @Test
    void searchUsesNamedVectorQueryProtocol() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KnowledgeClaimVectorQdrantStore store =
                new KnowledgeClaimVectorQdrantStore(builder.build(), properties);

        String payload = """
                {"result":[{"id":1,"score":0.92,"payload":{
                  "projectId":"proj-1","businessVersion":"v1","claimId":"c-1",
                  "documentVersionId":"dv-1","sourceType":"REQUIREMENT","authority":"PRIMARY",
                  "knowledgeStatus":"ACTIVE","factKey":"fk","subject":"subj","predicate":"pred",
                  "valueType":"TEXT","unit":"","evidenceIds":[],
                  "projectionGenerationId":"gen","projectionSchemaVersion":"schema-v1",
                  "embeddingModel":"model","textHash":"hash"}}]}""";
        server.expect(requestTo("/collections/knowledge_claims_live-proj-1-v1/points/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.query").isArray())
                .andExpect(jsonPath("$.using").value("dense"))
                .andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.with_payload").value(true))
                .andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));

        List<KnowledgeClaimVectorQdrantStore.ClaimVectorHit> hits =
                store.search("knowledge_claims_live-proj-1-v1", new float[]{0.1f, 0.2f}, 10);
        server.verify(); // 任何未匹配请求（如旧 /points/search）都会在此失败

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).claimId()).isEqualTo("c-1");
        assertThat(hits.get(0).score()).isEqualTo(0.92);
        assertThat(hits.get(0).point().sourceType()).isEqualTo(SourceType.REQUIREMENT);
    }
}
