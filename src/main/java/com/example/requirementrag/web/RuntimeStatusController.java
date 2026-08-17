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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
    private final RestClient ollamaClient;
    private final RestClient bgeClient;

    public RuntimeStatusController(RagProperties properties, ProjectRegistry projectRegistry,
                                   QdrantHybridStore requirementStore, CodeQdrantStore codeStore,
                                   WikiRepository wikiRepository, ProjectAccessGuard accessGuard,
                                   @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl) {
        this.properties = properties;
        this.projectRegistry = projectRegistry;
        this.requirementStore = requirementStore;
        this.codeStore = codeStore;
        this.wikiRepository = wikiRepository;
        this.accessGuard = accessGuard;
        this.qdrantClient = client(properties.qdrant().baseUrl());
        this.ollamaClient = client(ollamaBaseUrl);
        this.bgeClient = client(properties.bge().baseUrl());
    }

    /** 汇总 Qdrant/Ollama/BGE 服务与当前用户各项目状态，返回整体运行状态。对应 GET /api/runtime/status。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/status")
    public RuntimeSnapshot status(HttpServletRequest request) {
        UserContext user = accessGuard.currentUser(request);
        ServiceCheck qdrant = probe(qdrantClient, "/collections", true, "向量数据库未连接");
        ServiceCheck ollama = probe(ollamaClient, "/api/tags", true, "本地嵌入模型未连接");
        ServiceCheck bge = probe(bgeClient, "/health", false, "重排服务未连接，检索将自动降级");
        List<ProjectCheck> projects = projectRegistry.all().stream()
                .filter(project -> user.hasAccessTo(project.id()))
                .map(this::projectStatus)
                .toList();
        boolean coreReady = qdrant.available() && ollama.available();
        boolean hasWiki = projects.stream().anyMatch(project -> project.wikiVersions() > 0);
        String state = coreReady ? (bge.available() ? "READY" : "DEGRADED") : (hasWiki ? "DEGRADED" : "NOT_READY");
        return new RuntimeSnapshot(state, coreReady, List.of(qdrant, ollama, bge), projects);
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

    /** 探测服务健康：可达返回「已连接」，不可达按是否必选返回相应提示消息。 */
    private ServiceCheck probe(RestClient client, String path, boolean required, String message) {
        try {
            client.get().uri(path).retrieve().toBodilessEntity();
            return new ServiceCheck(serviceName(path), true, required, "已连接");
        } catch (RuntimeException exception) {
            return new ServiceCheck(serviceName(path), false, required, message);
        }
    }

    /** 根据探测路径返回服务显示名（Qdrant/Ollama/BGE Reranker）。 */
    private String serviceName(String path) {
        if ("/collections".equals(path)) return "Qdrant";
        if ("/api/tags".equals(path)) return "Ollama";
        return "BGE Reranker";
    }

    /** 构建带 1s 连接超时、2s 读超时的健康检查客户端。 */
    private RestClient client(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1_000);
        factory.setReadTimeout(2_000);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
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
