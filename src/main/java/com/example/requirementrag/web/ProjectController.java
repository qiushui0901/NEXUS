package com.example.requirementrag.web;

import com.example.requirementrag.code.CodeQdrantStore;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目注册表 REST 接口：列出所有可用项目及其统计信息。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private static final Logger log = LoggerFactory.getLogger(ProjectController.class);

    private final ProjectRegistry projectRegistry;
    private final QdrantHybridStore documentStore;
    private final CodeQdrantStore codeStore;
    private final ProjectAccessGuard accessGuard;

    public ProjectController(ProjectRegistry projectRegistry,
                             QdrantHybridStore documentStore,
                             CodeQdrantStore codeStore,
                             ProjectAccessGuard accessGuard) {
        this.projectRegistry = projectRegistry;
        this.documentStore = documentStore;
        this.codeStore = codeStore;
        this.accessGuard = accessGuard;
    }

    /** 列出当前用户有权访问的项目及其统计摘要。对应 GET /api/projects。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping
    public List<ProjectSummary> list(HttpServletRequest request) {
        UserContext user = accessGuard.currentUser(request);
        return projectRegistry.all().stream()
                .filter(project -> hasText(project.id()))
                .filter(project -> user.hasAccessTo(project.id()))
                .map(this::toSummary)
                .toList();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 将项目配置转换为含需求/代码分块统计的摘要。 */
    private ProjectSummary toSummary(RagProperties.ProjectConfig project) {
        long requirementChunks = safeCountRequirements(project);
        long codeChunks = safeCountCode(project);
        String version = project.knowledge() != null ? project.knowledge().version() : null;
        return new ProjectSummary(
                project.id(),
                project.name(),
                project.group(),
                project.side(),
                version,
                requirementChunks,
                codeChunks);
    }

    /** 安全统计需求分块数，配置缺失或存储不可用时返回 0。 */
    private long safeCountRequirements(RagProperties.ProjectConfig project) {
        try {
            if (project.knowledge() == null) return 0L;
            String collection = project.requirementCollection();
            String docId = project.knowledge().documentId();
            String version = project.knowledge().version();
            if (collection == null || docId == null || version == null) return 0L;
            return documentStore.countVersionIfAvailable(collection, docId, version);
        } catch (RuntimeException exception) {
            log.debug("Requirement count unavailable project={} exceptionType={}",
                    project.id(), exception.getClass().getSimpleName());
            return 0L;
        }
    }

    /** 安全统计代码分块数，存储不可用时返回 0。 */
    private long safeCountCode(RagProperties.ProjectConfig project) {
        try {
            String collection = project.codeCollection();
            if (collection == null) return 0L;
            return codeStore.countProjectIfAvailable(collection, project.id());
        } catch (RuntimeException exception) {
            log.debug("Code count unavailable project={} exceptionType={}",
                    project.id(), exception.getClass().getSimpleName());
            return 0L;
        }
    }

    /** 项目列表摘要：基本信息与需求/代码分块统计。 */
    public record ProjectSummary(
            String id,
            String name,
            String group,
            String side,
            String version,
            long requirementChunks,
            long codeChunks
    ) {}
}
