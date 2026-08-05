package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.knowledge.BootstrapState;
import com.example.requirementrag.knowledge.KnowledgeBootstrapService;
import com.example.requirementrag.model.MonitorSnapshot;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.RagChainSnapshot;
import com.example.requirementrag.observability.RagObservability;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统监控 REST 接口：健康状态、引导进度与指标。
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {
    private static final Logger log = LoggerFactory.getLogger(MonitorController.class);

    private final RagProperties properties;
    private final BootstrapState bootstrapState;
    private final KnowledgeBootstrapService bootstrapService;
    private final QdrantHybridStore store;
    private final MeterRegistry meterRegistry;
    private final RagObservability observability;
    private final RestClient appClient;
    private final RestClient ollamaClient;
    private final ProjectAccessGuard accessGuard;
    private final ProjectRegistry projectRegistry;

    /** 注入配置、引导状态、向量库与指标注册表，并初始化健康检查客户端。 */
    public MonitorController(RagProperties properties, BootstrapState bootstrapState,
                               KnowledgeBootstrapService bootstrapService, QdrantHybridStore store,
                               MeterRegistry meterRegistry, RagObservability observability,
                               ProjectAccessGuard accessGuard, ProjectRegistry projectRegistry,
                               @Value("${server.port:8080}") int serverPort,
                               @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl) {
        this.properties = properties;
        this.bootstrapState = bootstrapState;
        this.bootstrapService = bootstrapService;
        this.store = store;
        this.meterRegistry = meterRegistry;
        this.observability = observability;
        this.accessGuard = accessGuard;
        this.projectRegistry = projectRegistry;
        this.appClient = RestClient.builder().baseUrl("http://localhost:" + serverPort).build();
        this.ollamaClient = RestClient.builder().baseUrl(ollamaBaseUrl).build();
    }

    /** 返回应用、Qdrant、Ollama 健康状态及知识库统计快照。 */
    @GetMapping("/status")
    public MonitorSnapshot status(@RequestParam(required = false) String projectId) {
        RagProperties.Knowledge knowledge = resolveKnowledge(projectId);
        String collection = resolveRequirementCollection(projectId);
        long chunkCount = safeCount(collection, knowledge.documentId(), knowledge.version());
        return new MonitorSnapshot(
                applicationStatus(),
                qdrantStatus(),
                ollamaStatus(),
                bootstrapState.status(),
                new MonitorSnapshot.KnowledgeStats(
                        knowledge.documentId(),
                        knowledge.version(),
                        chunkCount,
                        bootstrapState.zipFiles(),
                        bootstrapState.xlsxRows()),
                metricValues());
    }

    /** 解析项目的知识配置，未指定或解析失败时回退到全局默认配置。 */
    private RagProperties.Knowledge resolveKnowledge(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return properties.knowledge();
        }
        try {
            RagProperties.ProjectConfig project = projectRegistry.require(projectId);
            if (project.knowledge() != null) {
                return project.knowledge().toKnowledge();
            }
        }
        catch (IllegalArgumentException exception) {
            log.warn("Monitor status cannot resolve knowledge settings for project {}; using defaults",
                    projectId, exception);
        }
        return properties.knowledge();
    }

    /** 解析项目的需求 collection，解析失败时回退到全局默认 collection。 */
    private String resolveRequirementCollection(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return properties.qdrant().collection();
        }
        try {
            return projectRegistry.resolveRequirementCollection(projectId);
        } catch (IllegalArgumentException exception) {
            log.warn("Monitor status cannot resolve the requirement collection for project {}; using the default",
                    projectId, exception);
            return properties.qdrant().collection();
        }
    }

    /** 异步触发知识库引导导入。projectId 可选，指定后只引导该项目。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping("/bootstrap")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> bootstrap(@RequestParam(required = false) String projectId,
                                         HttpServletRequest httpRequest) {
        accessGuard.requireProjectAccess(httpRequest, projectId);
        if (projectId != null && !projectId.isBlank()) {
            bootstrapService.bootstrapAsync(projectId);
        } else {
            bootstrapService.bootstrapAsync();
        }
        return Map.of("status", "accepted");
    }

    /** 返回 RAG 链路运行状态、最近阶段与 Token 指标。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/rag-chain")
    public RagChainSnapshot ragChain() {
        return new RagChainSnapshot(
                List.of("document.parse", "text.clean", "parent_child.chunk", "content.deduplicate",
                        "qdrant.upsert", "qdrant.scroll", "qdrant.hybrid_search", "bge.rerank",
                        "llm.rerank", "llm.generate.current", "llm.generate.prior"),
                observability.recentEvents(),
                tokenUsage(),
                toolMetrics());
    }

    /** 安全统计版本分块数，Qdrant 不可用时返回 0。 */
    private long safeCount(String collection, String documentId, String version) {
        try {
            return store.countVersion(collection, documentId, version);
        }
        catch (RuntimeException exception) {
            log.warn("Monitor could not count requirement chunks for document {} version {}",
                    documentId, version, exception);
            return 0L;
        }
    }

    /** 探测本应用 actuator 健康状态。 */
    private String applicationStatus() {
        try {
            Map<?, ?> health = appClient.get().uri("/actuator/health").retrieve().body(Map.class);
            Object status = health == null ? null : health.get("status");
            return status == null ? "UNKNOWN" : String.valueOf(status);
        }
        catch (RuntimeException exception) {
            log.debug("Application health probe failed", exception);
            return "DOWN";
        }
    }

    /** 探测 Qdrant 服务是否可达。 */
    private String qdrantStatus() {
        try {
            RestClient.create(properties.qdrant().baseUrl()).get().uri("/collections").retrieve().toBodilessEntity();
            return "UP";
        }
        catch (RuntimeException exception) {
            log.debug("Qdrant health probe failed", exception);
            return "DOWN";
        }
    }

    /** 探测 Ollama 嵌入服务是否可达。 */
    private String ollamaStatus() {
        try {
            ollamaClient.get().uri("/api/tags").retrieve().toBodilessEntity();
            return "UP";
        }
        catch (RuntimeException exception) {
            log.debug("Ollama health probe failed", exception);
            return "DOWN";
        }
    }

    /** 收集 RAG 业务指标当前值。 */
    private Map<String, Double> metricValues() {
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("documentIngested", counterValue("rag.events", "type", "document_ingested"));
        metrics.put("reviewsCompleted", counterValue("rag.events", "type", "review_completed"));
        metrics.put("stageFailures", counterTotal("rag.stage.failures"));
        metrics.put("degradedStages", counterTotal("rag.stage.outcomes", "status", "degraded"));
        metrics.put("failedStages", counterTotal("rag.stage.outcomes", "status", "failed"));
        metrics.put("stageWarnings", counterTotal("rag.stage.warnings"));
        return metrics;
    }

    /** 读取带指定标签的 Counter 值。 */
    private double counterValue(String name, String tagKey, String tagValue) {
        Counter counter = meterRegistry.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0D : counter.count();
    }

    /** 汇总同名 Counter 的所有实例计数。 */
    private double counterTotal(String name) {
        double total = 0D;
        for (Counter counter : meterRegistry.find(name).counters()) {
            total += counter.count();
        }
        return total;
    }

    /** 汇总带指定标签的同名 Counter。 */
    private double counterTotal(String name, String tagKey, String tagValue) {
        double total = 0D;
        for (Counter counter : meterRegistry.find(name).tag(tagKey, tagValue).counters()) {
            total += counter.count();
        }
        return total;
    }

    /** 汇总包含 token 的模型指标。 */
    private Map<String, Double> tokenUsage() {
        Map<String, Double> tokens = new LinkedHashMap<>();
        for (Meter meter : meterRegistry.getMeters()) {
            Meter.Id id = meter.getId();
            String name = id.getName();
            if (!name.toLowerCase().contains("token")) {
                continue;
            }
            double value = 0D;
            for (Measurement measurement : meter.measure()) {
                value += measurement.getValue();
            }
            tokens.put(name + id.getTags(), value);
        }
        return tokens;
    }

    /** 汇总关键工具/阶段调用次数。 */
    private Map<String, Double> toolMetrics() {
        Map<String, Double> tools = new LinkedHashMap<>();
        for (String stage : List.of("qdrant.upsert", "qdrant.scroll", "qdrant.hybrid_search", "bge.rerank",
                "llm.rerank", "llm.generate.current", "llm.generate.prior")) {
            tools.put(stage + ".failures", failureCount(stage));
        }
        return tools;
    }

    /** 统计指定阶段的失败次数。 */
    private double failureCount(String stage) {
        double total = 0D;
        for (Counter counter : meterRegistry.find("rag.stage.failures").tag("stage", stage).counters()) {
            total += counter.count();
        }
        return total;
    }
}
