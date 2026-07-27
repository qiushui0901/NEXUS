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

/** Provides a bounded, failure-tolerant snapshot for the platform home page. */
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

    private long countRequirements(RagProperties.ProjectConfig project, List<String> warnings) {
        try {
            if (project.knowledge() == null) return 0;
            return requirementStore.countVersion(project.requirementCollection(),
                    project.knowledge().documentId(), project.knowledge().version());
        } catch (RuntimeException exception) {
            warnings.add("需求知识统计暂时不可用");
            return 0;
        }
    }

    private long countCode(RagProperties.ProjectConfig project, List<String> warnings) {
        try {
            return codeStore.countProject(project.codeCollection(), project.id());
        } catch (RuntimeException exception) {
            warnings.add("代码索引统计暂时不可用");
            return 0;
        }
    }

    private ServiceCheck probe(RestClient client, String path, boolean required, String message) {
        try {
            client.get().uri(path).retrieve().toBodilessEntity();
            return new ServiceCheck(serviceName(path), true, required, "已连接");
        } catch (RuntimeException exception) {
            return new ServiceCheck(serviceName(path), false, required, message);
        }
    }

    private String serviceName(String path) {
        if ("/collections".equals(path)) return "Qdrant";
        if ("/api/tags".equals(path)) return "Ollama";
        return "BGE Reranker";
    }

    private RestClient client(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1_000);
        factory.setReadTimeout(2_000);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public record RuntimeSnapshot(String state, boolean coreReady, List<ServiceCheck> services,
                                  List<ProjectCheck> projects) {}
    public record ServiceCheck(String name, boolean available, boolean required, String message) {}
    public record ProjectCheck(String projectId, String projectName, String version,
                               String requirementCollection, String codeCollection,
                               long requirementChunks, long codeChunks, int wikiVersions,
                               boolean repositoryAvailable, List<String> warnings) {}
}
