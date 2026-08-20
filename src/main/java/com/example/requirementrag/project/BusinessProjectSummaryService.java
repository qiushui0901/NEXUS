package com.example.requirementrag.project;

import com.example.requirementrag.code.CodeQdrantStore;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.wiki.WikiRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 汇总业务项目的共享需求、多仓库代码和 Wiki 状态。 */
@Service
public class BusinessProjectSummaryService {

    private final BusinessProjectCatalogService catalog;
    private final QdrantHybridStore requirementStore;
    private final CodeQdrantStore codeStore;
    private final WikiRepository wikiRepository;

    public BusinessProjectSummaryService(BusinessProjectCatalogService catalog,
                                         QdrantHybridStore requirementStore,
                                         CodeQdrantStore codeStore,
                                         WikiRepository wikiRepository) {
        this.catalog = catalog;
        this.requirementStore = requirementStore;
        this.codeStore = codeStore;
        this.wikiRepository = wikiRepository;
    }

    public List<ProjectSummary> summaries() {
        return catalog.projects().stream().map(this::summary).toList();
    }

    public ProjectSummary summary(String projectId) {
        return summary(catalog.requireProject(projectId));
    }

    public ProjectSummary summary(BusinessProject project) {
        List<CodeRepository> owned = catalog.ownedRepositories(project.id());
        List<CodeRepository> shared = catalog.sharedRepositories(project.id());
        List<String> warnings = new ArrayList<>();
        long requirementChunks = countRequirements(project, warnings);
        long codeChunks = 0;
        for (CodeRepository repository : concat(owned, shared)) {
            try {
                codeChunks += repository.liveAlias()
                        ? codeStore.countLiveProjectIfAvailable(repository.codeCollection(), repository.id())
                        : codeStore.countProjectIfAvailable(repository.codeCollection(), repository.id());
            } catch (RuntimeException exception) {
                warnings.add("CODE_COUNT_UNAVAILABLE:" + repository.id());
            }
        }
        int wikiVersions;
        try {
            wikiVersions = wikiRepository.listVersions(project.wikiNamespace()).size();
        } catch (RuntimeException exception) {
            wikiVersions = 0;
            warnings.add("WIKI_UNAVAILABLE");
        }
        var productVersion = catalog.productVersion(project.id());
        if (!"AVAILABLE".equals(productVersion.status())) {
            warnings.add(productVersion.warningCode());
        }
        var coverage = catalog.requirementCoverage(project.id());
        if (coverage == BusinessProjectCatalogService.CoverageStatus.BEHIND) {
            warnings.add("REQUIREMENT_VERSION_BEHIND");
        }
        return new ProjectSummary(project.id(), project.name(), productVersion.displayVersion(),
                project.latestRequirementVersion(), coverage.name(), requirementChunks, codeChunks,
                wikiVersions, owned.size(), shared.size(), warnings.isEmpty() ? "READY" : "DEGRADED",
                List.copyOf(warnings));
    }

    private long countRequirements(BusinessProject project, List<String> warnings) {
        if (project.latestRequirementVersion() == null) return 0;
        try {
            return requirementStore.countVersionIfAvailable(project.requirementCollection(),
                    project.requirementDocumentId(), project.latestRequirementVersion());
        } catch (RuntimeException exception) {
            warnings.add("REQUIREMENT_COUNT_UNAVAILABLE");
            return 0;
        }
    }

    private List<CodeRepository> concat(List<CodeRepository> left, List<CodeRepository> right) {
        List<CodeRepository> values = new ArrayList<>(left.size() + right.size());
        values.addAll(left);
        values.addAll(right);
        return values;
    }

    public record ProjectSummary(String id, String name, String productVersion,
                                 String latestRequirementVersion, String requirementCoverage,
                                 long requirementChunks, long codeChunks, int wikiVersions,
                                 int repositoryCount, int sharedRepositoryCount, String status,
                                 List<String> warnings) {}
}
