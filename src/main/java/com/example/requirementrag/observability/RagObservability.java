package com.example.requirementrag.observability;

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

    /** 观测带返回值的操作，记录耗时、成功/失败日志与失败计数。 */
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
            remember(new RagStageEvent(Instant.now(), stage, safe(documentId), safe(version), "failure",
                    elapsedMillis(started), exception.getClass().getSimpleName() + ": " + exception.getMessage()));
            throw exception;
        }
    }

    /** 观测无返回值的操作。 */
    public void observe(String stage, String documentId, String version, Runnable action) {
        observe(stage, documentId, version, () -> { action.run(); return null; });
    }

    /** 记录某阶段处理的条目数量分布。 */
    public void items(String stage, String kind, long count) {
        DistributionSummary.builder("rag.stage.items")
                .description("Number of items entering or leaving a RAG stage")
                .tag("stage", stage).tag("kind", kind).register(meterRegistry).record(count);
    }

    /** 递增业务事件计数器。 */
    public void event(String type) {
        Counter.builder("rag.events").tag("type", type).register(meterRegistry).increment();
    }

    /** 返回最近的 RAG 阶段事件，最新在前。 */
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
