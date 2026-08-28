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
                        1_000, 1_800, 1_000_000, 400, true, 5_000));
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
    void failedSaveCannotOverwriteExistingSuccessfulAnnotation() {
        // #2（Review 高）：并发构建下，成功构建先保存并发布 active 代际后，另一个失败构建不得
        // insert or replace 覆盖同一 annotationId——否则代际行还在但 listActive() 因标注被覆盖成
        // FAILED 返回空（active 代际引用的成功标注被失败记录破坏）。
        SQLiteRequirementSemanticStore store = store();
        store.save(record("p1", "doc", "5.1", "file.md|p|0", "hash-1", "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED));
        store.save(record("p1", "doc", "5.1", "file.md|p|0", "hash-1", "model-a", "v1", "v1",
                ExtractionStatus.FAILED));

        // 成功标注保留，失败写被拒绝；幂等查询仍返回 SUCCEEDED。
        assertThat(store.existsSuccessful("p1", "doc", "5.1", "file.md|p|0", "hash-1",
                "model-a", "v1", "v1")).isTrue();
        assertThat(store.countByStatus("p1", "doc", "5.1", ExtractionStatus.SUCCEEDED)).isEqualTo(1);
        assertThat(store.countByStatus("p1", "doc", "5.1", ExtractionStatus.FAILED)).isEqualTo(0);
    }

    @Test
    void successfulSaveStillOverwritesExistingFailedAnnotation() {
        // 重试语义不能破坏：失败标注可被后续成功覆盖（这是 retryFailedOnly 的核心路径）。
        SQLiteRequirementSemanticStore store = store();
        store.save(record("p1", "doc", "5.1", "file.md|p|0", "hash-1", "model-a", "v1", "v1",
                ExtractionStatus.FAILED));
        store.save(record("p1", "doc", "5.1", "file.md|p|0", "hash-1", "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED));

        assertThat(store.existsSuccessful("p1", "doc", "5.1", "file.md|p|0", "hash-1",
                "model-a", "v1", "v1")).isTrue();
        assertThat(store.countByStatus("p1", "doc", "5.1", ExtractionStatus.FAILED)).isEqualTo(0);
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
        return build(projectId, revision, active, SemanticBuildStatus.SUCCESS);
    }

    private SemanticBuildRecord build(String projectId, String revision, boolean active,
                                      SemanticBuildStatus status) {
        return buildForDocument(projectId, "doc", revision, active, status);
    }

    private SemanticBuildRecord buildForDocument(String projectId, String documentId, String revision,
                                                 boolean active, SemanticBuildStatus status) {
        return new SemanticBuildRecord(
                SQLiteRequirementSemanticStore.buildId(projectId, documentId, "5.1", revision,
                        "model-a", "v1", "v1"),
                projectId, documentId, "5.1", revision, "model-a", "v1", "v1",
                status, 2, 1, 1, 0, List.of("SEMANTIC_BUDGET_MODEL_CALLS"),
                Instant.now(), Instant.now(), active);
    }

    @Test
    void savingActiveBuildDeactivatesPreviousGeneration() {
        SQLiteRequirementSemanticStore store = store();
        store.recordBuildRun(build("p1", "rev-1", true), List.of());
        store.recordBuildRun(build("p1", "rev-2", true), List.of());

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
        // active 由构建状态推导：SUCCESS 必然发布，"SUCCESS 但不接管"是矛盾状态；
        // 不接管的真实场景是 PARTIAL_FAILURE / FAILED（无论调用方传什么 active）。
        SQLiteRequirementSemanticStore store = store();
        store.recordBuildRun(build("p1", "rev-1", true), List.of());
        store.recordBuildRun(build("p1", "rev-2", false, SemanticBuildStatus.PARTIAL_FAILURE), List.of());

        assertThat(store.activeSourceRevision("p1", "doc", "5.1")).contains("rev-1");
    }

    @Test
    void failedRerunWithSameBuildIdKeepsActiveSuccessGeneration() {
        SQLiteRequirementSemanticStore store = store();
        store.save(record("p1", "doc", "5.1", "file.md|p|0", "hash-1", "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED));
        SemanticBuildRecord success = build("p1", "rev-1", true);
        store.recordBuildRun(success, List.of(
                new RequirementSemanticModels.SemanticBuildInput("file.md|p|0", null, "hash-1")));

        // 同一确定性 buildId（同输入/模型/Prompt/Schema）重跑，模型临时失败 → PARTIAL_FAILURE。
        SemanticBuildRecord failedRerun = build("p1", "rev-1", false, SemanticBuildStatus.PARTIAL_FAILURE);
        store.recordBuildRun(failedRerun, List.of());

        // 原成功代际未被覆盖：active 仍是 SUCCESS，输入集合未被清空。
        assertThat(store.activeSourceRevision("p1", "doc", "5.1")).contains("rev-1");
        assertThat(store.listActive("p1", "doc", "5.1", 10, 0)).hasSize(1);
        Optional<SemanticBuildRecord> generation = store.findBuild(success.buildId());
        assertThat(generation).isPresent();
        assertThat(generation.get().active()).isTrue();
        assertThat(generation.get().buildStatus()).isEqualTo(SemanticBuildStatus.SUCCESS);
        // 轮询看到的是最新一次执行（PARTIAL_FAILURE），但生效代际仍是成功构建。
        Optional<RequirementSemanticModels.SemanticBuildStatusView> latest = store.latestBuild("p1", "doc", "5.1");
        assertThat(latest).isPresent();
        assertThat(latest.get().latestRunStatus()).isEqualTo(SemanticBuildStatus.PARTIAL_FAILURE);
        assertThat(latest.get().runId()).isNotBlank();
        assertThat(latest.get().generationActive()).isTrue();
        assertThat(latest.get().activeGenerationStatus()).isEqualTo(SemanticBuildStatus.SUCCESS);
    }

    @Test
    void successRerunOfSameBuildIdRefreshesGenerationAndKeepsActive() {
        SQLiteRequirementSemanticStore store = store();
        store.recordBuildRun(build("p1", "rev-1", true), List.of());

        store.recordBuildRun(build("p1", "rev-1", true), List.of());

        assertThat(store.activeSourceRevision("p1", "doc", "5.1")).contains("rev-1");
        assertThat(store.findBuild(SQLiteRequirementSemanticStore.buildId(
                        "p1", "doc", "5.1", "rev-1", "model-a", "v1", "v1")))
                .isPresent().get().extracting(SemanticBuildRecord::active).isEqualTo(true);
        assertThat(store.latestBuild("p1", "doc", "5.1")).isPresent()
                .get()
                .satisfies(view -> {
                    assertThat(view.latestRunStatus()).isEqualTo(SemanticBuildStatus.SUCCESS);
                    assertThat(view.generationActive()).isTrue();
                    assertThat(view.activeGenerationStatus()).isEqualTo(SemanticBuildStatus.SUCCESS);
                });
    }

    @Test
    void firstFailedRunCreatesInactiveGenerationVisibleInLatestBuild() {
        SQLiteRequirementSemanticStore store = store();
        store.recordBuildRun(build("p1", "rev-1", false, SemanticBuildStatus.FAILED), List.of());

        assertThat(store.activeSourceRevision("p1", "doc", "5.1")).isEmpty();
        assertThat(store.listActive("p1", "doc", "5.1", 10, 0)).isEmpty();
        Optional<RequirementSemanticModels.SemanticBuildStatusView> latest = store.latestBuild("p1", "doc", "5.1");
        assertThat(latest).isPresent();
        assertThat(latest.get().latestRunStatus()).isEqualTo(SemanticBuildStatus.FAILED);
        assertThat(latest.get().generationActive()).isFalse();
        // 无 active 代际：activeGeneration* 三字段为 null，而不是回退到最新 run 的代际状态。
        assertThat(latest.get().activeGenerationBuildId()).isNull();
        assertThat(latest.get().activeGenerationSourceRevision()).isNull();
        assertThat(latest.get().activeGenerationStatus()).isNull();
    }

    @Test
    void duplicateActiveGenerationsAreRepairedAndGuardedByUniqueIndex() throws Exception {
        // 脏数据来自"无唯一索引的旧版库"（并发/中断产生同范围两条 active）：
        // 新版初始化先修复（保留每组最新一条 active），再建 partial unique index 防止再次出现。
        Path db = tempDir.resolve("dup-active.db");
        String rev1BuildId = "build:legacy-rev-1";
        String rev2BuildId = "build:legacy-rev-2";
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    create table requirement_semantic_build(
                      build_id text primary key,
                      project_id text not null,
                      document_id text not null,
                      requirement_version text not null,
                      source_revision text not null,
                      model text not null,
                      prompt_version text not null,
                      schema_version text not null,
                      build_status text not null,
                      total_chunks integer not null default 0,
                      skipped_chunks integer not null default 0,
                      completed_chunks integer not null default 0,
                      failed_chunks integer not null default 0,
                      warnings_json text not null default '[]',
                      started_at text,
                      finished_at text,
                      active integer not null default 0
                    )
                    """);
            statement.executeUpdate("insert into requirement_semantic_build(build_id, project_id, document_id,"
                    + " requirement_version, source_revision, model, prompt_version, schema_version,"
                    + " build_status, active) values('" + rev1BuildId + "', 'p1', 'doc', '5.1', 'rev-1',"
                    + " 'model-a', 'v1', 'v1', 'SUCCESS', 1)");
            statement.executeUpdate("insert into requirement_semantic_build(build_id, project_id, document_id,"
                    + " requirement_version, source_revision, model, prompt_version, schema_version,"
                    + " build_status, active) values('" + rev2BuildId + "', 'p1', 'doc', '5.1', 'rev-2',"
                    + " 'model-a', 'v1', 'v1', 'SUCCESS', 1)");
        }

        // 新版 Store 初始化：修复保留最新插入的一条（rev-2，rowid 更大），rev-1 置 0。
        RequirementSemanticProperties properties = new RequirementSemanticProperties(true, false, false, false,
                db.toString(), null, "requirement-semantic-v1", "v1", 12_000, 30, 30, 30, 30, 20, 30, 2,
                1_000, 1_800, 1_000_000, 400, true, 5_000);
        SQLiteRequirementSemanticStore store = new SQLiteRequirementSemanticStore(objectMapper, properties);
        assertThat(store.activeSourceRevision("p1", "doc", "5.1")).contains("rev-2");

        // partial unique index：同范围再激活另一条被数据库拒绝。
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             java.sql.Statement statement = connection.createStatement()) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> statement.executeUpdate(
                            "update requirement_semantic_build set active=1 where build_id='" + rev1BuildId + "'"))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test
    void nonSuccessRunCannotBeRecordedAsActiveEvenIfCallerPassesActiveTrue() {
        // Store 层强制生命周期约束：FAILED/PARTIAL_FAILURE 即使误传 active=true 也不得发布为线上代际。
        SQLiteRequirementSemanticStore store = store();
        String activeBuildId = SQLiteRequirementSemanticStore.buildId(
                "p1", "doc", "5.1", "rev-1", "model-a", "v1", "v1");
        store.recordBuildRun(build("p1", "rev-1", true), List.of());

        // 恶意/错误的调用：FAILED + active=true。
        SemanticBuildStatus failed = SemanticBuildStatus.FAILED;
        String failedBuildId = SQLiteRequirementSemanticStore.buildId(
                "p1", "doc", "5.1", "rev-2", "model-a", "v1", "v1");
        store.recordBuildRun(new SemanticBuildRecord(failedBuildId, "p1", "doc", "5.1",
                "rev-2", "model-a", "v1", "v1", failed, 1, 0, 0, 1, List.of(),
                Instant.now(), Instant.now(), true), List.of());

        // 原 SUCCESS 代际仍是在线的唯一 active；FAILED 代际行存在但 active=0。
        assertThat(store.activeSourceRevision("p1", "doc", "5.1")).contains("rev-1");
        assertThat(store.findBuild(failedBuildId)).isPresent().get()
                .satisfies(record -> assertThat(record.active()).isFalse());
        assertThat(store.findBuild(activeBuildId)).isPresent().get()
                .satisfies(record -> assertThat(record.active()).isTrue());
    }

    @Test
    void legacyDuplicateActiveCleanupPrefersNewestFinishedAtOverRowid() throws Exception {
        // rowid 大但 finished_at 旧的构建（导入/重写场景）不应挤掉 rowid 小但时间更新的 active。
        Path db = tempDir.resolve("cleanup-order.db");
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    create table requirement_semantic_build(
                      build_id text primary key,
                      project_id text not null,
                      document_id text not null,
                      requirement_version text not null,
                      source_revision text not null,
                      model text not null,
                      prompt_version text not null,
                      schema_version text not null,
                      build_status text not null,
                      total_chunks integer not null default 0,
                      skipped_chunks integer not null default 0,
                      completed_chunks integer not null default 0,
                      failed_chunks integer not null default 0,
                      warnings_json text not null default '[]',
                      started_at text,
                      finished_at text,
                      active integer not null default 0
                    )
                    """);
            // 先插入时间更新的 active（rowid 小），后插入时间更旧的 active（rowid 大）。
            statement.executeUpdate("insert into requirement_semantic_build(build_id, project_id, document_id,"
                    + " requirement_version, source_revision, model, prompt_version, schema_version,"
                    + " build_status, finished_at, active) values('build:newer-time', 'p1', 'doc', '5.1',"
                    + " 'rev-newer', 'model-a', 'v1', 'v1', 'SUCCESS', '2026-06-01T00:00:00Z', 1)");
            statement.executeUpdate("insert into requirement_semantic_build(build_id, project_id, document_id,"
                    + " requirement_version, source_revision, model, prompt_version, schema_version,"
                    + " build_status, finished_at, active) values('build:older-time', 'p1', 'doc', '5.1',"
                    + " 'rev-older', 'model-a', 'v1', 'v1', 'SUCCESS', '2026-01-01T00:00:00Z', 1)");
        }

        RequirementSemanticProperties properties = new RequirementSemanticProperties(true, false, false, false,
                db.toString(), null, "requirement-semantic-v1", "v1", 12_000, 30, 30, 30, 30, 20, 30, 2,
                1_000, 1_800, 1_000_000, 400, true, 5_000);
        SQLiteRequirementSemanticStore store = new SQLiteRequirementSemanticStore(objectMapper, properties);

        // 保留 finished_at 更新的 rev-newer，而不是 rowid 更大的 rev-older。
        assertThat(store.activeSourceRevision("p1", "doc", "5.1")).contains("rev-newer");
        assertThat(store.findBuild("build:older-time")).isPresent().get()
                .satisfies(record -> assertThat(record.active()).isFalse());
    }

    @Test
    void migrationDeactivatesNonSuccessActiveRows() throws Exception {
        // 补充（后端存储审查）：旧版本/异常路径可能留下 FAILED/PARTIAL_FAILURE 且 active=1 的脏行，
        // 不清理会造成 activeSourceRevision() 认为代际有效、而 listActive/聚合检索拒绝它，状态自相矛盾。
        Path db = tempDir.resolve("invalid-active.db");
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    create table requirement_semantic_build(
                      build_id text primary key,
                      project_id text not null,
                      document_id text not null,
                      requirement_version text not null,
                      source_revision text not null,
                      model text not null,
                      prompt_version text not null,
                      schema_version text not null,
                      build_status text not null,
                      total_chunks integer not null default 0,
                      skipped_chunks integer not null default 0,
                      completed_chunks integer not null default 0,
                      failed_chunks integer not null default 0,
                      warnings_json text not null default '[]',
                      started_at text,
                      finished_at text,
                      active integer not null default 0
                    )
                    """);
            // 旧脏数据：FAILED/PARTIAL_FAILURE 却被标成 active=1。
            statement.executeUpdate("insert into requirement_semantic_build(build_id, project_id, document_id,"
                    + " requirement_version, source_revision, model, prompt_version, schema_version,"
                    + " build_status, finished_at, active) values('build:failed-active', 'p1', 'doc', '5.1',"
                    + " 'rev-failed', 'model-a', 'v1', 'v1', 'FAILED', '2026-06-01T00:00:00Z', 1)");
            statement.executeUpdate("insert into requirement_semantic_build(build_id, project_id, document_id,"
                    + " requirement_version, source_revision, model, prompt_version, schema_version,"
                    + " build_status, finished_at, active) values('build:partial-active', 'p1', 'doc', '5.1',"
                    + " 'rev-partial', 'model-a', 'v1', 'v1', 'PARTIAL_FAILURE', '2026-06-01T00:00:00Z', 1)");
            // 正常 SUCCESS active 行不受影响。
            statement.executeUpdate("insert into requirement_semantic_build(build_id, project_id, document_id,"
                    + " requirement_version, source_revision, model, prompt_version, schema_version,"
                    + " build_status, finished_at, active) values('build:success-active', 'p1', 'doc', '5.1',"
                    + " 'rev-success', 'model-a', 'v1', 'v1', 'SUCCESS', '2026-06-02T00:00:00Z', 1)");
        }

        RequirementSemanticProperties properties = new RequirementSemanticProperties(true, false, false, false,
                db.toString(), null, "requirement-semantic-v1", "v1", 12_000, 30, 30, 30, 30, 20, 30, 2,
                1_000, 1_800, 1_000_000, 400, true, 5_000);
        SQLiteRequirementSemanticStore store = new SQLiteRequirementSemanticStore(objectMapper, properties);

        // FAILED/PARTIAL_FAILURE 的 active 行被停用，SUCCESS 的 active 行保留为唯一 active 代际。
        assertThat(store.activeSourceRevision("p1", "doc", "5.1")).contains("rev-success");
        assertThat(store.findBuild("build:failed-active")).isPresent().get()
                .satisfies(record -> assertThat(record.active()).isFalse());
        assertThat(store.findBuild("build:partial-active")).isPresent().get()
                .satisfies(record -> assertThat(record.active()).isFalse());
        assertThat(store.findBuild("build:success-active")).isPresent().get()
                .satisfies(record -> assertThat(record.active()).isTrue());
    }

    @Test
    void failedNewGenerationDoesNotMaskOldActiveGenerationInStatusView() {
        // rev-1 SUCCESS active；rev-2（新输入）FAILED inactive：
        // latestBuild 必须报出最新 run FAILED，同时 activeGeneration* 指向仍在线的 rev-1 成功代际。
        SQLiteRequirementSemanticStore store = store();
        String activeBuildId = SQLiteRequirementSemanticStore.buildId(
                "p1", "doc", "5.1", "rev-1", "model-a", "v1", "v1");
        store.recordBuildRun(build("p1", "rev-1", true), List.of());
        store.recordBuildRun(build("p1", "rev-2", false, SemanticBuildStatus.FAILED), List.of());

        Optional<RequirementSemanticModels.SemanticBuildStatusView> latest = store.latestBuild("p1", "doc", "5.1");
        assertThat(latest).isPresent();
        assertThat(latest.get().latestRunStatus()).isEqualTo(SemanticBuildStatus.FAILED);
        assertThat(latest.get().generationActive()).isFalse();
        // 真正 active 的代际是 rev-1（SUCCESS），不是最新 run 的 rev-2（FAILED）。
        assertThat(latest.get().activeGenerationBuildId()).isEqualTo(activeBuildId);
        assertThat(latest.get().activeGenerationSourceRevision()).isEqualTo("rev-1");
        assertThat(latest.get().activeGenerationStatus()).isEqualTo(SemanticBuildStatus.SUCCESS);
        assertThat(store.activeSourceRevision("p1", "doc", "5.1")).contains("rev-1");
    }

    @Test
    void latestBuildReturnsLastInsertedRunEvenWithinSameMillisecond() {
        // 同一毫秒内连续写入多个 run：epoch 相同时按 rowid 决胜，latestBuild 必须返回最后插入的 run。
        SQLiteRequirementSemanticStore store = store();
        store.recordBuildRun(build("p1", "rev-1", false, SemanticBuildStatus.FAILED), List.of());
        store.recordBuildRun(build("p1", "rev-1", false, SemanticBuildStatus.PARTIAL_FAILURE), List.of());
        store.recordBuildRun(build("p1", "rev-1", true, SemanticBuildStatus.SUCCESS), List.of());

        Optional<RequirementSemanticModels.SemanticBuildStatusView> latest = store.latestBuild("p1", "doc", "5.1");
        assertThat(latest).isPresent();
        assertThat(latest.get().latestRunStatus()).isEqualTo(SemanticBuildStatus.SUCCESS);
        assertThat(latest.get().runId()).isNotEqualTo("migration:" + latest.get().buildId());
    }

    @Test
    void migratesLegacyBuildRowsIntoRunHistoryOnUpgrade() throws Exception {
        // 旧版库（run 表引入前）：只有 build 表且已有一条 SUCCESS active 构建。
        Path legacyDb = tempDir.resolve("legacy-upgrade.db");
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + legacyDb);
             java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    create table requirement_semantic_build(
                      build_id text primary key,
                      project_id text not null,
                      document_id text not null,
                      requirement_version text not null,
                      source_revision text not null,
                      model text not null,
                      prompt_version text not null,
                      schema_version text not null,
                      build_status text not null,
                      total_chunks integer not null default 0,
                      skipped_chunks integer not null default 0,
                      completed_chunks integer not null default 0,
                      failed_chunks integer not null default 0,
                      warnings_json text not null default '[]',
                      started_at text,
                      finished_at text,
                      active integer not null default 0
                    )
                    """);
            statement.executeUpdate("""
                    insert into requirement_semantic_build(
                      build_id, project_id, document_id, requirement_version, source_revision, model,
                      prompt_version, schema_version, build_status, total_chunks, skipped_chunks,
                      completed_chunks, failed_chunks, warnings_json, started_at, finished_at, active)
                    values('build:legacy-1', 'p1', 'doc', '5.1', 'rev-legacy', 'model-a', 'v1', 'v1',
                      'SUCCESS', 3, 1, 2, 0, '[]',
                      '2026-01-01T00:00:00Z', '2026-01-01T00:10:00Z', 1)
                    """);
        }

        // 新版 Store 初始化：创建 run 表并回填迁移 run 记录。
        SQLiteRequirementSemanticStore store = new SQLiteRequirementSemanticStore(objectMapper,
                new RequirementSemanticProperties(true, false, false, false,
                        legacyDb.toString(), null,
                        "requirement-semantic-v1", "v1", 12_000, 30, 30, 30, 30, 20, 30, 2,
                        1_000, 1_800, 1_000_000, 400, true, 5_000));

        Optional<RequirementSemanticModels.SemanticBuildStatusView> latest = store.latestBuild("p1", "doc", "5.1");
        assertThat(latest).isPresent();
        assertThat(latest.get().runId()).startsWith("migration:");
        assertThat(latest.get().buildId()).isEqualTo("build:legacy-1");
        assertThat(latest.get().latestRunStatus()).isEqualTo(SemanticBuildStatus.SUCCESS);
        assertThat(latest.get().generationActive()).isTrue();
        assertThat(latest.get().activeGenerationStatus()).isEqualTo(SemanticBuildStatus.SUCCESS);
        // 升级不破坏 active 查询。
        assertThat(store.activeSourceRevision("p1", "doc", "5.1")).contains("rev-legacy");

        // 再次初始化（重启）：迁移幂等，不重复回填同一条 run。
        SQLiteRequirementSemanticStore restarted = new SQLiteRequirementSemanticStore(objectMapper,
                new RequirementSemanticProperties(true, false, false, false,
                        legacyDb.toString(), null,
                        "requirement-semantic-v1", "v1", 12_000, 30, 30, 30, 30, 20, 30, 2,
                        1_000, 1_800, 1_000_000, 400, true, 5_000));
        Optional<RequirementSemanticModels.SemanticBuildStatusView> afterRestart =
                restarted.latestBuild("p1", "doc", "5.1");
        assertThat(afterRestart).isPresent();
        assertThat(afterRestart.get().runId()).startsWith("migration:");
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
        store.recordBuildRun(build, List.of(
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
        store.recordBuildRun(build, List.of(
                new RequirementSemanticModels.SemanticBuildInput("file.md|p|0", null, "hash-1"),
                new RequirementSemanticModels.SemanticBuildInput("file.md|p|1", null, "hash-2")));

        List<SemanticAnnotationRecord> active = store.listActive("p1", "doc", "5.1", 10, 0);

        assertThat(active).hasSize(1);
        assertThat(active.get(0).sourceChunkId()).isEqualTo("file.md|p|0");
        // 没有 active 构建时检索层拿不到任何可消费记录。
        assertThat(store.listActive("p2", "doc", "5.1", 10, 0)).isEmpty();
    }

    @Test
    void listActiveByProjectVersionWithBuildsReturnsBuildIdsReadInSameQuery() {
        SQLiteRequirementSemanticStore store = store();
        store.save(record("p1", "doc", "5.1", "file.md|p|0", "hash-1", "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED));
        store.save(record("p1", "doc", "5.1", "file.md|p|1", "hash-2", "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED));
        // 单一 active 代际包含两块输入：两块标注与同一个 build id 在同一条查询中被读出。
        SemanticBuildRecord build = build("p1", "rev-1", true);
        store.recordBuildRun(build, List.of(
                new RequirementSemanticModels.SemanticBuildInput("file.md|p|0", null, "hash-1"),
                new RequirementSemanticModels.SemanticBuildInput("file.md|p|1", null, "hash-2")));

        SQLiteRequirementSemanticStore.ActiveAnnotations loaded =
                store.listActiveByProjectVersionWithBuilds("p1", "5.1", 10, "");

        assertThat(loaded.annotations()).hasSize(2);
        // 与标注同一条查询读到的 build ids：评测上下文必须绑定实际读取的代际，而非另行查询的状态。
        assertThat(loaded.buildIds()).containsExactly(build.buildId());
        // 旧签名仍然可用且语义一致。
        assertThat(store.listActiveByProjectVersion("p1", "5.1", 10, ""))
                .hasSize(2);
    }

    @Test
    void listActiveByProjectVersionWithBuildsEmptyWhenNoActiveGeneration() {
        SQLiteRequirementSemanticStore store = store();
        store.save(record("p1", "doc", "5.1", "file.md|p|0", "hash-1", "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED));
        // 没有 active 构建：无标注可见，也无代际可绑定。
        SQLiteRequirementSemanticStore.ActiveAnnotations loaded =
                store.listActiveByProjectVersionWithBuilds("p1", "5.1", 10, "");
        assertThat(loaded.annotations()).isEmpty();
        assertThat(loaded.buildIds()).isEmpty();
    }

    @Test
    void listActiveByProjectVersionWithBuildsReturnsAllActiveBuildIdsEvenWhenZeroAnnotationsMatch() {
        // 高（Review #1）：零命中时 buildIds 必须仍包含被查询的 active 代际——
        // 否则前端无法区分"语义源未参与"（空）与"参与但零命中"（应非空）。
        SQLiteRequirementSemanticStore store = store();
        store.save(record("p1", "doc", "5.1", "file.md|p|0", "hash-1", "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED));
        SemanticBuildRecord build = build("p1", "rev-1", true);
        store.recordBuildRun(build, List.of(
                new RequirementSemanticModels.SemanticBuildInput("file.md|p|0", null, "hash-1")));

        // 查询词不匹配任何标注 → 零命中标注，但 active 代际确实被查询了。
        SQLiteRequirementSemanticStore.ActiveAnnotations loaded =
                store.listActiveByProjectVersionWithBuilds("p1", "5.1", 10, "不存在的查询词");

        assertThat(loaded.annotations()).isEmpty();
        // buildIds 仍包含被查询的 active 代际——区分"参与但零命中"与"未参与"。
        assertThat(loaded.buildIds()).containsExactly(build.buildId());
    }

    @Test
    void listActiveByProjectVersionWithBuildsFlagsTruncationWhenMoreThanLimitMatch() {
        // 中（vaxr M2）：超过上限时必须由存储层显式报告截断（truncated=true）——
        // 此前适配器用 limit+1 探测，在恰好命中 SQL 硬顶 20000 时探测被钳制吞掉，
        // 后段相关候选静默丢失却无警告。
        SQLiteRequirementSemanticStore store = store();
        store.save(record("p1", "doc", "5.1", "file.md|p|0", "hash-1", "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED));
        store.save(record("p1", "doc", "5.1", "file.md|p|1", "hash-2", "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED));
        store.save(record("p1", "doc", "5.1", "file.md|p|2", "hash-3", "model-a", "v1", "v1",
                ExtractionStatus.SUCCEEDED));
        SemanticBuildRecord build = build("p1", "rev-1", true);
        store.recordBuildRun(build, List.of(
                new RequirementSemanticModels.SemanticBuildInput("file.md|p|0", null, "hash-1"),
                new RequirementSemanticModels.SemanticBuildInput("file.md|p|1", null, "hash-2"),
                new RequirementSemanticModels.SemanticBuildInput("file.md|p|2", null, "hash-3")));

        // limit=2 < 命中 3 → truncated=true，且返回恰 2 条（不把探测行泄漏进结果）。
        SQLiteRequirementSemanticStore.ActiveAnnotations truncated =
                store.listActiveByProjectVersionWithBuilds("p1", "5.1", 2, "");
        assertThat(truncated.truncated()).isTrue();
        assertThat(truncated.annotations()).hasSize(2);

        // limit=3 恰好等于命中数 → truncated=false（不误报截断）。
        SQLiteRequirementSemanticStore.ActiveAnnotations exact =
                store.listActiveByProjectVersionWithBuilds("p1", "5.1", 3, "");
        assertThat(exact.truncated()).isFalse();
        assertThat(exact.annotations()).hasSize(3);
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

    // ---------------- 聚合构建状态（项目/版本范围） ----------------

    @Test
    void aggregateCountsActiveSuccessAcrossDocuments() {
        // 语义检索按 projectId+version 召回该版本全部 active 文档；聚合状态必须按同样范围统计，
        // 前端状态条才与检索范围一致（两文档 active SUCCESS → 覆盖 2 文档）。
        SQLiteRequirementSemanticStore store = store();
        store.recordBuildRun(buildForDocument("p1", "doc-a", "rev-1", true, SemanticBuildStatus.SUCCESS), List.of());
        store.recordBuildRun(buildForDocument("p1", "doc-b", "rev-1", true, SemanticBuildStatus.SUCCESS), List.of());

        var aggregate = store.aggregateBuildStatus("p1", "5.1", true, false, true);

        assertThat(aggregate).isPresent();
        assertThat(aggregate.get().hasActiveGeneration()).isTrue();
        assertThat(aggregate.get().activeDocumentCount()).isEqualTo(2);
        assertThat(aggregate.get().activeDocumentIds()).containsExactlyInAnyOrder("doc-a", "doc-b");
        assertThat(aggregate.get().activeBuildIds()).hasSize(2);
        assertThat(aggregate.get().latestRunStatus()).isEqualTo(SemanticBuildStatus.SUCCESS);
        // 检索开关透传：聚合视图携带配置态，前端可区分“配置关闭”与“召回质量差”。
        assertThat(aggregate.get().candidateRetrievalEnabled()).isTrue();
        assertThat(aggregate.get().normativeRetrievalEnabled()).isFalse();
    }

    @Test
    void aggregateOnlyCountsSuccessActiveDocuments() {
        // 文档 A active SUCCESS + 文档 B 最新 FAILED：FAILED 不能发布为 active 代际，
        // 聚合“覆盖文档数”与“active 代际身份”只能包含 A——否则前端会误报“全部已构建”。
        SQLiteRequirementSemanticStore store = store();
        store.recordBuildRun(buildForDocument("p1", "doc-a", "rev-1", true, SemanticBuildStatus.SUCCESS), List.of());
        store.recordBuildRun(buildForDocument("p1", "doc-b", "rev-1", false, SemanticBuildStatus.FAILED), List.of());

        var aggregate = store.aggregateBuildStatus("p1", "5.1", true, true, true);

        assertThat(aggregate).isPresent();
        assertThat(aggregate.get().hasActiveGeneration()).isTrue();
        assertThat(aggregate.get().activeDocumentCount()).isEqualTo(1);
        assertThat(aggregate.get().activeDocumentIds()).containsExactly("doc-a");
        // 最新一次执行（跨文档）是 FAILED 文档的 run——最新执行状态如实反映，不隐藏失败。
        assertThat(aggregate.get().latestRunStatus()).isEqualTo(SemanticBuildStatus.FAILED);
    }

    @Test
    void aggregateOrderIsStableByFinishTimeAndRowid() {
        // active 文档/buildId 顺序必须稳定（评测键依赖排序拼接）；按 finished_at 降序 + rowid 兜底，
        // 同一次写入序列反复查询应得到相同顺序。
        SQLiteRequirementSemanticStore store = store();
        store.recordBuildRun(buildForDocument("p1", "doc-b", "rev-1", true, SemanticBuildStatus.SUCCESS), List.of());
        store.recordBuildRun(buildForDocument("p1", "doc-a", "rev-1", true, SemanticBuildStatus.SUCCESS), List.of());

        var first = store.aggregateBuildStatus("p1", "5.1", true, true, true);
        var second = store.aggregateBuildStatus("p1", "5.1", true, true, true);

        assertThat(first).isPresent();
        assertThat(first.get().activeDocumentIds()).isEqualTo(second.get().activeDocumentIds());
        assertThat(first.get().activeBuildIds()).isEqualTo(second.get().activeBuildIds());
        assertThat(first.get().activeDocumentIds()).containsExactlyInAnyOrder("doc-a", "doc-b");
        assertThat(first.get().activeBuildIds()).doesNotHaveDuplicates();
    }

    @Test
    void aggregateEmptyWhenNoBuildRuns() {
        // 没有任何 run 的版本：Optional.empty()，Controller 层映射为空体（前端“未构建”提示）。
        SQLiteRequirementSemanticStore store = store();

        assertThat(store.aggregateBuildStatus("p1", "5.1", true, true, true)).isEmpty();
    }

    @Test
    void aggregateScopedByProjectAndVersion() {
        // 聚合按 projectId+version 隔离：另一项目/版本的 run 不得混入。
        SQLiteRequirementSemanticStore store = store();
        store.recordBuildRun(buildForDocument("p1", "doc-a", "rev-1", true, SemanticBuildStatus.SUCCESS), List.of());
        store.recordBuildRun(buildForDocument("p2", "doc-a", "rev-1", true, SemanticBuildStatus.SUCCESS), List.of());
        store.recordBuildRun(buildForDocument("p1", "doc-a", "rev-1", true, SemanticBuildStatus.SUCCESS), List.of());
        // 版本维度：buildForDocument 固定 version 5.1，这里用不同版本字段直接构造不同 record。
        SemanticBuildRecord otherVersion = new SemanticBuildRecord(
                SQLiteRequirementSemanticStore.buildId("p1", "doc-a", "6.0", "rev-1",
                        "model-a", "v1", "v1"),
                "p1", "doc-a", "6.0", "rev-1", "model-a", "v1", "v1",
                SemanticBuildStatus.SUCCESS, 2, 1, 1, 0, List.of(),
                Instant.now(), Instant.now(), true);
        store.recordBuildRun(otherVersion, List.of());

        var aggregate = store.aggregateBuildStatus("p1", "5.1", true, true, true);

        assertThat(aggregate).isPresent();
        assertThat(aggregate.get().activeDocumentCount()).isEqualTo(1);
        assertThat(aggregate.get().activeDocumentIds()).containsExactly("doc-a");
        assertThat(aggregate.get().requirementVersion()).isEqualTo("5.1");
    }

    @Test
    void aggregateReadsLatestRunAndActiveGenerationFromSingleSnapshot() {
        // 补充（后端存储审查）：latestRun* 与 activeBuildIds 必须来自同一只读事务——否则构建恰好在
        // 两次查询之间提交时，最新执行来自运行 A、active 集合来自运行 B，聚合状态自相矛盾
        // （例如 latestRunStatus=FAILED 却列出最新成功代际，或反之）。
        SQLiteRequirementSemanticStore store = store();
        // run 1：构建失败且无 active（rev-fail 无成功代际）。
        store.recordBuildRun(buildForDocument("p1", "doc-a", "rev-fail", false, SemanticBuildStatus.FAILED),
                List.of());
        // run 2：构建成功发布 active（rev-ok）。
        store.recordBuildRun(buildForDocument("p1", "doc-a", "rev-ok", true, SemanticBuildStatus.SUCCESS),
                List.of());

        var aggregate = store.aggregateBuildStatus("p1", "5.1", true, true, true);

        assertThat(aggregate).isPresent();
        // latest run 与 active 代际来自同一次快照：最新执行已是 rev-ok 的 SUCCESS，active 集合必须非空；
        // 若两次查询来自不同事务（先查 run 后查 active），状态就可能在 rev-fail 与 rev-ok 之间撕裂。
        assertThat(aggregate.get().latestRunStatus()).isEqualTo(SemanticBuildStatus.SUCCESS);
        assertThat(aggregate.get().latestRunBuildId()).isEqualTo(
                SQLiteRequirementSemanticStore.buildId("p1", "doc-a", "5.1", "rev-ok",
                        "model-a", "v1", "v1"));
        assertThat(aggregate.get().hasActiveGeneration()).isTrue();
        assertThat(aggregate.get().activeBuildIds()).containsExactly(
                SQLiteRequirementSemanticStore.buildId("p1", "doc-a", "5.1", "rev-ok",
                        "model-a", "v1", "v1"));
    }
}
