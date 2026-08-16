package com.example.requirementrag.evolution.experience;

import com.example.requirementrag.config.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 检索经验异步采集器。
 * <p>
 * 默认关闭；开启后通过有界队列异步落盘，队列满时丢弃低价值成功事件，
 * 任何写入失败都不影响在线检索链路。
 * </p>
 */
@Component
public class RetrievalExperienceRecorder {

    private static final Logger log = LoggerFactory.getLogger(RetrievalExperienceRecorder.class);

    private final FileRetrievalExperienceStore store;
    private final boolean enabled;
    private final boolean recordingEnabled;
    private final double successSampleRate;
    private final double failureSampleRate;
    private final boolean queryPreviewEnabled;
    private final ExecutorService executor;
    private final MeterRegistry meterRegistry;
    private final AtomicLong dropped = new AtomicLong();

    public RetrievalExperienceRecorder(RagProperties properties, ObjectMapper objectMapper,
                                       MeterRegistry meterRegistry) {
        RagProperties.Evolution evolution = properties.evolution();
        this.enabled = evolution.enabled();
        this.recordingEnabled = evolution.experienceRecordingEnabled();
        this.successSampleRate = evolution.successSampleRate();
        this.failureSampleRate = evolution.failureSampleRate();
        this.queryPreviewEnabled = evolution.queryPreviewEnabled();
        this.store = new FileRetrievalExperienceStore(objectMapper,
                Path.of(evolution.experienceRootPath()), evolution.retentionDays());
        this.meterRegistry = meterRegistry;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(evolution.queueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "evolution-experience-recorder");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardPolicy());
    }

    /** 异步记录一条经验事件；未启用或采样未命中时直接返回。 */
    public void recordAsync(RetrievalExperience experience) {
        if (!enabled || !recordingEnabled || experience == null) {
            return;
        }
        if (!sample(experience)) {
            return;
        }
        RetrievalExperience sanitized = sanitize(experience);
        try {
            executor.execute(() -> {
                store.append(sanitized);
                meterRegistry.counter("nexus.evolution.experience.written").increment();
            });
        } catch (RuntimeException exception) {
            dropped.incrementAndGet();
            meterRegistry.counter("nexus.evolution.experience.dropped").increment();
            log.debug("Experience recording queue is full; dropping event {}", experience.experienceId());
        }
    }

    /** 读取当前保留期内全部经验事件，供失败挖掘和实验回放使用。 */
    public List<RetrievalExperience> readAll() {
        return store.readAll();
    }

    /** 清理过期经验文件，供调度任务调用。 */
    public void cleanExpired() {
        if (!enabled || !recordingEnabled) {
            return;
        }
        store.cleanExpired();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private boolean sample(RetrievalExperience experience) {
        boolean failure = "FAILED".equals(experience.outcomeStatus())
                || "DEGRADED".equals(experience.outcomeStatus())
                || !experience.warningCodes().isEmpty();
        double rate = failure ? failureSampleRate : successSampleRate;
        return rate >= 1.0 || Math.random() < rate;
    }

    private RetrievalExperience sanitize(RetrievalExperience experience) {
        if (queryPreviewEnabled) {
            return experience;
        }
        return new RetrievalExperience(
                experience.schemaVersion(), experience.experienceId(), experience.occurredAt(),
                experience.projectId(), experience.documentId(), experience.version(),
                experience.queryHash(), null, experience.retrievalProfile(),
                experience.selectedStrategy(), experience.executedStrategies(), experience.hops(),
                experience.hopDetails(), experience.candidates(), experience.finalRanking(), experience.evidenceIds(),
                experience.reflectionVerdict(), experience.reflectionReasonCode(),
                experience.outcomeStatus(), experience.warningCodes(), experience.diagnostics(),
                experience.latencyMs(), experience.tokenCost(), experience.degradedStages(),
                sanitizeFeedback(experience.feedback()), experience.policyVersion(),
                experience.configHash(), experience.indexVersion(), experience.datasetVersion());
    }

    private RetrievalExperience.UserFeedback sanitizeFeedback(RetrievalExperience.UserFeedback feedback) {
        if (feedback == null || queryPreviewEnabled) {
            return feedback;
        }
        return new RetrievalExperience.UserFeedback(feedback.rating(), feedback.rejectedEvidenceIds(),
                null, feedback.feedbackAt());
    }
}
