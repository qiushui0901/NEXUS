package com.example.requirementrag.observability;

import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.model.RagStageEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * RAG 流水线可观测性：阶段计时、指标上报与结构化日志。
 */
@Component
public class RagObservability {
    private static final Logger log = LoggerFactory.getLogger(RagObservability.class);
    private static final int MAX_RECENT_EVENTS = 200;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final List<RagStageEvent> recentEvents = new ArrayList<>();

    /** 注入 Micrometer 观测与指标注册表。 */
    public RagObservability(ObservationRegistry observationRegistry, MeterRegistry meterRegistry) {
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 观测带返回值的操作，记录耗时、成功/失败日志与失败计数。
     *
     * @param stage     阶段标识，用于指标与日志
     * @param documentId 文档 ID，参与日志与最近事件记录
     * @param version   文档版本，参与日志与最近事件记录
     * @param action    待执行的操作
     * @return 操作返回值；操作抛出运行时异常时统计失败后原样抛出
     */
    public <T> T observe(String stage, String documentId, String version, Supplier<T> action) {
        long started = System.nanoTime();
        Observation observation = Observation.createNotStarted("rag.stage", observationRegistry)
                .contextualName("rag " + stage)
                .lowCardinalityKeyValue("rag.stage", stage)
                .highCardinalityKeyValue("rag.document.id", safe(documentId))
                .highCardinalityKeyValue("rag.document.version", safe(version));
        try {
            T result = observation.observe(action);
            log.atInfo().addKeyValue("event", "rag_stage").addKeyValue("stage", stage)
                    .addKeyValue("documentId", safe(documentId)).addKeyValue("version", safe(version))
                    .addKeyValue("status", "success").addKeyValue("durationMs", elapsedMillis(started))
                    .log("RAG stage completed");
            remember(new RagStageEvent(Instant.now(), stage, safe(documentId), safe(version), "success", elapsedMillis(started), ""));
            return result;
        }
        catch (RuntimeException exception) {
            Counter.builder("rag.stage.failures").tag("stage", stage)
                    .tag("exception", exception.getClass().getSimpleName()).register(meterRegistry).increment();
            log.atError().setCause(exception).addKeyValue("event", "rag_stage").addKeyValue("stage", stage)
                    .addKeyValue("documentId", safe(documentId)).addKeyValue("version", safe(version))
                    .addKeyValue("status", "failure").addKeyValue("durationMs", elapsedMillis(started))
                    .log("RAG stage failed");
            remember(new RagStageEvent(Instant.now(), stage, safe(documentId), safe(version), "failed",
                    elapsedMillis(started), exception.getClass().getSimpleName()));
            throw exception;
        }
    }

    /** 观测无返回值的操作。 */
    public void observe(String stage, String documentId, String version, Runnable action) {
        observe(stage, documentId, version, () -> { action.run(); return null; });
    }

    /**
     * 记录已被调用方归类的阶段结果；公开诊断只保存稳定 warning code。
     *
     * @param stage       阶段标识
     * @param documentId  文档 ID
     * @param version     文档版本
     * @param status      阶段最终状态，影响日志级别与失败计数
     * @param durationMs  阶段耗时（毫秒）
     * @param warningCode 稳定告警码，可空；空则不记录告警指标
     * @param failure     导致失败的异常，可空；仅 FAILED 状态用于失败计数与日志原因
     */
    public void outcome(String stage, String documentId, String version, RagOutcomeStatus status,
                        long durationMs, String warningCode, RuntimeException failure) {
        String normalizedStatus = status.name().toLowerCase();
        Counter.builder("rag.stage.outcomes").tag("stage", stage).tag("status", normalizedStatus)
                .register(meterRegistry).increment();
        if (warningCode != null && !warningCode.isBlank()) {
            Counter.builder("rag.stage.warnings").tag("stage", stage).tag("code", warningCode)
                    .register(meterRegistry).increment();
        }
        var event = log.atInfo();
        if (status == RagOutcomeStatus.DEGRADED) {
            event = log.atWarn();
        } else if (status == RagOutcomeStatus.FAILED) {
            Counter.builder("rag.stage.failures").tag("stage", stage)
                    .tag("exception", failure == null ? "Unknown" : failure.getClass().getSimpleName())
                    .register(meterRegistry).increment();
            event = log.atError();
            if (failure != null) {
                event.setCause(failure);
            }
        }
        event.addKeyValue("event", "rag_outcome").addKeyValue("stage", stage)
                .addKeyValue("documentId", safe(documentId)).addKeyValue("version", safe(version))
                .addKeyValue("status", normalizedStatus).addKeyValue("durationMs", durationMs)
                .addKeyValue("warningCode", safe(warningCode)).log("RAG stage outcome");
        remember(new RagStageEvent(Instant.now(), stage, safe(documentId), safe(version), normalizedStatus,
                durationMs, warningCode == null ? "" : warningCode));
    }

    /**
     * 记录某阶段处理的条目数量分布。
     *
     * @param stage 阶段标识
     * @param kind  条目类别（如输入/输出/移除）
     * @param count 条目数量
     */
    public void items(String stage, String kind, long count) {
        DistributionSummary.builder("rag.stage.items")
                .description("Number of items entering or leaving a RAG stage")
                .tag("stage", stage).tag("kind", kind).register(meterRegistry).record(count);
    }

    /** 递增业务事件计数器。 */
    public void event(String type) {
        Counter.builder("rag.events").tag("type", type).register(meterRegistry).increment();
    }

    /**
     * 返回最近的 RAG 阶段事件，最新在前。
     *
     * @return 最近事件的只读副本（最多保留固定窗口条数）
     */
    public synchronized List<RagStageEvent> recentEvents() {
        List<RagStageEvent> copy = new ArrayList<>(recentEvents);
        Collections.reverse(copy);
        return copy;
    }

    /** 计算自 started 起的毫秒耗时。 */
    private long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    /** 空值或空白字符串替换为 unknown，避免指标标签缺失。 */
    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    /** 记录最近阶段事件，保留固定窗口。 */
    private synchronized void remember(RagStageEvent event) {
        recentEvents.add(event);
        if (recentEvents.size() > MAX_RECENT_EVENTS) {
            recentEvents.remove(0);
        }
    }
}
