package com.example.requirementrag.code;

import com.example.requirementrag.model.CodeChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 磁盘标注缓存的写读闭环：LLM 摘要必须落盘、可读回、同源码不重复写。 */
class AnnotationCacheStoreTest {

    @TempDir
    Path tempDir;

    private static CodeChunk chunk(String text, String descCn, String descEn) {
        return new CodeChunk("id-" + text.hashCode(), "project-a", "commit", "src/Foo.java",
                "method", "bar", 1, 2, text, "hash-" + text.hashCode())
                .withFullSemantics(descCn, descEn, List.of(), List.of("k1"), List.of("q1"), List.of("s1"));
    }

    @Test
    void writesAndReadsBackEntries() throws Exception {
        AnnotationCacheStore store = new AnnotationCacheStore(new ObjectMapper(), tempDir);
        store.append("project-a", List.of(chunk("text-1", "描述一", "desc one")));

        Map<String, CodeQdrantStore.AnnotationEntry> loaded = store.load("project-a");
        assertEquals(1, loaded.size());
        CodeQdrantStore.AnnotationEntry entry = loaded.values().iterator().next();
        assertEquals("描述一", entry.businessDescCn());
        assertEquals("desc one", entry.businessDescEn());
        assertEquals(List.of("k1"), entry.keywords());
    }

    @Test
    void doesNotDuplicateSameSource() throws Exception {
        AnnotationCacheStore store = new AnnotationCacheStore(new ObjectMapper(), tempDir);
        store.append("project-a", List.of(chunk("text-1", "描述一", "desc one")));
        store.append("project-a", List.of(chunk("text-1", "描述一", "desc one"), chunk("text-2", "描述二", "desc two")));

        Map<String, CodeQdrantStore.AnnotationEntry> loaded = store.load("project-a");
        assertEquals(2, loaded.size());
        long lines = Files.lines(tempDir.resolve("project-a.jsonl")).count();
        assertEquals(2, lines);
    }

    @Test
    void nullSemanticsDoNotBreakAppend() throws Exception {
        AnnotationCacheStore store = new AnnotationCacheStore(new ObjectMapper(), tempDir);
        CodeChunk withNull = chunk("text-null", "描述三", null);
        store.append("project-a", List.of(withNull));

        Map<String, CodeQdrantStore.AnnotationEntry> loaded = store.load("project-a");
        assertEquals(1, loaded.size());
        assertNotNull(loaded.values().iterator().next().businessDescEn());
        assertTrue(Files.exists(tempDir.resolve("project-a.jsonl")));
    }
}
