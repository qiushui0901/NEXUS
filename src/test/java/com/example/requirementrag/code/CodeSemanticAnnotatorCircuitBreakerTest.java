package com.example.requirementrag.code;

import com.example.requirementrag.model.CodeChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class CodeSemanticAnnotatorCircuitBreakerTest {

    @Test
    void opensCircuitAfterThreeConsecutiveBatchFailuresAndStaticallyAnnotatesTheRest() {
        AtomicInteger attempts = new AtomicInteger();
        CodeSemanticAnnotator annotator = new CodeSemanticAnnotator(null, null, null) {
            @Override
            List<CodeChunk> annotateBatch(List<CodeChunk> batch) {
                attempts.incrementAndGet();
                throw new IllegalStateException("annotation service unavailable");
            }
        };
        List<CodeChunk> chunks = IntStream.range(0, 100)
                .mapToObj(this::coreChunk)
                .toList();

        List<CodeChunk> annotated = annotator.annotate(chunks);

        assertThat(attempts)
                .as("并发下至少三次失败触发熔断；已提交批次数取决于调度，不做精确断言")
                .hasValueGreaterThanOrEqualTo(3);
        assertThat(annotated).hasSize(100)
                .allSatisfy(chunk -> {
                    assertThat(chunk.businessDescCn()).isNotBlank();
                    assertThat(chunk.businessDescEn()).isNotBlank();
                });
    }

    @Test
    void resetsConsecutiveFailureCountAfterASuccessfulBatch() {
        AtomicInteger attempts = new AtomicInteger();
        CodeSemanticAnnotator annotator = new CodeSemanticAnnotator(null, null, null) {
            @Override
            List<CodeChunk> annotateBatch(List<CodeChunk> batch) {
                int attempt = attempts.incrementAndGet();
                if (attempt == 3) {
                    return batch.stream()
                            .map(chunk -> chunk.withSemantics("成功标注", "successful annotation", List.of(), List.of()))
                            .toList();
                }
                throw new IllegalStateException("intermittent annotation failure");
            }
        };
        List<CodeChunk> chunks = IntStream.range(0, 125)
                .mapToObj(this::coreChunk)
                .toList();

        List<CodeChunk> annotated = annotator.annotate(chunks);

        assertThat(attempts)
                .as("并发下成功批次会重置失败计数，但批次执行顺序不定，只保证总量")
                .hasValueGreaterThanOrEqualTo(3);
        assertThat(annotated).allSatisfy(chunk -> assertThat(chunk.businessDescCn()).isNotBlank());
    }

    private CodeChunk coreChunk(int index) {
        return new CodeChunk("chunk-" + index, "project-a", "commit", "src/OrderService.java",
                "method", "handle" + index, 1, 2, "void handle" + index + "() {}", "hash-" + index, "java")
                .withStaticAnalysis("OrderService", "", List.of(), List.of());
    }

    @Test
    void parallelAnnotationPreservesInputOrderAndConcurrency() throws Exception {
        CodeSemanticAnnotator annotator = new CodeSemanticAnnotator(null, null, null, 4) {
            @Override
            List<CodeChunk> annotateBatch(List<CodeChunk> batch) {
                return batch.stream()
                        .map(chunk -> chunk.withSemantics("并发-" + chunk.symbolName(),
                                "parallel", List.of(), List.of()))
                        .toList();
            }
        };
        List<CodeChunk> chunks = IntStream.range(0, 45)
                .mapToObj(this::coreChunk)
                .toList();

        List<CodeChunk> annotated = annotator.annotate(chunks);

        assertThat(annotated).hasSize(45);
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(annotated.get(i).symbolName())
                    .as("结果顺序必须与输入一致")
                    .isEqualTo(chunks.get(i).symbolName());
            assertThat(annotated.get(i).businessDescCn()).startsWith("并发-");
        }
    }
}
