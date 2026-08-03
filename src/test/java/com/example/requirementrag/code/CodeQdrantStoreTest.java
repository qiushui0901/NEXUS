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
        assertThatThrownBy(() -> trace.candidates().add(ranked))
                .isInstanceOf(UnsupportedOperationException.class);
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
        return new CodeChunk("id", "demo", "abc", path, "method", symbolName,
                1, 2, "void method() {}", "hash", "java");
    }
}
