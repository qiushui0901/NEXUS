package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.requirement.graph.RequirementGraphModels.BuildJob;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.BuildJobState;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.BuildRequest;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphSnapshot;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SnapshotStatus;
import com.example.requirementrag.requirement.graph.SQLiteRequirementGraphStore.StoredBuildJob;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Persisted, resumable asynchronous wrapper around the deterministic/resumable graph builder. */
@Service
@ConditionalOnProperty(prefix = "app.rag.requirement-graph", name = "enabled", havingValue = "true", matchIfMissing = false)
public class RequirementGraphBuildJobService {
    private static final Duration TERMINAL_JOB_RETENTION = Duration.ofDays(7);

    private final RequirementGraphBuildService buildService;
    private final SQLiteRequirementGraphStore store;
    private final RequirementGraphProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final Map<String, Future<?>> running = new ConcurrentHashMap<>();

    public RequirementGraphBuildJobService(RequirementGraphBuildService buildService,
                                           SQLiteRequirementGraphStore store,
                                           RequirementGraphProperties properties,
                                           ObjectMapper objectMapper) {
        this.buildService = buildService;
        this.store = store;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = Executors.newFixedThreadPool(properties.maxConcurrentWorkers(), runnable -> {
            Thread thread = new Thread(runnable, "nexus-requirement-graph-build");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PostConstruct
    void recoverInterruptedJobs() {
        store.markInterruptedBuildJobs();
        store.deleteTerminalBuildJobsBefore(Instant.now().minus(TERMINAL_JOB_RETENTION));
    }

    public synchronized BuildJob start(BuildRequest request) {
        validate(request);
        String buildId = "graph-job:" + UUID.randomUUID();
        Instant now = Instant.now();
        BuildJob queued = new BuildJob(buildId, request.resumeSnapshotId(), request.projectId(), request.documentId(),
                request.requirementVersion(), BuildJobState.QUEUED, 0, 0, null, null, now, null, null);
        persist(queued, writeRequest(request), false, request.resumeSnapshotId());
        running.put(buildId, executor.submit(() -> run(buildId)));
        cleanupTerminalJobs();
        return queued;
    }

    public BuildJob require(String buildId) {
        return stored(buildId).job();
    }

    public BuildJob resume(String buildId) {
        StoredBuildJob stored = stored(buildId);
        BuildJob previous = stored.job();
        if (previous.state() != BuildJobState.FAILED
                && previous.state() != BuildJobState.PARTIAL_FAILED
                && previous.state() != BuildJobState.CANCELLED) {
            throw new RequirementGraphException("GRAPH_WINDOW_FAILED", "当前构建任务不允许恢复");
        }
        BuildRequest oldRequest = readRequest(stored);
        BuildRequest request;
        if (previous.snapshotId() == null || previous.snapshotId().isBlank()) {
            // 任务尚未创建快照（例如启动恢复标记的 QUEUED 任务）：按原请求重新排队即可，不要求存在旧快照。
            request = new BuildRequest(oldRequest.projectId(), oldRequest.documentId(),
                    oldRequest.requirementVersion(), oldRequest.collection(), null, oldRequest.allowPartial());
        } else {
            request = new BuildRequest(oldRequest.projectId(), oldRequest.documentId(),
                    oldRequest.requirementVersion(), oldRequest.collection(), previous.snapshotId(),
                    oldRequest.allowPartial());
        }
        return start(request);
    }

    public synchronized BuildJob cancel(String buildId) {
        StoredBuildJob stored = stored(buildId);
        BuildJob current = stored.job();
        if (current.state() == BuildJobState.QUEUED || current.state() == BuildJobState.RUNNING) {
            BuildJob cancelled = new BuildJob(current.buildId(), current.snapshotId(), current.projectId(),
                    current.documentId(), current.requirementVersion(), BuildJobState.CANCELLED,
                    current.completedWindows(), current.totalWindows(), "GRAPH_BUILD_CANCELLED", "需求图构建已取消",
                    current.createdAt(), current.startedAt(), Instant.now());
            // 先持久化取消标记，再中断线程，保证运行中任务能看到“已取消”并保留快照 ID。
            persist(cancelled, stored.requestJson(), true, stored.resumeSnapshotId());
            Future<?> future = running.remove(buildId);
            if (future != null) future.cancel(true);
            return cancelled;
        }
        return current;
    }

    private void run(String buildId) {
        StoredBuildJob initial = stored(buildId);
        BuildRequest request = readRequest(initial);
        BuildJob runningJob = new BuildJob(buildId, initial.job().snapshotId(), request.projectId(), request.documentId(),
                request.requirementVersion(), BuildJobState.RUNNING, 0, 0, null, null,
                initial.job().createdAt(), Instant.now(), null);
        persist(runningJob, initial.requestJson(), initial.cancelRequested(), initial.resumeSnapshotId());
        try {
            GraphSnapshot snapshot = buildService.build(new BuildRequest(request.projectId(), request.documentId(),
                    request.requirementVersion(), request.collection(), request.resumeSnapshotId(),
                    request.allowPartial(), buildId));
            BuildJobState state = snapshot.status() == SnapshotStatus.PARTIAL_FAILED
                    ? BuildJobState.PARTIAL_FAILED : BuildJobState.SUCCEEDED;
            StoredBuildJob latest = stored(buildId);
            if (latest.cancelRequested()) {
                BuildJob cancelled = new BuildJob(buildId, snapshot.id(), latest.job().projectId(), latest.job().documentId(),
                        latest.job().requirementVersion(), BuildJobState.CANCELLED,
                        snapshot.succeededWindowCount(), snapshot.windowCount(), "GRAPH_BUILD_CANCELLED",
                        "需求图构建已取消", latest.job().createdAt(), latest.job().startedAt(), Instant.now());
                persist(cancelled, latest.requestJson(), true, latest.resumeSnapshotId());
            } else {
                BuildJob succeeded = new BuildJob(buildId, snapshot.id(), latest.job().projectId(), latest.job().documentId(),
                        latest.job().requirementVersion(), state, snapshot.succeededWindowCount(), snapshot.windowCount(),
                        null, null, latest.job().createdAt(), latest.job().startedAt(), snapshot.updatedAt());
                persist(succeeded, latest.requestJson(), false, latest.resumeSnapshotId());
            }
        } catch (RequirementGraphException exception) {
            StoredBuildJob latest = stored(buildId);
            String snapshotId = exception instanceof RequirementGraphBuildFailureException failure
                    ? failure.snapshotId() : resolveSnapshotId(buildId, latest.job().snapshotId());
            if (latest.cancelRequested()) {
                BuildJob cancelled = new BuildJob(buildId, snapshotId, latest.job().projectId(), latest.job().documentId(),
                        latest.job().requirementVersion(), BuildJobState.CANCELLED,
                        latest.job().completedWindows(), latest.job().totalWindows(), "GRAPH_BUILD_CANCELLED",
                        "需求图构建已取消", latest.job().createdAt(), latest.job().startedAt(), Instant.now());
                persist(cancelled, latest.requestJson(), true, latest.resumeSnapshotId());
            } else {
                BuildJob failed = new BuildJob(buildId, snapshotId, latest.job().projectId(), latest.job().documentId(),
                        latest.job().requirementVersion(), BuildJobState.FAILED,
                        latest.job().completedWindows(), latest.job().totalWindows(), exception.code(),
                        exception.getMessage(), latest.job().createdAt(), latest.job().startedAt(), Instant.now());
                persist(failed, latest.requestJson(), false, latest.resumeSnapshotId());
            }
        } catch (RuntimeException exception) {
            StoredBuildJob latest = stored(buildId);
            if (latest.cancelRequested()) {
                BuildJob cancelled = new BuildJob(buildId, resolveSnapshotId(buildId, latest.job().snapshotId()),
                        latest.job().projectId(), latest.job().documentId(),
                        latest.job().requirementVersion(), BuildJobState.CANCELLED,
                        latest.job().completedWindows(), latest.job().totalWindows(), "GRAPH_BUILD_CANCELLED",
                        "需求图构建已取消", latest.job().createdAt(), latest.job().startedAt(), Instant.now());
                persist(cancelled, latest.requestJson(), true, latest.resumeSnapshotId());
            } else {
                BuildJob failed = new BuildJob(buildId, resolveSnapshotId(buildId, latest.job().snapshotId()),
                        latest.job().projectId(), latest.job().documentId(),
                        latest.job().requirementVersion(), BuildJobState.FAILED,
                        latest.job().completedWindows(), latest.job().totalWindows(), "GRAPH_WINDOW_FAILED",
                        "需求图构建失败", latest.job().createdAt(), latest.job().startedAt(), Instant.now());
                persist(failed, latest.requestJson(), false, latest.resumeSnapshotId());
            }
        } finally {
            running.remove(buildId);
        }
    }

    private void persist(BuildJob job, String requestJson, boolean cancelRequested, String resumeSnapshotId) {
        store.saveBuildJob(new StoredBuildJob(job, requestJson, cancelRequested, resumeSnapshotId));
    }

    private StoredBuildJob stored(String buildId) {
        Optional<StoredBuildJob> stored = store.loadBuildJob(buildId);
        if (stored.isEmpty()) throw new RequirementGraphException("GRAPH_INPUT_EMPTY", "未知需求图构建任务: " + buildId);
        return stored.get();
    }

    private void validate(BuildRequest request) {
        if (request == null || request.projectId() == null || request.projectId().isBlank()
                || request.documentId() == null || request.documentId().isBlank()
                || request.requirementVersion() == null || request.requirementVersion().isBlank()) {
            throw new RequirementGraphException("GRAPH_INPUT_EMPTY", "需求图异步构建请求不完整");
        }
    }

    private String resolveSnapshotId(String buildId, String fallback) {
        if (fallback != null && !fallback.isBlank()) return fallback;
        return store.findSnapshotByBuildId(buildId).map(GraphSnapshot::id).orElse(null);
    }

    private String writeRequest(BuildRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new RequirementGraphException("GRAPH_JOB_SERIALIZATION_FAILED", "构建请求无法持久化");
        }
    }

    private BuildRequest readRequest(StoredBuildJob stored) {
        try {
            return objectMapper.readValue(stored.requestJson(), BuildRequest.class);
        } catch (JsonProcessingException exception) {
            throw new RequirementGraphException("GRAPH_JOB_CORRUPTED", "持久化构建请求损坏，无法恢复");
        }
    }

    private void cleanupTerminalJobs() {
        store.deleteTerminalBuildJobsBefore(Instant.now().minus(TERMINAL_JOB_RETENTION));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}