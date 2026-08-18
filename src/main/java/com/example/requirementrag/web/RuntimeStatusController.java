package com.example.requirementrag.web;

import com.example.requirementrag.code.CodeQdrantStore;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.wiki.WikiRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/** 为平台首页提供有界、可容忍失败的系统状态快照。 */
@RestController
@RequestMapping("/api/runtime")
public class RuntimeStatusController {

    private final RagProperties properties;
    private final ProjectRegistry projectRegistry;
    private final QdrantHybridStore requirementStore;
    private final CodeQdrantStore codeStore;
    private final WikiRepository wikiRepository;
    private final ProjectAccessGuard accessGuard;
    private final RestClient qdrantClient;
    private final RestClient modelClient;
    private final RestClient gitlabClient;
    private final String gitlabHealthPath;
    private final List<String> configuredModels;

    @Autowired
    public RuntimeStatusController(RagProperties properties, ProjectRegistry projectRegistry,
                                   QdrantHybridStore requirementStore, CodeQdrantStore codeStore,
                                   WikiRepository wikiRepository, ProjectAccessGuard accessGuard,
                                   @Value("${spring.ai.openai.base-url:}") String openAiBaseUrl,
                                   @Value("${spring.ai.openai.api-key:}") String openAiApiKey,
                                   @Value("${spring.ai.openai.embedding.options.model:}") String embeddingModel,
                                   @Value("${app.rag.runtime.gitlab-base-url:https://gitlab.com}") String gitlabBaseUrl,
                                   @Value("${app.rag.runtime.gitlab-health-path:/explore}") String gitlabHealthPath) {
        this(properties, projectRegistry, requirementStore, codeStore, wikiRepository, accessGuard,
                client(properties.qdrant().baseUrl()),
                authenticatedClient(openAiBaseUrl, openAiApiKey),
                gitlabClient(gitlabBaseUrl),
                gitlabHealthPath,
                configuredModels(properties, embeddingModel));
    }

    RuntimeStatusController(RagProperties properties, ProjectRegistry projectRegistry,
                            QdrantHybridStore requirementStore, CodeQdrantStore codeStore,
                            WikiRepository wikiRepository, ProjectAccessGuard accessGuard,
                            RestClient qdrantClient, RestClient modelClient, RestClient gitlabClient,
                            String gitlabHealthPath, List<String> configuredModels) {
        this.properties = properties;
        this.projectRegistry = projectRegistry;
        this.requirementStore = requirementStore;
        this.codeStore = codeStore;
        this.wikiRepository = wikiRepository;
        this.accessGuard = accessGuard;
        this.qdrantClient = qdrantClient;
        this.modelClient = modelClient;
        this.gitlabClient = gitlabClient;
        this.gitlabHealthPath = normalizePath(gitlabHealthPath);
        this.configuredModels = configuredModels == null ? List.of() : List.copyOf(configuredModels);
    }

    /** 汇总 Qdrant、API 模型网关、GitLab 与当前用户各项目状态。对应 GET /api/runtime/status。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/status")
    public RuntimeSnapshot status(HttpServletRequest request) {
        UserContext user = accessGuard.currentUser(request);
        ServiceCheck qdrant;
        ServiceCheck models;
        ServiceCheck gitlab;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var qdrantFuture = executor.submit(() ->
                    probe(qdrantClient, "/collections", "Qdrant", true, "向量数据库未连接"));
            var modelFuture = executor.submit(this::probeModels);
            var gitlabFuture = executor.submit(() ->
                    probe(gitlabClient, gitlabHealthPath, "GitLab", false, "GitLab 当前不可访问"));
            qdrant = qdrantFuture.get();
            models = modelFuture.get();
            gitlab = gitlabFuture.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            qdrant = unavailable("Qdrant", true, "状态检查被中断");
            models = unavailable("API 模型", true, "状态检查被中断");
            gitlab = unavailable("GitLab", false, "状态检查被中断");
        } catch (java.util.concurrent.ExecutionException exception) {
            qdrant = unavailable("Qdrant", true, "向量数据库状态暂时不可用");
            models = unavailable("API 模型", true, "模型网关状态暂时不可用");
            gitlab = unavailable("GitLab", false, "GitLab 状态暂时不可用");
        }
        List<ProjectCheck> projects = projectRegistry.all().stream()
                .filter(project -> user.hasAccessTo(project.id()))
                .map(this::projectStatus)
                .toList();
        boolean coreReady = qdrant.available() && models.available();
        boolean hasWiki = projects.stream().anyMatch(project -> project.wikiVersions() > 0);
        String state = coreReady ? (gitlab.available() ? "READY" : "DEGRADED")
                : (hasWiki ? "DEGRADED" : "NOT_READY");
        return new RuntimeSnapshot(state, coreReady, List.of(qdrant, models, gitlab), projects);
    }

    /** 汇总单个项目的需求/代码分块数、Wiki 版本数与仓库可用性，附带警告列表。 */
    private ProjectCheck projectStatus(RagProperties.ProjectConfig project) {
        List<String> warnings = new ArrayList<>();
        long requirements = countRequirements(project, warnings);
        long code = countCode(project, warnings);
        int wikiVersions;
        try {
            wikiVersions = wikiRepository.listVersions(project.id()).size();
        } catch (RuntimeException exception) {
            wikiVersions = 0;
            warnings.add("Wiki 文件暂时不可读取");
        }
        boolean repositoryAvailable = project.repositoryPath() != null
                && Files.isDirectory(Path.of(project.repositoryPath()));
        if (code == 0) warnings.add("当前代码 collection 还没有索引，请在代码工作台建立索引");
        if (!repositoryAvailable) warnings.add("代码仓库路径不可用");
        return new ProjectCheck(project.id(), project.name(), project.knowledge() == null ? null : project.knowledge().version(),
                project.requirementCollection(), project.codeCollection(), requirements, code, wikiVersions,
                repositoryAvailable, List.copyOf(warnings));
    }

    /** 统计项目需求分块数，失败时记录警告并返回 0。 */
    private long countRequirements(RagProperties.ProjectConfig project, List<String> warnings) {
        try {
            if (project.knowledge() == null) return 0;
            return requirementStore.countVersionIfAvailable(project.requirementCollection(),
                    project.knowledge().documentId(), project.knowledge().version());
        } catch (RuntimeException exception) {
            warnings.add("需求知识统计暂时不可用");
            return 0;
        }
    }

    /** 统计项目代码分块数，失败时记录警告并返回 0。 */
    private long countCode(RagProperties.ProjectConfig project, List<String> warnings) {
        try {
            return codeStore.countProjectIfAvailable(project.codeCollection(), project.id());
        } catch (RuntimeException exception) {
            warnings.add("代码索引统计暂时不可用");
            return 0;
        }
    }

    /** 探测普通 HTTP 服务健康：可达返回「已连接」，不可达返回安全提示。 */
    private ServiceCheck probe(RestClient client, String path, String name, boolean required, String message) {
        try {
            client.get().uri(path).retrieve().toBodilessEntity();
            return new ServiceCheck(name, true, required, "已连接");
        } catch (RuntimeException exception) {
            return unavailable(name, required, message);
        }
    }

    /** 读取 OpenAI 兼容网关模型目录，并核对当前配置引用的全部模型。 */
    private ServiceCheck probeModels() {
        try {
            Map<String, Object> response = modelClient.get().uri("/models").retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            Set<String> availableModels = modelIds(response);
            long missing = configuredModels.stream().filter(model -> !availableModels.contains(model)).count();
            if (missing > 0) {
                return unavailable("API 模型", true, "有 " + missing + " 个配置模型当前不可用");
            }
            String message = configuredModels.isEmpty()
                    ? "模型网关已连接"
                    : "已验证 " + configuredModels.size() + " 个配置模型";
            return new ServiceCheck("API 模型", true, true, message);
        } catch (RuntimeException exception) {
            return unavailable("API 模型", true, "模型网关未连接");
        }
    }

    private Set<String> modelIds(Map<String, Object> response) {
        if (response == null || !(response.get("data") instanceof List<?> data)) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Object item : data) {
            if (item instanceof Map<?, ?> model && model.get("id") instanceof String id && !id.isBlank()) {
                ids.add(id.trim());
            }
        }
        return Set.copyOf(ids);
    }

    private ServiceCheck unavailable(String name, boolean required, String message) {
        return new ServiceCheck(name, false, required, message);
    }

    /** 构建带 1s 连接超时、2s 读超时的健康检查客户端。 */
    private static RestClient client(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1_000);
        factory.setReadTimeout(2_000);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    private static RestClient authenticatedClient(String baseUrl, String apiKey) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1_000);
        factory.setReadTimeout(2_000);
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl).requestFactory(factory);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim());
        }
        return builder.build();
    }

    private static RestClient gitlabClient(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1_000);
        factory.setReadTimeout(2_000);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.USER_AGENT, "NEXUS-runtime-health")
                .requestFactory(factory)
                .build();
    }

    private static List<String> configuredModels(RagProperties properties, String embeddingModel) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        addModel(models, embeddingModel);
        RagProperties.Llm llm = properties.llm();
        if (llm != null) {
            addModel(models, llm.generationModel());
            addModel(models, llm.resolvedDevelopmentPlanModel());
            addModel(models, llm.resolvedDoubtReviewModel());
            addModel(models, llm.resolvedRoutingModel());
            addModel(models, llm.resolvedAnnotationModel());
            if (properties.retrieval() != null && properties.retrieval().llmRerankEnabled()) {
                addModel(models, llm.rerankerModel());
            }
        }
        return List.copyOf(models);
    }

    private static void addModel(Set<String> models, String model) {
        if (model != null && !model.isBlank()) {
            models.add(model.trim());
        }
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/explore";
        }
        String normalized = path.trim();
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    /** 平台首页运行状态快照：整体状态、核心服务与各项目检查结果。 */
    public record RuntimeSnapshot(String state, boolean coreReady, List<ServiceCheck> services,
                                  List<ProjectCheck> projects) {}
    /** 单个外部服务的可用性检查结果。 */
    public record ServiceCheck(String name, boolean available, boolean required, String message) {}
    /** 单个项目的运行检查结果：分块统计、Wiki 版本数、仓库可用性与警告。 */
    public record ProjectCheck(String projectId, String projectName, String version,
                               String requirementCollection, String codeCollection,
                               long requirementChunks, long codeChunks, int wikiVersions,
                               boolean repositoryAvailable, List<String> warnings) {}
}
