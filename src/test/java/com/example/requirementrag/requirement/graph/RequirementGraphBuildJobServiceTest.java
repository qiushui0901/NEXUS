package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.requirement.graph.RequirementGraphModels.BuildJob;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.BuildJobState;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.BuildRequest;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphSnapshot;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SnapshotStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequirementGraphBuildJobServiceTest {
    @Test
    void executesBuildAsynchronouslyAndExposesTerminalStatus() throws Exception {
        RequirementGraphBuildService builder = mock(RequirementGraphBuildService.class);
        String database = Files.createTempDirectory("nexus-req-graph-job-").resolve("graph.db").toString();
        RequirementGraphProperties properties = new RequirementGraphProperties(
                true, true, true, database, 20, 30, 20_000, 2, 40, "model", "v1");
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(new ObjectMapper(), properties);
        Instant now = Instant.now();
        when(builder.build(any())).thenReturn(new GraphSnapshot("reqgraph:job", "orders", "requirements", "2.0",
                "source", "model", "v1", SnapshotStatus.REVIEW_REQUIRED, 2, 1, now, now, null));
        RequirementGraphBuildJobService jobs = new RequirementGraphBuildJobService(
                builder, store, properties, new ObjectMapper());
        try {
            BuildJob queued = jobs.start(new BuildRequest("orders", "requirements", "2.0", null));
            BuildJob terminal = queued;
            for (int index = 0; index < 30 && (terminal.state() == BuildJobState.QUEUED
                    || terminal.state() == BuildJobState.RUNNING); index++) {
                Thread.sleep(10);
                terminal = jobs.require(queued.buildId());
            }
            assertThat(terminal.state()).isEqualTo(BuildJobState.SUCCEEDED);
            assertThat(terminal.snapshotId()).isEqualTo("reqgraph:job");
        } finally {
            jobs.shutdown();
        }
    }

    @Test
    void resumesFailedJobWithPersistedSnapshotId() throws Exception {
        RequirementGraphBuildService builder = mock(RequirementGraphBuildService.class);
        String database = Files.createTempDirectory("nexus-req-graph-job-resume-").resolve("graph.db").toString();
        RequirementGraphProperties properties = new RequirementGraphProperties(
                true, true, true, database, 20, 30, 20_000, 2, 40, "model", "v1");
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(new ObjectMapper(), properties);
        when(builder.build(any())).thenThrow(
                new RequirementGraphBuildFailureException("GRAPH_WINDOW_FAILED", "窗口失败", "snapshot-1"));
        RequirementGraphBuildJobService jobs = new RequirementGraphBuildJobService(
                builder, store, properties, new ObjectMapper());
        try {
            BuildJob queued = jobs.start(new BuildRequest("orders", "requirements", "2.0", null));
            BuildJob failed = await(jobs, queued.buildId(),
                    job -> job.state() == BuildJobState.FAILED);
            assertThat(failed.snapshotId()).isEqualTo("snapshot-1");

            BuildJob resumed = jobs.resume(queued.buildId());
            assertThat(resumed.state()).isEqualTo(BuildJobState.QUEUED);
            assertThat(resumed.snapshotId()).isEqualTo("snapshot-1");
        } finally {
            jobs.shutdown();
        }
    }

    @Test
    void cancelledJobPreservesSnapshotIdAndIsResumable() throws Exception {
        RequirementGraphBuildService builder = mock(RequirementGraphBuildService.class);
        String database = Files.createTempDirectory("nexus-req-graph-job-cancel-").resolve("graph.db").toString();
        RequirementGraphProperties properties = new RequirementGraphProperties(
                true, true, true, database, 20, 30, 20_000, 2, 40, "model", "v1");
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(new ObjectMapper(), properties);
        java.util.concurrent.CountDownLatch snapshotSaved = new java.util.concurrent.CountDownLatch(1);
        when(builder.build(any())).thenAnswer(invocation -> {
            String buildId = invocation.getArgument(0, BuildRequest.class).buildId();
            // 模拟“快照已创建但构建未完成”：
            Instant created = Instant.now();
            store.saveSnapshot(new GraphSnapshot("snapshot-1", "orders", "requirements", "2.0",
                    "source", "model", "v1", SnapshotStatus.PARTIAL_FAILED, 0, 0, created, created, null,
                    2, "v1", 1.0, 1, 1, 0, 0, buildId, null, null, null));
            snapshotSaved.countDown();
            awaitCancelIgnoreInterrupt(store, buildId);
            throw new RequirementGraphBuildFailureException("GRAPH_WINDOW_FAILED", "窗口失败", "snapshot-1");
        });
        RequirementGraphBuildJobService jobs = new RequirementGraphBuildJobService(
                builder, store, properties, new ObjectMapper());
        try {
            BuildJob queued = jobs.start(new BuildRequest("orders", "requirements", "2.0", null));
            BuildJob running = await(jobs, queued.buildId(), job -> job.state() == BuildJobState.RUNNING);
            assertThat(snapshotSaved.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            BuildJob cancelled = jobs.cancel(queued.buildId());
            assertThat(cancelled.state()).isEqualTo(BuildJobState.CANCELLED);

            BuildJob terminal = await(jobs, queued.buildId(),
                    job -> job.state() == BuildJobState.CANCELLED && job.snapshotId() != null);
            assertThat(terminal.snapshotId()).isEqualTo("snapshot-1");

            BuildJob resumed = jobs.resume(queued.buildId());
            assertThat(resumed.state()).isEqualTo(BuildJobState.QUEUED);
            assertThat(resumed.snapshotId()).isEqualTo("snapshot-1");
        } finally {
            jobs.shutdown();
        }
    }

    @Test
    void jobSurvivesAcrossServiceInstances() throws Exception {
        RequirementGraphBuildService builder = mock(RequirementGraphBuildService.class);
        String database = Files.createTempDirectory("nexus-req-graph-job-restart-").resolve("graph.db").toString();
        RequirementGraphProperties properties = new RequirementGraphProperties(
                true, true, true, database, 20, 30, 20_000, 2, 40, "model", "v1");
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(new ObjectMapper(), properties);
        when(builder.build(any())).thenThrow(
                new RequirementGraphBuildFailureException("GRAPH_WINDOW_FAILED", "窗口失败", "snapshot-1"));
        RequirementGraphBuildJobService first = new RequirementGraphBuildJobService(
                builder, store, properties, new ObjectMapper());
        String buildId;
        try {
            BuildJob queued = first.start(new BuildRequest("orders", "requirements", "2.0", null));
            buildId = queued.buildId();
            await(first, buildId, job -> job.state() == BuildJobState.FAILED);
        } finally {
            first.shutdown();
        }

        RequirementGraphBuildJobService second = new RequirementGraphBuildJobService(
                builder, store, properties, new ObjectMapper());
        try {
            BuildJob loaded = second.require(buildId);
            assertThat(loaded.state()).isEqualTo(BuildJobState.FAILED);
            assertThat(loaded.snapshotId()).isEqualTo("snapshot-1");
        } finally {
            second.shutdown();
        }
    }

    @Test
    void resumesQueuedJobWithoutSnapshotByRequeueingFreshBuild() throws Exception {
        RequirementGraphBuildService builder = mock(RequirementGraphBuildService.class);
        String database = Files.createTempDirectory("nexus-req-graph-job-requeue-").resolve("graph.db").toString();
        RequirementGraphProperties properties = new RequirementGraphProperties(
                true, true, true, database, 20, 30, 20_000, 2, 40, "model", "v1");
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(new ObjectMapper(), properties);
        Instant now = Instant.now();
        store.saveBuildJob(new SQLiteRequirementGraphStore.StoredBuildJob(
                new BuildJob("graph-job:no-snapshot", null, "orders", "requirements", "2.0",
                        BuildJobState.FAILED, 0, 0, "GRAPH_JOB_INTERRUPTED", "中断", now, null, now),
                "{\"projectId\":\"orders\",\"documentId\":\"requirements\",\"requirementVersion\":\"2.0\"}",
                false, null));
        RequirementGraphBuildJobService jobs = new RequirementGraphBuildJobService(
                builder, store, properties, new ObjectMapper());
        try {
            BuildJob resumed = jobs.resume("graph-job:no-snapshot");
            assertThat(resumed.state()).isEqualTo(BuildJobState.QUEUED);
            assertThat(resumed.snapshotId()).isNull();
        } finally {
            jobs.shutdown();
        }
    }

    @Test
    void runtimeExceptionDuringCancelKeepsCancelledState() throws Exception {
        RequirementGraphBuildService builder = mock(RequirementGraphBuildService.class);
        String database = Files.createTempDirectory("nexus-req-graph-job-cancel-runtime-").resolve("graph.db").toString();
        RequirementGraphProperties properties = new RequirementGraphProperties(
                true, true, true, database, 20, 30, 20_000, 2, 40, "model", "v1");
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(new ObjectMapper(), properties);
        when(builder.build(any())).thenAnswer(invocation -> {
            String buildId = invocation.getArgument(0, BuildRequest.class).buildId();
            awaitCancelIgnoreInterrupt(store, buildId);
            throw new RuntimeException("模型调用普通异常");
        });
        RequirementGraphBuildJobService jobs = new RequirementGraphBuildJobService(
                builder, store, properties, new ObjectMapper());
        try {
            BuildJob queued = jobs.start(new BuildRequest("orders", "requirements", "2.0", null));
            BuildJob running = await(jobs, queued.buildId(), job -> job.state() == BuildJobState.RUNNING);
            jobs.cancel(queued.buildId());

            BuildJob terminal = await(jobs, queued.buildId(), job -> job.state() == BuildJobState.CANCELLED);
            assertThat(terminal.state()).isEqualTo(BuildJobState.CANCELLED);
        } finally {
            jobs.shutdown();
        }
    }

    private static BuildJob await(RequirementGraphBuildJobService jobs, String buildId,
                                  java.util.function.Predicate<BuildJob> predicate) throws Exception {
        BuildJob job = null;
        for (int index = 0; index < 200; index++) {
            job = jobs.require(buildId);
            if (predicate.test(job)) return job;
            Thread.sleep(10);
        }
        return job;
    }

    private static void awaitCancelIgnoreInterrupt(SQLiteRequirementGraphStore store, String buildId)
            throws Exception {
        for (int index = 0; index < 200; index++) {
            var stored = store.loadBuildJob(buildId);
            if (stored.isPresent() && stored.get().cancelRequested()) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("等待构建取消标记超时: " + buildId);
    }
}