package com.example.requirementrag.project;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.integration.gitlab.GitLabManagedProject;
import com.example.requirementrag.integration.gitlab.GitLabProjectStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 管理员显式执行的业务项目迁移；预览无写入，应用幂等。 */
@Service
public class BusinessProjectMigrationService {

    private final BusinessProjectCatalogStore catalogStore;
    private final ProjectRegistry legacyRegistry;
    private final GitLabProjectStore gitLabStore;
    private final RepositoryVersionResolver versionResolver;

    public BusinessProjectMigrationService(BusinessProjectCatalogStore catalogStore,
                                           ProjectRegistry legacyRegistry,
                                           ObjectProvider<GitLabProjectStore> gitLabStore,
                                           RepositoryVersionResolver versionResolver) {
        this.catalogStore = catalogStore;
        this.legacyRegistry = legacyRegistry;
        this.gitLabStore = gitLabStore.getIfAvailable();
        this.versionResolver = versionResolver;
    }

    public Preview preview(MigrationRequest request) {
        validate(request);
        RagProperties.ProjectConfig source = legacyRegistry.require(request.sourceProjectId());
        CodeRepository anchor = repository(source, request.targetProjectId(), CodeRepository.Kind.PROJECT);
        List<CodeRepository> repositories = new ArrayList<>();
        repositories.add(anchor);
        List<Mapping> mappings = new ArrayList<>();
        mappings.add(new Mapping("BUSINESS_PROJECT", source.id(), request.targetProjectId()));
        mappings.add(new Mapping("ANCHOR_REPOSITORY", source.id(), anchor.id()));
        mappings.add(new Mapping("REQUIREMENTS", source.id(), request.targetProjectId()));
        mappings.add(new Mapping("WIKI", source.id(), request.targetProjectId()));

        for (String repositoryId : request.repositoryIds()) {
            if (repositoryId.equals(anchor.id())) continue;
            GitLabManagedProject managed = requireGitLabRepository(repositoryId);
            repositories.add(repository(managed, request.targetProjectId(), CodeRepository.Kind.PROJECT));
            mappings.add(new Mapping("REPOSITORY", repositoryId, request.targetProjectId()));
        }
        BusinessProject project = project(request, source);
        var version = versionResolver.resolve(anchor);
        List<String> warnings = new ArrayList<>();
        if (!"AVAILABLE".equals(version.status())) warnings.add(version.warningCode());
        if (catalogStore.project(request.targetProjectId()).isPresent()) {
            warnings.add("TARGET_ALREADY_EXISTS");
        }
        return new Preview(request.migrationId(), project, List.copyOf(repositories),
                List.copyOf(mappings), version, List.copyOf(warnings));
    }

    public Preview apply(MigrationRequest request) {
        Preview preview = preview(request);
        catalogStore.applyMigration(request.migrationId(), preview.project(), preview.repositories(),
                List.of(request.sourceProjectId()));
        return preview;
    }

    private BusinessProject project(MigrationRequest request, RagProperties.ProjectConfig source) {
        RagProperties.ProjectKnowledge knowledge = source.knowledge();
        if (knowledge == null || blank(knowledge.documentId())) {
            throw new IllegalArgumentException("源项目没有可迁移的需求配置");
        }
        String now = Instant.now().toString();
        return new BusinessProject(request.targetProjectId(), request.targetName(),
                request.anchorRepositoryId(), source.requirementCollection(), knowledge.documentId(),
                source.id(), source.id(), knowledge.version(), BusinessProject.Status.ACTIVE, now, now);
    }

    private CodeRepository repository(RagProperties.ProjectConfig source, String businessProjectId,
                                      CodeRepository.Kind kind) {
        String now = Instant.now().toString();
        return new CodeRepository(source.id(), source.name(), kind, businessProjectId, source.side(),
                source.codeCollection(), source.repositoryPath(), source.gitPath(),
                "MAVEN_POM", "pom.xml", true, true, now, now);
    }

    private CodeRepository repository(GitLabManagedProject source, String businessProjectId,
                                      CodeRepository.Kind kind) {
        String now = Instant.now().toString();
        return new CodeRepository(source.projectId(), source.name(), kind, businessProjectId, source.side(),
                source.codeCollection(), source.repositoryPath(), source.gitPath(),
                "MAVEN_POM", "pom.xml", true,
                source.status() != com.example.requirementrag.integration.gitlab.GitLabProjectStatus.DISABLED,
                source.createdAt() == null ? now : source.createdAt(), now);
    }

    private GitLabManagedProject requireGitLabRepository(String id) {
        if (gitLabStore == null) throw new IllegalStateException("GitLab 集成未启用");
        return gitLabStore.find(id).orElseThrow(() -> new IllegalArgumentException("未知 GitLab 仓库: " + id));
    }

    private void validate(MigrationRequest request) {
        if (request == null || blank(request.migrationId()) || blank(request.sourceProjectId())
                || blank(request.targetProjectId()) || blank(request.targetName())
                || blank(request.anchorRepositoryId())) {
            throw new IllegalArgumentException("迁移请求不完整");
        }
        if (!request.sourceProjectId().equals(request.anchorRepositoryId())) {
            throw new IllegalArgumentException("当前迁移只支持源静态项目作为版本主仓库");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record MigrationRequest(String migrationId, String sourceProjectId, String targetProjectId,
                                   String targetName, String anchorRepositoryId, List<String> repositoryIds) {
        public MigrationRequest {
            repositoryIds = repositoryIds == null ? List.of() : List.copyOf(repositoryIds);
        }
    }

    public record Mapping(String type, String source, String target) {}

    public record Preview(String migrationId, BusinessProject project, List<CodeRepository> repositories,
                          List<Mapping> mappings, RepositoryVersionResolver.ResolvedVersion productVersion,
                          List<String> warnings) {}
}
