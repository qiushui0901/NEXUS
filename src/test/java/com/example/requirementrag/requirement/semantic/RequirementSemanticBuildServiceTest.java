package com.example.requirementrag.requirement.semantic;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationInput;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationOutcome;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationRecord;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationResult;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildRequest;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildResult;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildStatus;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticErrorCode;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequirementSemanticBuildServiceTest {
    @TempDir
    Path tempDir;

    private final QdrantHybridStore qdrantStore = mock(QdrantHybridStore.class);
    private final ProjectRegistry projectRegistry = mock(ProjectRegistry.class);
    private final RequirementSemanticAnnotationService annotationService =
            mock(RequirementSemanticAnnotationService.class);

    private SQLiteRequirementSemanticStore store;
    private RequirementSemanticProperties properties;
    private RequirementSemanticBuildService service;

    @BeforeEach
    void setUp() {
        properties = buildProperties(1_000, 12_000);
        store = new SQLiteRequirementSemanticStore(new ObjectMapper(), properties);
        service = new RequirementSemanticBuildService(store, annotationService, qdrantStore,
                projectRegistry, properties, new RequirementSemanticTextComposer());
        when(annotationService.resolvedModel()).thenReturn("test-model");
        when(annotationService.annotate(any(), anyInt())).thenReturn(success());
        when(projectRegistry.resolveRequirementCollection("p1")).thenReturn("col-1");
    }

    private RequirementSemanticProperties buildProperties(int maxModelCalls, int maxInputChars) {
        return buildProperties(maxModelCalls, maxInputChars, 1_000_000);
    }

    private RequirementSemanticProperties buildProperties(int maxModelCalls, int maxInputChars,
                                                           int maxEstimatedTokens) {
        return new RequirementSemanticProperties(true, false, false, false,
                tempDir.resolve("semantic.db").toString(), "test-model",
                "requirement-semantic-v1", "v1", maxInputChars, 30, 30, 30, 30, 20, 30, 0,
                maxModelCalls, 1_800, maxEstimatedTokens, 400, true);
    }

    private ChunkRecord chunk(String parentId, String text) {
        return new ChunkRecord("point-" + parentId, "doc", "5.1", "file.md", parentId,
                text, "child-text", "hash-" + parentId, 0, 0, "成长 / 基金", "成长基金",
                null, null, null);
    }

    private SemanticAnnotationOutcome success() {
        return new SemanticAnnotationOutcome(SemanticAnnotationResult.empty(), null, 1, 1, 5, 10);
    }

    private SemanticAnnotationOutcome failure() {
        return SemanticAnnotationOutcome.failure(SemanticErrorCode.MODEL_TIMEOUT, 1, 1, 5, 10);
    }

    @Test
    void annotatesEachParentOnceAndSkipsUnchangedOnRebuild() {
        when(qdrantStore.scrollVersion("col-1", "doc", "5.1"))
                .thenReturn(List.of(chunk("parent-1", "玩家达到30级后开放成长基金。")));

        SemanticBuildResult first = service.build(new SemanticBuildRequest("p1", "doc", "5.1", null));
        SemanticBuildResult second = service.build(new SemanticBuildRequest("p1", "doc", "5.1", null));

        assertThat(first.completedChunks()).isEqualTo(1);
        assertThat(first.status()).isEqualTo(SemanticBuildStatus.SUCCESS);
        assertThat(second.skippedChunks()).isEqualTo(1);
        assertThat(second.completedChunks()).isEqualTo(0);
        assertThat(second.status()).isEqualTo(SemanticBuildStatus.SUCCESS);
        verify(annotationService, times(1)).annotate(any(), anyInt());
    }

    @Test
    void reportsPartialFailureAndRetriesOnlyFailedItems() {
        when(qdrantStore.scrollVersion("col-1", "doc", "5.1"))
                .thenReturn(List.of(chunk("parent-1", "玩家达到30级后开放成长基金。"),
                        chunk("parent-2", "冷却时间为30秒。")));
        when(annotationService.annotate(any(), anyInt())).thenReturn(success(), failure());

        SemanticBuildResult first = service.build(new SemanticBuildRequest("p1", "doc", "5.1", null));

        assertThat(first.status()).isEqualTo(SemanticBuildStatus.PARTIAL_FAILURE);
        assertThat(first.completedChunks()).isEqualTo(1);
        assertThat(first.failedChunks()).isEqualTo(1);
        assertThat(first.failures()).singleElement()
                .satisfies(failure -> assertThat(failure.errorCode()).isEqualTo("MODEL_TIMEOUT"));

        // 第二次构建：成功的父块跳过，只重跑失败父块。
        when(annotationService.annotate(any(), anyInt())).thenReturn(success());
        SemanticBuildResult retry = service.build(new SemanticBuildRequest("p1", "doc", "5.1",
                null, true));

        assertThat(retry.skippedChunks()).isEqualTo(1);
        assertThat(retry.completedChunks()).isEqualTo(1);
        assertThat(retry.status()).isEqualTo(SemanticBuildStatus.SUCCESS);
        // 第一次 2 次调用 + 重试仅 1 次。
        verify(annotationService, times(3)).annotate(any(), anyInt());
    }

    @Test
    void stopsWhenModelCallBudgetExceeded() {
        service = new RequirementSemanticBuildService(store, annotationService, qdrantStore,
                projectRegistry, buildProperties(1, 12_000), new RequirementSemanticTextComposer());
        when(qdrantStore.scrollVersion("col-1", "doc", "5.1"))
                .thenReturn(List.of(chunk("parent-1", "玩家达到30级后开放成长基金。"),
                        chunk("parent-2", "冷却时间为30秒。")));
        when(annotationService.annotate(any(), anyInt())).thenReturn(success());

        SemanticBuildResult result = service.build(new SemanticBuildRequest("p1", "doc", "5.1", null));

        assertThat(result.completedChunks()).isEqualTo(1);
        // 预算中断后仍有未处理输入：不能伪装成完整成功。
        assertThat(result.status()).isEqualTo(SemanticBuildStatus.PARTIAL_FAILURE);
        assertThat(result.warnings()).contains("SEMANTIC_BUDGET_MODEL_CALLS");
        verify(annotationService, times(1)).annotate(any(), anyInt());
    }

    @Test
    void stopsWhenTokenBudgetWouldBeExceededByNextInput() {
        // 输入约 7~9 token，成功 outcome 计 10 token：第一窗后累计 10，第二窗预检 10+4 越过预算 10。
        service = new RequirementSemanticBuildService(store, annotationService, qdrantStore,
                projectRegistry, buildProperties(1_000, 12_000, 10), new RequirementSemanticTextComposer());
        when(qdrantStore.scrollVersion("col-1", "doc", "5.1"))
                .thenReturn(List.of(chunk("parent-1", "玩家达到30级后开放成长基金。"),
                        chunk("parent-2", "冷却时间为30秒。")));

        SemanticBuildResult result = service.build(new SemanticBuildRequest("p1", "doc", "5.1", null));

        assertThat(result.completedChunks()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(SemanticBuildStatus.PARTIAL_FAILURE);
        assertThat(result.warnings()).contains("SEMANTIC_BUDGET_TOKENS");
    }

    @Test
    void sourceRevisionIsInsensitiveToUnderlyingReturnOrder() {
        ChunkRecord first = chunk("parent-1", "玩家达到30级后开放成长基金。");
        ChunkRecord second = chunk("parent-2", "冷却时间为30秒。");
        when(qdrantStore.scrollVersion("col-1", "doc", "5.1")).thenReturn(List.of(first, second));
        String forward = service.build(new SemanticBuildRequest("p1", "doc", "5.1", null))
                .sourceRevision();

        when(qdrantStore.scrollVersion("col-1", "doc", "5.1")).thenReturn(List.of(second, first));
        String reversed = service.build(new SemanticBuildRequest("p1", "doc", "5.1", null))
                .sourceRevision();

        assertThat(forward).isEqualTo(reversed);
    }

    @Test
    void activeBuildInputControlsWhichReusedRecordsAreVisible() {
        when(qdrantStore.scrollVersion("col-1", "doc", "5.1"))
                .thenReturn(List.of(chunk("parent-1", "玩家达到30级后开放成长基金。")));
        service.build(new SemanticBuildRequest("p1", "doc", "5.1", null));

        // 第二次构建新增 parent-2：parent-1 内容未变被复用，但 active 查询按当前构建输入集合暴露。
        when(qdrantStore.scrollVersion("col-1", "doc", "5.1"))
                .thenReturn(List.of(chunk("parent-1", "玩家达到30级后开放成长基金。"),
                        chunk("parent-2", "冷却时间为30秒。")));
        SemanticBuildResult second = service.build(new SemanticBuildRequest("p1", "doc", "5.1", null));

        assertThat(store.activeSourceRevision("p1", "doc", "5.1")).contains(second.sourceRevision());
        List<SemanticAnnotationRecord> active = store.listActive("p1", "doc", "5.1", 10, 0);
        assertThat(active).hasSize(2);
        assertThat(active).extracting(SemanticAnnotationRecord::sourceChunkId)
                .containsExactlyInAnyOrder("file.md|parent-1|0", "file.md|parent-2|0");
    }

    @Test
    void failedBuildDoesNotActivateItsRevision() {
        when(qdrantStore.scrollVersion("col-1", "doc", "5.1"))
                .thenReturn(List.of(chunk("parent-1", "玩家达到30级后开放成长基金。")));
        when(annotationService.annotate(any(), anyInt())).thenReturn(failure());

        SemanticBuildResult result = service.build(new SemanticBuildRequest("p1", "doc", "5.1", null));

        assertThat(result.status()).isEqualTo(SemanticBuildStatus.FAILED);
        assertThat(store.activeSourceRevision("p1", "doc", "5.1")).isEmpty();
        assertThat(store.listActive("p1", "doc", "5.1", 10, 0)).isEmpty();
    }

    @Test
    void splitsLongParentsIntoWindowsWithoutSilentTailLoss() {
        RequirementSemanticProperties windowProperties = buildProperties(1_000, 1_500);
        SQLiteRequirementSemanticStore windowStore =
                new SQLiteRequirementSemanticStore(new ObjectMapper(), windowProperties);
        service = new RequirementSemanticBuildService(windowStore, annotationService, qdrantStore,
                projectRegistry, windowProperties, new RequirementSemanticTextComposer());
        String longText = "玩家达到30级后开放成长基金，可进入成长基金玩法。".repeat(120);
        when(qdrantStore.scrollVersion("col-1", "doc", "5.1"))
                .thenReturn(List.of(chunk("parent-1", longText)));

        SemanticBuildResult result = service.build(new SemanticBuildRequest("p1", "doc", "5.1", null));

        assertThat(result.totalChunks()).isGreaterThan(1);
        org.mockito.ArgumentCaptor<SemanticAnnotationInput> captor =
                org.mockito.ArgumentCaptor.forClass(SemanticAnnotationInput.class);
        verify(annotationService, times(result.totalChunks())).annotate(captor.capture(), anyInt());
        assertThat(captor.getAllValues()).allSatisfy(input -> {
            assertThat(input.windowId()).isNotBlank();
            assertThat(input.rawText()).isNotBlank();
            assertThat(input.rawText().length()).isLessThanOrEqualTo(1_500);
        });
        // 窗口坐标必须持久化，且按 window_index / start_offset 稳定排序。
        List<SemanticAnnotationRecord> windows = windowStore
                .list("p1", "doc", "5.1", null, 100, 0);
        assertThat(windows).hasSize(result.totalChunks());
        for (int i = 1; i < windows.size(); i++) {
            SemanticAnnotationRecord previous = windows.get(i - 1);
            SemanticAnnotationRecord current = windows.get(i);
            assertThat(current.windowIndex()).isGreaterThanOrEqualTo(previous.windowIndex());
            assertThat(current.startOffset()).isGreaterThan(previous.startOffset());
            assertThat(current.endOffset() - current.startOffset())
                    .isEqualTo(current.rawText().length());
        }
    }

    @Test
    void throwsWhenVersionHasNoParentChunks() {
        when(qdrantStore.scrollVersion("col-1", "doc", "5.1")).thenReturn(List.of());

        assertThatThrownBy(() -> service.build(new SemanticBuildRequest("p1", "doc", "5.1", null)))
                .isInstanceOf(RequirementSemanticException.class)
                .satisfies(exception -> assertThat(((RequirementSemanticException) exception).code())
                        .isEqualTo("SEMANTIC_INPUT_EMPTY"));
    }

    @Test
    void rejectsCollectionOutsideCurrentProject() {
        assertThatThrownBy(() -> service.build(
                new SemanticBuildRequest("p1", "doc", "5.1", "other-collection")))
                .isInstanceOf(RequirementSemanticException.class)
                .satisfies(exception -> assertThat(((RequirementSemanticException) exception).code())
                        .isEqualTo("SEMANTIC_REQUEST_INVALID"));
    }

    @Test
    void rejectsBlankRequestFields() {
        assertThatThrownBy(() -> service.build(new SemanticBuildRequest("", "doc", "5.1", null)))
                .isInstanceOf(RequirementSemanticException.class)
                .satisfies(exception -> assertThat(((RequirementSemanticException) exception).code())
                        .isEqualTo("SEMANTIC_REQUEST_INVALID"));
        assertThatThrownBy(() -> service.build(null))
                .isInstanceOf(RequirementSemanticException.class);
        verify(qdrantStore, times(0)).scrollVersion(anyString(), anyString(), anyString());
    }
}
