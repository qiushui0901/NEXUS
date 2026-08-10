package com.example.requirementrag.evidence;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceRegistryTest {

    @Test
    void createsStableWhitelistedReferencesForRequirementAndCodeEvidence() {
        ChunkRecord requirement = requirement("req-1", "docs/spec.md", "用户提交后进入异步处理流程");
        CodeChunk code = code("code-1", "src/main/java/example/TaskService.java", "TaskService", "submit task");
        RetrievalBundle bundle = bundle(List.of(requirement), List.of(code));

        EvidenceRegistry first = EvidenceRegistry.from(bundle);
        EvidenceRegistry second = EvidenceRegistry.from(bundle);

        assertThat(first.references()).extracting(EvidenceRef::evidenceId)
                .containsExactly("requirement:req-1", "code:code-1")
                .containsExactlyElementsOf(second.references().stream().map(EvidenceRef::evidenceId).toList());
        assertThat(first.evidenceId(requirement)).contains("requirement:req-1");
        assertThat(first.evidenceId(code)).contains("code:code-1");
        assertThat(first.promptRequirementContext(List.of(requirement), 1000)).contains("[evidenceId=requirement:req-1]");
        assertThat(first.promptCodeContext(List.of(code), 1000)).contains("[evidenceId=code:code-1]");
    }

    @Test
    void contextSliceKeepsOneRepresentativePerModuleAndReportsOmissions() {
        List<ChunkRecord> chunks = new java.util.ArrayList<>();
        for (int module = 0; module < 5; module++) {
            chunks.add(requirement("req-m" + module, "module-" + module + "/spec.html", "模块规则内容"));
        }
        EvidenceRegistry registry = EvidenceRegistry.from(bundle(chunks, List.of()));

        EvidenceRegistry.ContextSlice slice = registry.requirementContextSlice(chunks, 150);

        assertThat(slice.coveredModules()).isEqualTo(5);
        assertThat(slice.omittedChunks()).isGreaterThan(0);
        assertThat(slice.text()).contains("module-0");
        assertThat(slice.includedChunks() + slice.omittedChunks()).isEqualTo(5);
    }

    @Test
    void contextSliceWithoutBudgetKeepsEverything() {
        ChunkRecord moduleA = requirement("req-a1", "module-a/spec.html", "A 模块规则");
        ChunkRecord moduleB = requirement("req-b1", "module-b/spec.html", "B 模块规则");
        EvidenceRegistry registry = EvidenceRegistry.from(bundle(List.of(moduleA, moduleB), List.of()));

        EvidenceRegistry.ContextSlice slice = registry.requirementContextSlice(List.of(moduleA, moduleB), -1);

        assertThat(slice.omittedChunks()).isZero();
        assertThat(slice.includedChunks()).isEqualTo(2);
        assertThat(slice.coveredModules()).isEqualTo(2);
    }

    @Test
    void removesPrivateAbsolutePathsAndBoundsExcerpts() {
        String longText = "a".repeat(500);
        ChunkRecord requirement = requirement("req-1", "/private/workspace/requirements.md", longText);
        CodeChunk code = code("code-1", "C:/private/workspace/TaskService.java", "TaskService", longText);

        EvidenceRegistry registry = EvidenceRegistry.from(bundle(List.of(requirement), List.of(code)));

        assertThat(registry.references()).allSatisfy(reference -> {
            assertThat(reference.source()).doesNotContain("private/workspace");
            assertThat(reference.excerpt().length()).isLessThanOrEqualTo(361);
        });
        assertThat(registry.references()).extracting(EvidenceRef::source)
                .containsExactly("requirements.md", "TaskService.java");
    }

    @Test
    void replacesUnsafeOrRepeatedChunkIdsWithRequestScopedStableIds() {
        ChunkRecord unsafe = requirement("unsafe id", "docs/a.md", "rule a");
        ChunkRecord first = requirement("duplicate", "docs/b.md", "rule b");
        ChunkRecord second = requirement("duplicate", "docs/b.md", "rule b");
        ChunkRecord third = requirement("duplicate", "docs/b.md", "rule b");
        List<ChunkRecord> chunks = List.of(unsafe, first, second, third);

        EvidenceRegistry registry = EvidenceRegistry.from(bundle(chunks, List.of()));

        assertThat(registry.references()).hasSize(4);
        assertThat(registry.references()).extracting(EvidenceRef::evidenceId)
                .allMatch(id -> id.startsWith("requirement:"))
                .doesNotHaveDuplicates();
        assertThat(chunks).allSatisfy(chunk -> assertThat(registry.evidenceId(chunk)).isPresent());
        assertThat(registry.promptRequirementContext(chunks, 4000))
                .contains(registry.evidenceId(first).orElseThrow())
                .contains(registry.evidenceId(second).orElseThrow())
                .contains(registry.evidenceId(third).orElseThrow());
    }

    @Test
    void excludesEvidenceThatExplicitlyBelongsToAnotherDocumentVersionOrProject() {
        ChunkRecord current = requirement("req-current", "docs/current.md", "current rule");
        ChunkRecord stale = new ChunkRecord("req-stale", "doc-a", "0.9", "docs/stale.md", "section-a",
                "stale rule", "stale rule", "hash-stale", 1, 1);
        ChunkRecord foreignDocument = new ChunkRecord("req-foreign", "doc-b", "1.0", "docs/foreign.md", "section-a",
                "foreign rule", "foreign rule", "hash-foreign", 1, 1);
        CodeChunk localCode = code("code-local", "src/main/java/example/LocalService.java", "LocalService", "local");
        CodeChunk foreignCode = new CodeChunk("code-foreign", "project-b", "commit-b",
                "src/main/java/example/ForeignService.java", "class", "ForeignService",
                1, 20, "foreign", "hash-foreign-code");

        EvidenceRegistry registry = EvidenceRegistry.from(bundle(
                List.of(current, stale, foreignDocument), List.of(localCode, foreignCode)));

        assertThat(registry.references()).extracting(EvidenceRef::evidenceId)
                .containsExactly("requirement:req-current", "code:code-local");
        assertThat(registry.evidenceId(stale)).isEmpty();
        assertThat(registry.evidenceId(foreignDocument)).isEmpty();
        assertThat(registry.evidenceId(foreignCode)).isEmpty();
    }

    private RetrievalBundle bundle(List<ChunkRecord> requirements, List<CodeChunk> code) {
        return new RetrievalBundle("query", RetrievalProfile.DEVELOPMENT_PLAN, "project-a", "doc-a", "1.0",
                requirements, code);
    }

    private ChunkRecord requirement(String id, String filename, String text) {
        return new ChunkRecord(id, "doc-a", "1.0", filename, "section-a", text, text,
                "hash-" + filename, 1, 1);
    }

    private CodeChunk code(String id, String path, String symbol, String text) {
        return new CodeChunk(id, "project-a", "commit-a", path, "class", symbol,
                10, 30, text, "hash-" + path);
    }
}
