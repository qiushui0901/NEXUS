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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Claim 向量 Qdrant 发布器测试：验证点结构（payload + 确定性 ID + dense-only vector）。
 * 不连接真实 Qdrant——只测试 {@link KnowledgeClaimVectorQdrantStore#buildPointMap}。
 */
class KnowledgeClaimVectorQdrantStoreTest {

    private final KnowledgeClaimVectorProperties properties = new KnowledgeClaimVectorProperties(
            true, true, true, true,
            "knowledge_claims_live", "knowledge-claim-vector-v1", "knowledge-claim-text-v1",
            200, 3, 32, 3, 2, null, "ACTIVE_DOC");
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

    @Test
    void searchFailureRethrowsInsteadOfMaskingAsEmptyHits() {
        // Med：Qdrant 检索服务故障必须上抛（由适配器转成 CLAIM_VECTOR_SEARCH_FAILED），
        // 不得把“向量服务不可用”伪装成“没有命中”
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KnowledgeClaimVectorQdrantStore store =
                new KnowledgeClaimVectorQdrantStore(builder.build(), properties);
        String alias = properties.liveAlias("proj-1", "v1");
        server.expect(requestTo("/collections/" + alias + "/points/query"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThatThrownBy(() -> store.search(alias, new float[]{0.1f}, 10))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void corruptedPointIsSkippedWhileValidHitsSurvive() {
        // Med：单条损坏 payload（缺必填字段）只跳过该点，同批其它合法命中保留；不得整次失败
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KnowledgeClaimVectorQdrantStore store =
                new KnowledgeClaimVectorQdrantStore(builder.build(), properties);
        String payload = """
                {"result":{"points":[
                  {"id":1,"score":0.92,"payload":{
                    "projectId":"proj-1","businessVersion":"v1","claimId":"c-1",
                    "documentVersionId":"dv-1","sourceType":"REQUIREMENT","authority":"PRIMARY",
                    "knowledgeStatus":"ACTIVE","factKey":"fk","subject":"subj","predicate":"pred",
                    "valueType":"TEXT","unit":"","evidenceIds":[],
                    "projectionGenerationId":"gen","projectionSchemaVersion":"schema-v1",
                    "embeddingModel":"model","textHash":"hash"}},
                  {"id":2,"score":0.5,"payload":{
                    "projectId":"proj-1","businessVersion":"v1","claimId":"c-bad",
                    "documentVersionId":"dv-1","sourceType":"REQUIREMENT","authority":"PRIMARY",
                    "factKey":"fk","subject":"s","predicate":"p","valueType":"TEXT","unit":"",
                    "evidenceIds":[],"projectionGenerationId":"gen",
                    "embeddingModel":"model","textHash":"hash"}}]}}""";
        String alias = properties.liveAlias("proj-1", "v1");
        server.expect(requestTo("/collections/" + alias + "/points/query"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));

        List<KnowledgeClaimVectorQdrantStore.ClaimVectorHit> hits =
                store.search(alias, new float[]{0.1f}, 10);

        // 缺 projectionSchemaVersion 的坏点被跳过，合法点保留
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).claimId()).isEqualTo("c-1");
    }

    @Test
    void incompletePointPayloadFailsFast() {
        // High：投影契约字段（schema/model）必填——构造时缺 schema 直接抛错，不允许回退旧版本
        assertThatThrownBy(() -> new KnowledgeClaimVectorPoint(
                "proj-1", "v1", "c-1", "dv-1",
                SourceType.REQUIREMENT, Authority.PRIMARY, "ACTIVE",
                "fk", "subj", "pred", "TEXT", "",
                List.of(), "gen", null, "model", "hash"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectionSchemaVersion");
    }

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

        // 高（Review 1）：真实 Qdrant /points/query 响应为 {"result":{"points":[...]}}
        // 而非顶层数组——旧测试模拟了错误结构导致通过但不符真实响应，此处按真实结构钉死。
        String payload = """
                {"result":{"points":[{"id":1,"score":0.92,"payload":{
                  "projectId":"proj-1","businessVersion":"v1","claimId":"c-1",
                  "documentVersionId":"dv-1","sourceType":"REQUIREMENT","authority":"PRIMARY",
                  "knowledgeStatus":"ACTIVE","factKey":"fk","subject":"subj","predicate":"pred",
                  "valueType":"TEXT","unit":"","evidenceIds":[],
                  "projectionGenerationId":"gen","projectionSchemaVersion":"schema-v1",
                  "embeddingModel":"model","textHash":"hash"}}]}}""";
        String alias = properties.liveAlias("proj-1", "v1");
        server.expect(requestTo("/collections/" + alias + "/points/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.query").isArray())
                .andExpect(jsonPath("$.using").value("dense"))
                .andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.with_payload").value(true))
                .andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));

        List<KnowledgeClaimVectorQdrantStore.ClaimVectorHit> hits =
                store.search(properties.liveAlias("proj-1", "v1"), new float[]{0.1f, 0.2f}, 10);
        server.verify(); // 任何未匹配请求（如旧 /points/search）都会在此失败

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).claimId()).isEqualTo("c-1");
        assertThat(hits.get(0).score()).isEqualTo(0.92);
        assertThat(hits.get(0).point().sourceType()).isEqualTo(SourceType.REQUIREMENT);
    }

    // ── alias 切换（高：Review 4：切换后旧 collection 回收必须 best-effort）──

    /**
     * 高（Review 4）：alias 已切换成功后，若 GET /collections 旧集合回收列表查询/解析
     * 临时失败，switchAlias 不得向上抛出——否则调用方会把它误判为"alias 切换失败"，
     * 进而删除当前 alias 指向的线上 collection，留下悬空 alias 与 SQLite/Qdrant 分叉。
     */
    @Test
    void switchAliasSucceedsEvenWhenRetireOldCollectionsFails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KnowledgeClaimVectorQdrantStore store =
                new KnowledgeClaimVectorQdrantStore(builder.build(), properties);

        String alias = properties.liveAlias("proj-1", "v1");
        // 1) aliasTarget → GET /aliases 返回旧目标 old-collection-1
        server.expect(requestTo("/aliases"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"result\":{\"aliases\":[{\"alias_name\":\"" + alias
                                + "\",\"collection_name\":\"old-collection-1\"}]}}",
                        MediaType.APPLICATION_JSON));
        // 2) POST /collections/aliases 切换成功
        server.expect(requestTo("/collections/aliases"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());
        // 3) GET /collections 旧集合回收列表查询失败（500）——不得让 switchAlias 抛异常
        server.expect(requestTo("/collections"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        // 切换已完成，回收失败仅告警（best-effort），不得抛出
        assertThatCode(() -> store.switchAlias(alias, "new-collection-1"))
                .doesNotThrowAnyException();
        server.verify();
    }
}
