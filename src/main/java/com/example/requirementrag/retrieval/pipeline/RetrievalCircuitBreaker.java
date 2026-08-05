package com.example.requirementrag.retrieval.pipeline;

import com.example.requirementrag.config.RagProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/** 轻量级按阶段熔断器：依赖故障期间阻止重复调用，降低对下游的压力。 */
@Component
public class RetrievalCircuitBreaker {
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    private final int failureThreshold;
    private final long openMillis;

    @Autowired
    public RetrievalCircuitBreaker(RagProperties properties) {
        this(properties.retrieval().resolvedCircuitBreakerFailureThreshold(),
                Duration.ofMillis(properties.retrieval().resolvedCircuitBreakerOpenMs()));
    }

    RetrievalCircuitBreaker(int failureThreshold, Duration openDuration) {
        this.failureThreshold = Math.max(0, failureThreshold);
        this.openMillis = Math.max(0, openDuration == null ? 0 : openDuration.toMillis());
    }

    /**
     * 查询指定阶段是否允许发起调用；熔断期已过的阶段自动恢复。
     *
     * @param stage 阶段标识
     * @return 允许调用返回 true；熔断生效中（未到期）返回 false
     */
    public boolean allow(String stage) {
        if (failureThreshold == 0 || openMillis == 0) return true;
        State state = states.get(stage);
        if (state == null) return true;
        if (state.openUntilMillis > 0 && state.openUntilMillis <= System.currentTimeMillis()) {
            states.remove(stage, state);
            return true;
        }
        return state.openUntilMillis == 0;
    }

    /** 阶段调用成功时清除该阶段的失败计数与熔断状态。 */
    public void success(String stage) {
        states.remove(stage);
    }

    /**
     * 记录一次失败；连续失败次数达到阈值时熔断该阶段 openMillis 毫秒。
     *
     * @param stage 阶段标识
     */
    public void failure(String stage) {
        if (failureThreshold == 0 || openMillis == 0) return;
        states.compute(stage, (ignored, previous) -> {
            int failures = previous == null ? 1 : previous.failures + 1;
            long openUntil = failures >= failureThreshold ? System.currentTimeMillis() + openMillis : 0;
            return new State(failures, openUntil);
        });
    }

    private record State(int failures, long openUntilMillis) {
    }
}
