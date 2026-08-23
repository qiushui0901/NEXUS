package com.example.requirementrag.knowledge.multisource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MultiSourceKnowledgePublishTest {
    @TempDir Path tempDir;

    private MultiSourceKnowledgeStore store;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        store = new MultiSourceKnowledgeStore(tempDir.resolve("publish.db").toString(), objectMapper);
    }

    @Test
    void publishAndRollbackActiveDocumentVersion() {
        assertThat(store.activeDocumentVersion("fengshen", "5.1")).isEmpty();

        store.publishDocumentVersion("fengshen", "5.1", "dv-1");
        assertThat(store.activeDocumentVersion("fengshen", "5.1")).contains("dv-1");

        store.publishDocumentVersion("fengshen", "5.1", "dv-2");
        assertThat(store.activeDocumentVersion("fengshen", "5.1")).contains("dv-2");

        store.rollbackActiveVersion("fengshen", "5.1", "dv-1");
        assertThat(store.activeDocumentVersion("fengshen", "5.1")).contains("dv-1");
    }

    @Test
    void businessVersionsAreIsolatedInManifest() {
        store.publishDocumentVersion("fengshen", "5.1", "dv-51");
        store.publishDocumentVersion("fengshen", "5.2", "dv-52");

        assertThat(store.activeDocumentVersion("fengshen", "5.1")).contains("dv-51");
        assertThat(store.activeDocumentVersion("fengshen", "5.2")).contains("dv-52");
    }
}