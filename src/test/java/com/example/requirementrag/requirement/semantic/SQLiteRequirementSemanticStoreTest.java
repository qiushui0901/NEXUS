package com.example.requirementrag.requirement.semantic;

import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.ClaimStatus;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.ExtractionStatus;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationRecord;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationResult;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildRecord;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildStatus;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SQLiteRequirementSemanticStoreTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SQLiteRequirementSemanticStore store() {
        return new SQLiteRequirementSemanticStore(objectMapper,
                new RequirementSemanticProperties(true, false, false, false,
                        tempDir.resolve("semantic.db").toString(), null,
                        "requirement-semantic-v1", "v1", 12_000, 30, 30, 30, 30, 20, 30, 2,
                        1_000, 1_800, 1_000_000, 400, true));
    }

    private SemanticAnnotationRecord record(String projectId, String documentId, String version,
                                            String chunkId, String contentHash, String model,
                                            String promptVersion, String schemaVersion,
                                            ExtractionStatus status) {
        String annotationId = SQLiteRequirementSemanticStore.annotationId(projectId, documentId,
                version, chunkId, contentHash, model, promptVersion, schemaVersion);
        SemanticAnnotationResult result = status == ExtractionStatus.SUCCEEDED
                ? new SemanticAnnotationResult(
                List.of(new RequirementSemanticModels.SemanticEntity("成长基金", "FEATURE",
                        List.of("成长基金玩法"), "EXPLICIT", "成长基金")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), true)
                : null;
        return new SemanticAnnotationRecord(annotationId, projectId, documentId, version,
                "rev-1", chunkId, "parent-1", null, 0, 0, 0, "file.md", 0, contentHash,
                "玩家达到30级后开放成长基金。", "摘要", "语义文本", result, model, promptVersion,
                schemaVersion, status, ClaimStatus.CANDIDATE, null, 1, 1, 10, 10,
                status == ExtractionStatus.FAILED ? SemanticErrorCode.MODEL_TIMEOUT : null,
                Instant.now(), Instant.now());
    }

    @Test
    void repeatedSaveOfSameIdempotentKeyKeepsSingleRecord() {
        SQLiteRequirementSemanticStore store = store();
        SemanticAnnotationRecord record = record("p1", "doc", "5.1", "file.md|p|0", "hash-1",
                "model-a", "v1", "v1", ExtractionStatus.SUCCEEDED);

        store.save(record);
        store.save(record);

        assertThat(store.countByStatus("p1", "doc", "5.1", ExtractionStatus.SUCCEEDED)).isEqualTo(1);
        assertThat(store.countChildren(record.annotationId(), "requirement_semantic_entity")).isEqualTo(1);
        assertThat(store.existsSuccessful("p1", "doc", "5.1", "file.md|p|0", "hash-1",
                "model-a", "v1", "v1")).isTrue();
    }

    @Test
    void promptVersionChangeCreatesIndependentAnnotation() {
        SQLiteRequirementSemanticStore store = store();
        store.save(record("p1", "doc", "5.1", "file.md|p|0", "hash-1", "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED));
        store.save(record("p1", "doc", "5.1", "file.md|p|0", "hash-1", "model-a", "v2", "v1",
                ExtractionStatus.SUCCEEDED));

        assertThat(store.countByStatus("p1", "doc", "5.1", null)).isEqualTo(2);
        assertThat(store.existsSuccessful("p1", "doc", "5.1", "file.md|p|0", "hash-1",
                "model-a", "v1", "v1")).isTrue();
        assertThat(store.existsSuccessful("p1", "doc", "5.1", "file.md|p|0", "hash-1",
                "model-a", "v2", "v1")).isTrue();
    }

    @Test
    void schemaVersionChangeCreatesIndependentAnnotation() {
        SQLiteRequirementSemanticStore store = store();
        store.save(record("p1", "doc", "5.1", "file.md|p|0", "hash-1", "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED));
        store.save(record("p1", "doc", "5.1", "file.md|p|0", "hash-1", "model-a", "v1", "v2",
                ExtractionStatus.SUCCEEDED));

        assertThat(store.countByStatus("p1", "doc", "5.1", null)).isEqualTo(2);
    }

    @Test
    void contentHashChangeCreatesIndependentAnnotation() {
        SQLiteRequirementSemanticStore store = store();
        store.save(record("p1", "doc", "5.1", "file.md|p|0", "hash-1", "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED));
        store.save(record("p1", "doc", "5.1", "file.md|p|0", "hash-2", "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED));

        assertThat(store.countByStatus("p1", "doc", "5.1", null)).isEqualTo(2);
    }

    @Test
    void failedRecordIsVisibleAndRetryable() {
        SQLiteRequirementSemanticStore store = store();
        store.save(record("p1", "doc", "5.1", "file.md|p|0", "hash-1", "model-a", "v1", "v1",
                ExtractionStatus.FAILED));

        Optional<SemanticAnnotationRecord> existing = store.findExisting("p1", "doc", "5.1",
                "file.md|p|0", "hash-1", "model-a", "v1", "v1");

        assertThat(existing).isPresent();
        assertThat(existing.get().extractionStatus()).isEqualTo(ExtractionStatus.FAILED);
        assertThat(existing.get().errorCode()).isEqualTo(SemanticErrorCode.MODEL_TIMEOUT);
        assertThat(existing.get().result()).isNull();
        assertThat(store.existsSuccessful("p1", "doc", "5.1", "file.md|p|0", "hash-1",
                "model-a", "v1", "v1")).isFalse();
        assertThat(store.list("p1", "doc", "5.1", ExtractionStatus.FAILED, 10, 0)).hasSize(1);
    }

    @Test
    void retryReplacesFailedRecordWithoutDuplicating() {
        SQLiteRequirementSemanticStore store = store();
        store.save(record("p1", "doc", "5.1", "file.md|p|0", "hash-1", "model-a", "v1", "v1",
                ExtractionStatus.FAILED));
        store.save(record("p1", "doc", "5.1", "file.md|p|0", "hash-1", "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED));

        assertThat(store.countByStatus("p1", "doc", "5.1", null)).isEqualTo(1);
        assertThat(store.countByStatus("p1", "doc", "5.1", ExtractionStatus.SUCCEEDED)).isEqualTo(1);
        assertThat(store.countByStatus("p1", "doc", "5.1", ExtractionStatus.FAILED)).isEqualTo(0);
    }

    @Test
    void versionsAndProjectsAreIsolated() {
        SQLiteRequirementSemanticStore store = store();
        store.save(record("p1", "doc", "5.1", "file.md|p|0", "hash-1", "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED));
        store.save(record("p1", "doc", "5.0", "file.md|p|0", "hash-1", "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED));
        store.save(record("p2", "doc", "5.1", "file.md|p|0", "hash-1", "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED));

        assertThat(store.countByStatus("p1", "doc", "5.1", null)).isEqualTo(1);
        assertThat(store.countByStatus("p1", "doc", "5.0", null)).isEqualTo(1);
        assertThat(store.countByStatus("p2", "doc", "5.1", null)).isEqualTo(1);
        assertThat(store.countByStatus("p1", "doc", "4.9", null)).isEqualTo(0);
    }

    @Test
    void roundTripsStructuredResult() {
        SQLiteRequirementSemanticStore store = store();
        store.save(record("p1", "doc", "5.1", "file.md|p|0", "hash-1", "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED));

        Optional<SemanticAnnotationRecord> loaded = store.findExisting("p1", "doc", "5.1",
                "file.md|p|0", "hash-1", "model-a", "v1", "v1");

        assertThat(loaded).isPresent();
        assertThat(loaded.get().result()).isNotNull();
        assertThat(loaded.get().result().entities()).singleElement()
                .satisfies(entity -> assertThat(entity.name()).isEqualTo("成长基金"));
        assertThat(loaded.get().semanticText()).isEqualTo("语义文本");
        assertThat(loaded.get().claimStatus()).isEqualTo(ClaimStatus.CANDIDATE);
    }

    private SemanticBuildRecord build(String projectId, String revision, boolean active) {
        return new SemanticBuildRecord(
                SQLiteRequirementSemanticStore.buildId(projectId, "doc", "5.1", revision,
                        "model-a", "v1", "v1"),
                projectId, "doc", "5.1", revision, "model-a", "v1", "v1",
                SemanticBuildStatus.SUCCESS, 2, 1, 1, 0, List.of("SEMANTIC_BUDGET_MODEL_CALLS"),
                Instant.now(), Instant.now(), active);
    }

    @Test
    void savingActiveBuildDeactivatesPreviousGeneration() {
        SQLiteRequirementSemanticStore store = store();
        store.saveBuild(build("p1", "rev-1", true));
        store.saveBuild(build("p1", "rev-2", true));

        assertThat(store.activeSourceRevision("p1", "doc", "5.1")).contains("rev-2");
        Optional<SemanticBuildRecord> first = store.findBuild(
                SQLiteRequirementSemanticStore.buildId("p1", "doc", "5.1", "rev-1",
                        "model-a", "v1", "v1"));
        assertThat(first).isPresent();
        assertThat(first.get().active()).isFalse();
        assertThat(first.get().buildStatus()).isEqualTo(SemanticBuildStatus.SUCCESS);
        assertThat(first.get().warnings()).containsExactly("SEMANTIC_BUDGET_MODEL_CALLS");
    }

    @Test
    void inactiveBuildDoesNotChangeActiveRevision() {
        SQLiteRequirementSemanticStore store = store();
        store.saveBuild(build("p1", "rev-1", true));
        store.saveBuild(build("p1", "rev-2", false));

        assertThat(store.activeSourceRevision("p1", "doc", "5.1")).contains("rev-1");
    }

    @Test
    void alignSourceRevisionRemovedAndReplacedByBuildInputFiltering() {
        SQLiteRequirementSemanticStore store = store();
        store.save(record("p1", "doc", "5.1", "file.md|p|0", "hash-1", "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED));
        store.save(record("p1", "doc", "5.1", "file.md|p|1", "hash-2", "model-a", "v1", "v1",
                ExtractionStatus.FAILED));
        store.save(record("p1", "doc", "5.1", "file.md|p|2", "hash-3", "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED));
        // 当前 active 构建只包含 p0 与 p1 的输入：p2（已删除）不应被重新激活。
        SemanticBuildRecord build = build("p1", "rev-new", true);
        store.saveBuild(build);
        store.saveBuildInputs(build.buildId(), List.of(
                new RequirementSemanticModels.SemanticBuildInput("file.md|p|0", null, "hash-1"),
                new RequirementSemanticModels.SemanticBuildInput("file.md|p|1", null, "hash-2")));

        List<SemanticAnnotationRecord> active = store.listActive("p1", "doc", "5.1", 10, 0);

        // 只暴露仍在当前构建输入中的成功标注；p2 已从输入删除，不再可见。
        assertThat(active).extracting(SemanticAnnotationRecord::sourceChunkId)
                .containsExactly("file.md|p|0");
    }

    @Test
    void listActiveReturnsOnlySuccessfulRecordsOfActiveBuildInput() {
        SQLiteRequirementSemanticStore store = store();
        store.save(record("p1", "doc", "5.1", "file.md|p|0", "hash-1", "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED));
        store.save(record("p1", "doc", "5.1", "file.md|p|1", "hash-2", "model-a", "v1", "v1",
                ExtractionStatus.FAILED));
        SemanticBuildRecord build = build("p1", "rev-1", true);
        store.saveBuild(build);
        store.saveBuildInputs(build.buildId(), List.of(
                new RequirementSemanticModels.SemanticBuildInput("file.md|p|0", null, "hash-1"),
                new RequirementSemanticModels.SemanticBuildInput("file.md|p|1", null, "hash-2")));

        List<SemanticAnnotationRecord> active = store.listActive("p1", "doc", "5.1", 10, 0);

        assertThat(active).hasSize(1);
        assertThat(active.get(0).sourceChunkId()).isEqualTo("file.md|p|0");
        // 没有 active 构建时检索层拿不到任何可消费记录。
        assertThat(store.listActive("p2", "doc", "5.1", 10, 0)).isEmpty();
    }

    @Test
    void listOrdersWindowsByWindowIndexAndOffset() {
        SQLiteRequirementSemanticStore store = store();
        store.save(windowRecord("file.md|p|0", "hash-1", "win-2", 2, 400, 800));
        store.save(windowRecord("file.md|p|0", "hash-1", "win-0", 0, 0, 400));
        store.save(windowRecord("file.md|p|0", "hash-1", "win-1", 1, 350, 750));

        List<SemanticAnnotationRecord> ordered = store.list("p1", "doc", "5.1", null, 10, 0);

        assertThat(ordered).extracting(SemanticAnnotationRecord::windowId)
                .containsExactly("win-0", "win-1", "win-2");
        assertThat(ordered).extracting(SemanticAnnotationRecord::windowIndex)
                .containsExactly(0, 1, 2);
    }

    private SemanticAnnotationRecord windowRecord(String chunkId, String contentHash, String windowId,
                                                   int windowIndex, int startOffset, int endOffset) {
        // 同一父块的三个窗口：用不同 contentHash 区分幂等键，其余字段保持一致。
        String hash = contentHash + "|" + windowId;
        return new SemanticAnnotationRecord(
                SQLiteRequirementSemanticStore.annotationId("p1", "doc", "5.1",
                        chunkId + "|" + windowId, hash, "model-a", "v1", "v1"),
                "p1", "doc", "5.1", "rev-1", chunkId + "|" + windowId, "p", windowId,
                windowIndex, startOffset, endOffset,
                "file.md", 0, hash, "窗口文本", "摘要", "语义文本", null, "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED, ClaimStatus.CANDIDATE, null, 1, 1, 1, 1,
                null, Instant.now(), Instant.now());
    }
}
