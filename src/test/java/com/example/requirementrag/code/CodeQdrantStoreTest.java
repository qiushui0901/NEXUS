package com.example.requirementrag.code;

import com.example.requirementrag.model.CodeChunk;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeQdrantStoreTest {

    @Test
    void retrievalTextFrontLoadsSymbolPathAndImplementationRoleWithoutChangingPayloadText() {
        CodeChunk chunk = new CodeChunk("id", "demo", "abc",
                "module/src/main/java/com/acme/note/service/impl/NoteServiceImpl.java",
                "method", "publishNote", 10, 30,
                "public void publishNote() { persist(); }", "hash", "java");

        String retrievalText = CodeQdrantStore.retrievalText(chunk);

        assertThat(retrievalText)
                .startsWith("file path: module/src/main/java/com/acme/note/service/impl/NoteServiceImpl.java")
                .contains("symbol type: method")
                .contains("symbol name: publishNote")
                .contains("symbol terms: publish Note")
                .contains("service implementation business logic")
                .contains("服务实现 业务逻辑 实现入口")
                .endsWith(chunk.text());
        assertThat(chunk.text()).isEqualTo("public void publishNote() { persist(); }");
    }

    @Test
    void retrievalTextClassifiesControllerAndTestRolesDeterministically() {
        CodeChunk controller = chunk("src/main/java/com/acme/web/controller/NoteController.java", "publishNote");
        CodeChunk test = chunk("src/test/java/com/acme/note/NoteServiceTest.java", "publishNote");

        assertThat(CodeQdrantStore.retrievalText(controller)).contains("controller api endpoint request entry");
        assertThat(CodeQdrantStore.retrievalText(test)).contains("test verification");
    }


    @Test
    void denseRetrievalTextBoundsLongSourceButKeepsMetadataAtTheFront() {
        String longSource = "x".repeat(2_000) + "TAIL_MARKER";
        CodeChunk chunk = new CodeChunk("id", "demo", "abc",
                "src/main/java/com/acme/service/impl/NoteServiceImpl.java",
                "method", "publishNote", 1, 200, longSource, "hash", "java");

        String denseText = CodeQdrantStore.denseRetrievalText(chunk);

        assertThat(denseText)
                .startsWith("file path: src/main/java/com/acme/service/impl/NoteServiceImpl.java")
                .contains("symbol name: publishNote")
                .contains("source code prefix:")
                .doesNotContain("TAIL_MARKER");
    }

    @Test
    void rerankCandidatesPromotesServiceImplementationForExplicitRoleIntent() {
        CodeChunk controller = chunk("src/main/java/com/acme/controller/AgentController.java", "chat");
        CodeChunk helper = chunk("src/main/java/com/acme/tool/AgentTools.java", "searchRelatedTopics");
        CodeChunk implementation = chunk("src/main/java/com/acme/service/impl/AgentServiceImpl.java", "chat");

        List<CodeChunk> reranked = CodeQdrantStore.rerankCandidates(
                "AI 普通对话服务实现入口", List.of(controller, helper, implementation), 3);

        assertThat(reranked).containsExactly(implementation, controller, helper);
    }

    @Test
    void rerankCandidatesExpandsBilingualIntentIntoStableSymbolTerms() {
        CodeChunk noise = chunk("src/main/java/com/acme/search/CanalSchedule.java", "syncUserIndex");
        CodeChunk userSearch = chunk("src/main/java/com/acme/search/service/impl/UserServiceImpl.java", "searchUser");
        CodeChunk chat = chunk("src/main/java/com/acme/agent/service/impl/AgentServiceImpl.java", "chat");

        assertThat(CodeQdrantStore.rerankCandidates(
                "构建用户搜索 Wiki 且不能混入笔记搜索", List.of(noise, userSearch), 2))
                .startsWith(userSearch);
        assertThat(CodeQdrantStore.rerankCandidates(
                "AI 普通对话复用受控模型调用边界", List.of(noise, chat), 2))
                .startsWith(chat);
    }

    @Test
    void queryExpansionIsBoundedToKnownIntentPhrases() {
        assertThat(CodeQdrantStore.expandQuery("用户搜索和搜索摘要"))
                .contains("search user", "search summary");
        assertThat(CodeQdrantStore.expandQuery("无关查询")).isEqualTo("无关查询");
    }

    @Test
    void rerankCandidatesPreservesOriginalOrderWithoutStructuralOrLexicalSignal() {
        CodeChunk first = chunk("src/main/java/com/acme/Alpha.java", "alpha");
        CodeChunk second = chunk("src/main/java/com/acme/Beta.java", "beta");

        assertThat(CodeQdrantStore.rerankCandidates("无关查询", List.of(first, second), 2))
                .containsExactly(first, second);
    }

    @Test
    void rerankCandidatesStillHonorsRequestedLimit() {
        CodeChunk first = chunk("src/main/java/com/acme/Alpha.java", "alpha");
        CodeChunk exact = chunk("src/main/java/com/acme/Beta.java", "publishNote");

        assertThat(CodeQdrantStore.rerankCandidates("publishNote implementation", List.of(first, exact), 1))
                .containsExactly(exact);
    }

    @Test
    void structuralRerankPromotesClassNameAndMethodNameMatch() {
        CodeChunk noise = chunk("src/main/java/com/acme/Alpha.java", "alpha");
        CodeChunk target = chunk("src/main/java/com/acme/service/VipService.java", "receiveGift");
        CodeChunk sameMethodOtherClass = chunk("src/main/java/com/acme/service/GiftService.java", "receiveGift");

        assertThat(CodeQdrantStore.rerankCandidates(
                "查找 VipService.receiveGift 的实现位置。",
                List.of(noise, sameMethodOtherClass, target), 3, true, true))
                .startsWith(target);
    }

    @Test
    void structuralRerankPromotesClassNameOnlyMatchForClassScopedQueries() {
        CodeChunk noise = chunk("src/main/java/com/acme/Alpha.java", "alpha");
        CodeChunk sameClassMethod = chunk("src/main/java/com/acme/service/FarmService.java", "dig");

        assertThat(CodeQdrantStore.rerankCandidates(
                "在 FarmService 中由哪个方法实现？", List.of(noise, sameClassMethod), 2, true, true))
                .startsWith(sameClassMethod);
    }

    @Test
    void structuralRerankKeepsLegacyBehaviorWhenStructuralSignalsDisabled() {
        CodeChunk first = chunk("src/main/java/com/acme/Alpha.java", "alpha");
        CodeChunk target = chunk("src/main/java/com/acme/service/VipService.java", "receiveGift");

        // 旧规则：查询只含类名时不做类名提升，保持原始名次；启用结构信号后类内方法被提升
        assertThat(CodeQdrantStore.rerankCandidates(
                "在 VipService 中由哪个方法实现？", List.of(first, target), 2, true, false))
                .startsWith(first);
        assertThat(CodeQdrantStore.rerankCandidates(
                "在 VipService 中由哪个方法实现？", List.of(first, target), 2, true, true))
                .startsWith(target);
    }

    @Test
    void structuralRerankBoostsExplicitFilePathMatchesOverSameNameClassFiles() {
        CodeChunk sameNameOtherFile = chunk("src/other/HeroService.java", "save", "o-1");
        CodeChunk targetFile = chunk("src/main/java/com/acme/HeroService.java", "save", "t-1");

        // 同名类在两个文件：查询显式给出路径时，路径命中者必须置前（无路径信号时按 RRF 名次）
        assertThat(CodeQdrantStore.rerankCandidates(
                "解释 src/main/java/com/acme/HeroService.java 中 HeroService 的实现",
                List.of(sameNameOtherFile, targetFile), 2, true, true))
                .startsWith(targetFile);
    }

    @Test
    void methodFirstPromotesOnlyTargetClassMethodsAheadOfContainerChunks() {
        CodeChunk clazz = new CodeChunk("c-1", "demo", "abc",
                "src/main/java/com/acme/service/FarmService.java", "class", "FarmService",
                1, 10, "class FarmService {}", "hash", "java");
        CodeChunk method = chunk("src/main/java/com/acme/service/FarmService.java", "dig", "m-1");
        CodeChunk file = new CodeChunk("f-1", "demo", "abc",
                "src/main/java/com/acme/service/FarmService.java", "file", "FarmService.java",
                1, 10, "file chunk", "hash", "java");

        assertThat(CodeQdrantStore.methodFirst(
                List.of(clazz, file, method), List.of("src/main/java/com/acme/service/FarmService.java"), 3))
                .extracting(CodeChunk::id).containsExactly("m-1", "c-1", "f-1");
    }

    @Test
    void methodFirstDoesNotPromoteMethodsOfUnrelatedClasses() {
        CodeChunk unrelatedMethod = chunk("src/OtherService.java", "run", "u-1");
        CodeChunk targetClass = new CodeChunk("c-1", "demo", "abc",
                "src/FarmService.java", "class", "FarmService",
                1, 10, "class FarmService {}", "hash", "java");
        CodeChunk targetMethod = chunk("src/FarmService.java", "dig", "m-1");

        // 无关类方法保持原有相对位置，不因 methodFirst 被提权；目标类方法仍前置于容器 chunk
        assertThat(CodeQdrantStore.methodFirst(
                List.of(unrelatedMethod, targetClass, targetMethod), List.of("src/FarmService.java"), 3))
                .extracting(CodeChunk::id).containsExactly("m-1", "u-1", "c-1");
    }

    @Test
    void unionCandidatesKeepsGlobalOrderAndBackfillsClassScopeOnly() {
        CodeChunk globalA = chunk("src/A.java", "run", "g-a");
        CodeChunk globalB = chunk("src/FarmService.java", "dig", "g-b");
        CodeChunk duplicate = chunk("src/FarmService.java", "dig", "g-b");
        CodeChunk backfill = chunk("src/FarmService.java", "harvest", "c-1");

        assertThat(CodeQdrantStore.unionCandidates(
                List.of(globalA, globalB), List.of(duplicate, backfill)))
                .extracting(CodeChunk::id)
                .containsExactly("g-a", "g-b", "c-1");
    }

    @Test
    void classScopedQueryUsesMatchAnyForMultiValueFileScopeFilter() throws Exception {
        org.springframework.web.client.RestClient.Builder builder = org.springframework.web.client.RestClient.builder();
        org.springframework.test.web.client.MockRestServiceServer server =
                org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        org.springframework.web.client.RestClient client = builder.build();
        com.example.requirementrag.config.RagProperties props =
                mock(com.example.requirementrag.config.RagProperties.class);
        when(props.retrieval()).thenReturn(new com.example.requirementrag.config.RagProperties.Retrieval(
                50, 50, 40, 20, 10, false, 1_000, 3, 3, 30_000,
                -1, -1, -1, -1, null, null, null, true, 3));
        com.example.requirementrag.retrieval.EmbeddingBatcher batcher =
                mock(com.example.requirementrag.retrieval.EmbeddingBatcher.class);
        when(batcher.embedAll(any())).thenReturn(List.of(new float[2]));

        // ensureCollection 探测 + desc_dense 能力探测（无 desc_dense → 仅 dense+sparse 两路预取）
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.endsWith("/collections/test-code")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(
                        org.springframework.http.HttpMethod.GET))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{}", org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.endsWith("/collections/test-code")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(
                        org.springframework.http.HttpMethod.GET))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"dense\":{\"size\":2}}}}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        // 全局查询（无文件范围过滤）
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.endsWith("/points/query")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"result\":{\"points\":[]}}", org.springframework.http.MediaType.APPLICATION_JSON));
        // 类范围查询：filter 必须使用 match.any 且不得出现 match.value 数组
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.endsWith("/points/query")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath(
                        "$.prefetch[0].filter.must[1].match.any[0]").value("src/Alpha.java"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath(
                        "$.prefetch[0].filter.must[1].match.any[1]").value("src/Beta.java"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath(
                        "$.prefetch[0].filter.must[1].match.value").doesNotExist())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"result\":{\"points\":[]}}", org.springframework.http.MediaType.APPLICATION_JSON));

        CodeQdrantStore store = new CodeQdrantStore(client,
                mock(org.springframework.ai.embedding.EmbeddingModel.class),
                batcher,
                new com.example.requirementrag.retrieval.SparseVectorizer(),
                props, null);

        CodeQdrantStore.ScopedSearchResult result = store.searchWithClassScope(
                "test-code", "在 HeroService 中由哪个方法实现？", "demo",
                List.of("src/Alpha.java", "src/Beta.java"), null, 10);

        assertThat(result.global()).isEmpty();
        assertThat(result.classScoped()).isEmpty();
        server.verify();
    }

    @Test
    void classScopedSearchBackfillsWhenGlobalLacksTheRequestedSymbol() throws Exception {
        org.springframework.web.client.RestClient.Builder builder = org.springframework.web.client.RestClient.builder();
        org.springframework.test.web.client.MockRestServiceServer server =
                org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        org.springframework.web.client.RestClient client = builder.build();
        com.example.requirementrag.config.RagProperties props =
                mock(com.example.requirementrag.config.RagProperties.class);
        when(props.retrieval()).thenReturn(new com.example.requirementrag.config.RagProperties.Retrieval(
                50, 50, 40, 20, 10, false, 1_000, 3, 3, 30_000,
                -1, -1, -1, -1, null, null, null, true, 3));
        com.example.requirementrag.retrieval.EmbeddingBatcher batcher =
                mock(com.example.requirementrag.retrieval.EmbeddingBatcher.class);
        when(batcher.embedAll(any())).thenReturn(List.of(new float[2]));

        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.endsWith("/collections/test-code")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{}", org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.endsWith("/collections/test-code")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"dense\":{\"size\":2}}}}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        // 全局查询返回目标类的另一个方法 dig——目标符号 harvest 未命中，不得走快速路径
        String digPoint = "{\"id\":\"p1\",\"payload\":{\"projectId\":\"demo\",\"commitSha\":\"abc\","
                + "\"filePath\":\"src/Alpha.java\",\"symbolType\":\"method\",\"symbolName\":\"dig\","
                + "\"startLine\":4,\"endLine\":6,\"text\":\"void dig() {}\",\"contentHash\":\"h1\"}}";
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.endsWith("/points/query")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"result\":{\"points\":[" + digPoint + "]}}", org.springframework.http.MediaType.APPLICATION_JSON));
        // 类范围查询补召回：返回目标符号 harvest
        String harvestPoint = "{\"id\":\"p2\",\"payload\":{\"projectId\":\"demo\",\"commitSha\":\"abc\","
                + "\"filePath\":\"src/Alpha.java\",\"symbolType\":\"method\",\"symbolName\":\"harvest\","
                + "\"startLine\":9,\"endLine\":11,\"text\":\"void harvest() {}\",\"contentHash\":\"h2\"}}";
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.endsWith("/points/query")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath(
                        "$.prefetch[0].filter.must[1].match.any[0]").value("src/Alpha.java"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"result\":{\"points\":[" + harvestPoint + "]}}", org.springframework.http.MediaType.APPLICATION_JSON));

        CodeQdrantStore store = new CodeQdrantStore(client,
                mock(org.springframework.ai.embedding.EmbeddingModel.class),
                batcher,
                new com.example.requirementrag.retrieval.SparseVectorizer(),
                props, null);

        CodeQdrantStore.ScopedSearchResult result = store.searchWithClassScope(
                "test-code", "查找 AlphaService.harvest 的实现位置。", "demo",
                List.of("src/Alpha.java"), "harvest", 10);

        // 目标符号经类内补召回进入最终结果，符号命中加权使其置顶
        assertThat(result.classScoped()).extracting(CodeChunk::symbolName).startsWith("harvest");
        assertThat(result.candidates()).extracting(CodeChunk::id).containsExactly("p1", "p2");
        server.verify();
    }

    @Test
    void classScopedSearchKeepsGlobalOrderWhenClassScopeCannotSupplyTheRequestedSymbol() throws Exception {
        org.springframework.web.client.RestClient.Builder builder = org.springframework.web.client.RestClient.builder();
        org.springframework.test.web.client.MockRestServiceServer server =
                org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        org.springframework.web.client.RestClient client = builder.build();
        com.example.requirementrag.config.RagProperties props =
                mock(com.example.requirementrag.config.RagProperties.class);
        when(props.retrieval()).thenReturn(new com.example.requirementrag.config.RagProperties.Retrieval(
                50, 50, 40, 20, 10, false, 1_000, 3, 3, 30_000,
                -1, -1, -1, -1, null, null, null, true, 3));
        com.example.requirementrag.retrieval.EmbeddingBatcher batcher =
                mock(com.example.requirementrag.retrieval.EmbeddingBatcher.class);
        when(batcher.embedAll(any())).thenReturn(List.of(new float[2]));

        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.endsWith("/collections/test-code")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{}", org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.endsWith("/collections/test-code")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"dense\":{\"size\":2}}}}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        String digPoint = "{\"id\":\"p1\",\"payload\":{\"projectId\":\"demo\",\"commitSha\":\"abc\","
                + "\"filePath\":\"src/Alpha.java\",\"symbolType\":\"method\",\"symbolName\":\"dig\","
                + "\"startLine\":4,\"endLine\":6,\"text\":\"void dig() {}\",\"contentHash\":\"h1\"}}";
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.endsWith("/points/query")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"result\":{\"points\":[" + digPoint + "]}}", org.springframework.http.MediaType.APPLICATION_JSON));
        // 类范围查询返回的候选不含目标符号（解析器误把业务文本标识符当方法名）→ 不做并集扰动
        String seedPoint = "{\"id\":\"p2\",\"payload\":{\"projectId\":\"demo\",\"commitSha\":\"abc\","
                + "\"filePath\":\"src/Alpha.java\",\"symbolType\":\"method\",\"symbolName\":\"seed\","
                + "\"startLine\":9,\"endLine\":11,\"text\":\"void seed() {}\",\"contentHash\":\"h2\"}}";
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.endsWith("/points/query")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"result\":{\"points\":[" + seedPoint + "]}}", org.springframework.http.MediaType.APPLICATION_JSON));

        CodeQdrantStore store = new CodeQdrantStore(client,
                mock(org.springframework.ai.embedding.EmbeddingModel.class),
                batcher,
                new com.example.requirementrag.retrieval.SparseVectorizer(),
                props, null);

        CodeQdrantStore.ScopedSearchResult result = store.searchWithClassScope(
                "test-code", "在 AlphaService 中由哪个方法实现？", "demo",
                List.of("src/Alpha.java"), "viewAddHeroes", 10);

        // 类内无法提供目标符号：保持全局精排（方法优先），不被并集扰动
        assertThat(result.classScoped()).extracting(CodeChunk::symbolName).startsWith("dig");
        assertThat(result.candidates()).extracting(CodeChunk::id).containsExactly("p1");
        server.verify();
    }

    @Test
    void classScopedSearchSkipsSecondQueryWhenGlobalAlreadyHasAClassMethod() throws Exception {
        org.springframework.web.client.RestClient.Builder builder = org.springframework.web.client.RestClient.builder();
        org.springframework.test.web.client.MockRestServiceServer server =
                org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        org.springframework.web.client.RestClient client = builder.build();
        com.example.requirementrag.config.RagProperties props =
                mock(com.example.requirementrag.config.RagProperties.class);
        when(props.retrieval()).thenReturn(new com.example.requirementrag.config.RagProperties.Retrieval(
                50, 50, 40, 20, 10, false, 1_000, 3, 3, 30_000,
                -1, -1, -1, -1, null, null, null, true, 3));
        com.example.requirementrag.retrieval.EmbeddingBatcher batcher =
                mock(com.example.requirementrag.retrieval.EmbeddingBatcher.class);
        when(batcher.embedAll(any())).thenReturn(List.of(new float[2]));

        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.endsWith("/collections/test-code")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{}", org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.endsWith("/collections/test-code")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"dense\":{\"size\":2}}}}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        // 全局查询返回类容器 chunk（RRF 首位）+ 类内方法 chunk → 快速路径，不得再发起类内查询，
        // 且返回前必须做方法优先稳定重排（容器类 chunk 不是方法类查询的答案）
        String containerPoint = "{\"id\":\"p1\",\"payload\":{\"projectId\":\"demo\",\"commitSha\":\"abc\","
                + "\"filePath\":\"src/Alpha.java\",\"symbolType\":\"class\",\"symbolName\":\"Alpha\","
                + "\"startLine\":1,\"endLine\":9,\"text\":\"class Alpha {}\",\"contentHash\":\"h1\"}}";
        String methodPoint = "{\"id\":\"p2\",\"payload\":{\"projectId\":\"demo\",\"commitSha\":\"abc\","
                + "\"filePath\":\"src/Alpha.java\",\"symbolType\":\"method\",\"symbolName\":\"dig\","
                + "\"startLine\":4,\"endLine\":6,\"text\":\"void dig() {}\",\"contentHash\":\"h2\"}}";
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.endsWith("/points/query")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"result\":{\"points\":[" + containerPoint + "," + methodPoint + "]}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        CodeQdrantStore store = new CodeQdrantStore(client,
                mock(org.springframework.ai.embedding.EmbeddingModel.class),
                batcher,
                new com.example.requirementrag.retrieval.SparseVectorizer(),
                props, null);

        CodeQdrantStore.ScopedSearchResult result = store.searchWithClassScope(
                "test-code", "在 AlphaService 中由哪个方法实现？", "demo",
                List.of("src/Alpha.java"), null, 10);

        // 方法优先：dig 前置于容器类 chunk；且全局候选归因与最终结果来自同一次检索
        assertThat(result.classScoped()).extracting(CodeChunk::symbolName).containsExactly("dig", "Alpha");
        assertThat(result.candidates()).extracting(CodeChunk::id).containsExactly("p1", "p2");
        server.verify();
    }

    @Test
    void codeSearchTraceDefensivelyCopiesBothStages() {
        CodeChunk candidate = chunk("src/main/java/com/acme/Alpha.java", "alpha");
        CodeChunk ranked = chunk("src/main/java/com/acme/Beta.java", "beta");
        java.util.ArrayList<CodeChunk> candidates = new java.util.ArrayList<>(List.of(candidate));
        java.util.ArrayList<CodeChunk> rankedValues = new java.util.ArrayList<>(List.of(ranked));

        CodeQdrantStore.CodeSearchTrace trace =
                new CodeQdrantStore.CodeSearchTrace(candidates, rankedValues);
        candidates.clear();
        rankedValues.clear();

        assertThat(trace.candidates()).containsExactly(candidate);
        assertThat(trace.ranked()).containsExactly(ranked);
        assertThat(trace.denseCandidates()).isEmpty();
        assertThat(trace.sparseCandidates()).isEmpty();
        assertThatThrownBy(() -> trace.candidates().add(ranked))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void codeSearchTraceDefensivelyCopiesPrefetchStages() {
        CodeChunk dense = chunk("src/main/java/com/acme/Alpha.java", "alpha");
        CodeChunk sparse = chunk("src/main/java/com/acme/Beta.java", "beta");
        java.util.ArrayList<CodeChunk> denseValues = new java.util.ArrayList<>(List.of(dense));
        java.util.ArrayList<CodeChunk> sparseValues = new java.util.ArrayList<>(List.of(sparse));

        CodeQdrantStore.CodeSearchTrace trace = new CodeQdrantStore.CodeSearchTrace(
                List.of(), List.of(), denseValues, sparseValues);
        denseValues.clear();
        sparseValues.clear();

        assertThat(trace.denseCandidates()).containsExactly(dense);
        assertThat(trace.sparseCandidates()).containsExactly(sparse);
    }

    @Test
    void semanticRerankReordersCandidatesByBgeScoreAndDropsUnknownIds() {
        CodeChunk first = chunk("src/main/java/com/acme/Alpha.java", "alpha", "id-1");
        CodeChunk second = chunk("src/main/java/com/acme/Beta.java", "beta", "id-2");
        CodeChunk third = chunk("src/main/java/com/acme/Gamma.java", "gamma", "id-3");
        com.example.requirementrag.rerank.BgeReranker bge = mock(com.example.requirementrag.rerank.BgeReranker.class);
        when(bge.rerank(eq("query"), any(), eq(3)))
                .thenReturn(List.of(
                        new com.example.requirementrag.model.ChunkRecord(third.id(), "demo", "abc",
                                third.filePath(), null, "", "passage", "hash", 1, 2),
                        new com.example.requirementrag.model.ChunkRecord(second.id(), "demo", "abc",
                                second.filePath(), null, "", "passage", "hash", 1, 2)));
        CodeQdrantStore store = storeWith(bge);

        assertThat(store.semanticRerank("query", List.of(first, second, third)))
                .containsExactly(third, second);
    }

    @Test
    void semanticRerankFallsBackToRrfOrderWhenBgeUnavailable() {
        CodeChunk first = chunk("src/main/java/com/acme/Alpha.java", "alpha");
        CodeChunk second = chunk("src/main/java/com/acme/Beta.java", "beta");
        com.example.requirementrag.rerank.BgeReranker bge = mock(com.example.requirementrag.rerank.BgeReranker.class);
        when(bge.rerank(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("endpoint unavailable"));
        CodeQdrantStore store = storeWith(bge);

        assertThat(store.semanticRerank("query", List.of(first, second)))
                .containsExactly(first, second);
    }

    @Test
    void semanticRerankPassesRetrievalTextAsBgePassage() {
        CodeChunk chunk = chunk("src/main/java/com/acme/service/impl/NoteServiceImpl.java", "publishNote");
        com.example.requirementrag.rerank.BgeReranker bge = mock(com.example.requirementrag.rerank.BgeReranker.class);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.List<com.example.requirementrag.model.ChunkRecord>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        when(bge.rerank(eq("query"), captor.capture(), anyInt())).thenReturn(List.of());
        CodeQdrantStore store = storeWith(bge);

        store.semanticRerank("query", List.of(chunk));

        assertThat(captor.getValue().get(0).childText())
                .startsWith("file path: src/main/java/com/acme/service/impl/NoteServiceImpl.java");
        assertThat(captor.getValue().get(0).filename()).isEqualTo(chunk.filePath());
    }

    @Test
    void semanticRerankPrunesInputToBgeTopKBeforeScoring() {
        java.util.ArrayList<CodeChunk> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            candidates.add(chunk("src/main/java/com/acme/Alpha.java", "alpha" + i, "id-" + i));
        }
        com.example.requirementrag.rerank.BgeReranker bge = mock(com.example.requirementrag.rerank.BgeReranker.class);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.List<com.example.requirementrag.model.ChunkRecord>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        when(bge.rerank(eq("query"), captor.capture(), eq(20))).thenReturn(List.of());
        CodeQdrantStore store = storeWith(bge);

        store.semanticRerank("query", candidates);

        assertThat(captor.getValue()).hasSize(20);
        assertThat(captor.getValue().get(0).id()).isEqualTo("id-0");
        assertThat(captor.getValue().get(19).id()).isEqualTo("id-19");
    }

    private CodeQdrantStore storeWith(com.example.requirementrag.rerank.BgeReranker bge) {
        com.example.requirementrag.config.RagProperties properties =
                mock(com.example.requirementrag.config.RagProperties.class);
        when(properties.retrieval()).thenReturn(new com.example.requirementrag.config.RagProperties.Retrieval(
                50, 50, 40, 20, 10, false, 1_000, 3, 3, 30_000,
                -1, -1, -1, -1, null, null, null, true, 3));
        return new CodeQdrantStore(mock(org.springframework.web.client.RestClient.class),
                mock(org.springframework.ai.embedding.EmbeddingModel.class),
                mock(com.example.requirementrag.retrieval.EmbeddingBatcher.class),
                new com.example.requirementrag.retrieval.SparseVectorizer(),
                properties, bge);
    }

    @Test
    void idempotentQueryRetriesOneTransientRequestBodyIoFailure() {
        AtomicInteger attempts = new AtomicInteger();

        String result = CodeQdrantStore.executeIdempotentQuery(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new HttpMessageNotWritableException("Could not write JSON",
                        new IOException("Error writing request body to server"));
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void idempotentQueryStopsAfterOneRetryWhenIoFailurePersists() {
        AtomicInteger attempts = new AtomicInteger();
        ResourceAccessException failure = new ResourceAccessException("Qdrant unavailable",
                new IOException("connection reset"));

        assertThatThrownBy(() -> CodeQdrantStore.executeIdempotentQuery(() -> {
            attempts.incrementAndGet();
            throw failure;
        })).isSameAs(failure);

        assertThat(attempts).hasValue(2);
    }

    @Test
    void idempotentQueryDoesNotRetryHttpStatusFailures() {
        AtomicInteger attempts = new AtomicInteger();
        HttpClientErrorException failure = new HttpClientErrorException(HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> CodeQdrantStore.executeIdempotentQuery(() -> {
            attempts.incrementAndGet();
            throw failure;
        })).isSameAs(failure);

        assertThat(attempts).hasValue(1);
    }

    private CodeChunk chunk(String path, String symbolName) {
        return chunk(path, symbolName, "id");
    }

    private CodeChunk chunk(String path, String symbolName, String id) {
        return new CodeChunk(id, "demo", "abc", path, "method", symbolName,
                1, 2, "void method() {}", "hash", "java");
    }
    @Test
    void publishProjectWritesVersionedCollectionVerifiesAndCreatesAliasAtomically() throws Exception {
        org.springframework.web.client.RestClient.Builder builder = org.springframework.web.client.RestClient.builder();
        org.springframework.test.web.client.MockRestServiceServer server =
                org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        org.springframework.web.client.RestClient client = builder.build();
        com.example.requirementrag.config.RagProperties properties =
                mock(com.example.requirementrag.config.RagProperties.class);
        when(properties.retrieval()).thenReturn(new com.example.requirementrag.config.RagProperties.Retrieval(
                50, 50, 40, 20, 10, false, 1_000, 3, 3, 30_000,
                -1, -1, -1, -1, null, null, null, true, 3));


        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.startsWith("/collections/code_x-live-")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.GET))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(HttpStatus.NOT_FOUND));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.startsWith("/collections/code_x-live-")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.PUT))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess());
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.containsString("/points?wait=true")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.PUT))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess());
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.containsString("/points/count")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"result\": {\"count\": 2}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.containsString("/aliases")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"result\": {\"aliases\": []}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.containsString("/collections/aliases")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess());
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.containsString("/collections/code_x-live")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess());
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.containsString("/collections")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.GET))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"result\": {\"collections\": [{\"name\": \"code_x-live-1\"}, {\"name\": \"code_x-live-2\"}]}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        List<CodeChunk> chunks = List.of(
                chunk("src/Alpha.java", "run"),
                chunk("src/Beta.java", "stop"));
        com.example.requirementrag.retrieval.EmbeddingBatcher batcher =
                mock(com.example.requirementrag.retrieval.EmbeddingBatcher.class);
        when(batcher.embedAll(any())).thenReturn(List.of(new float[2], new float[2]));
        com.example.requirementrag.config.RagProperties props =
                mock(com.example.requirementrag.config.RagProperties.class);
        when(props.retrieval()).thenReturn(new com.example.requirementrag.config.RagProperties.Retrieval(
                50, 50, 40, 20, 10, false, 1_000, 3, 3, 30_000,
                -1, -1, -1, -1, null, null, null, true, 3));
        CodeQdrantStore store = new CodeQdrantStore(client,
                mock(org.springframework.ai.embedding.EmbeddingModel.class),
                batcher,
                new com.example.requirementrag.retrieval.SparseVectorizer(),
                props, null);

        store.publishProject("code_x-live", "demo", chunks);

        server.verify();
    }

    @Test
    void scrollChunkIdsWalksAllPagesUntilNextPageOffsetIsNull() {
        org.springframework.web.client.RestClient.Builder builder = org.springframework.web.client.RestClient.builder();
        org.springframework.test.web.client.MockRestServiceServer server =
                org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        org.springframework.web.client.RestClient client = builder.build();
        com.example.requirementrag.config.RagProperties props =
                mock(com.example.requirementrag.config.RagProperties.class);
        when(props.retrieval()).thenReturn(new com.example.requirementrag.config.RagProperties.Retrieval(
                50, 50, 40, 20, 10, false, 1_000, 3, 3, 30_000,
                -1, -1, -1, -1, null, null, null, true, 3));
        CodeQdrantStore store = new CodeQdrantStore(client,
                mock(org.springframework.ai.embedding.EmbeddingModel.class),
                mock(com.example.requirementrag.retrieval.EmbeddingBatcher.class),
                new com.example.requirementrag.retrieval.SparseVectorizer(),
                props, null);

        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.containsString("/collections/code-live")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(
                        org.springframework.http.HttpMethod.GET))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"result\": {\"exists\": true}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        // 第一页：2 个 ID + next_page_offset；第二页：1 个 ID + null
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.containsString("/points/scroll")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(
                        org.springframework.http.HttpMethod.POST))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"result\": {\"points\": [{\"id\": \"page1-a\"}, {\"id\": \"page1-b\"}],"
                                + " \"next_page_offset\": 2}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.containsString("/points/scroll")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"result\": {\"points\": [{\"id\": \"page2-c\"}],"
                                + " \"next_page_offset\": null}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        java.util.List<String> ids = store.scrollChunkIds("code-live", "demo", "src/Huge.java", 100);

        assertThat(ids).containsExactly("page1-a", "page1-b", "page2-c");
        server.verify();
    }
}
