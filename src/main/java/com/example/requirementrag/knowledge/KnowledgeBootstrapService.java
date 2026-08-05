package com.example.requirementrag.knowledge;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.IngestResponse;
import com.example.requirementrag.model.KnowledgeEntry;
import com.example.requirementrag.observability.RagObservability;
import com.example.requirementrag.service.RequirementIngestionService;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库引导服务：扫描 ZIP、等待 Qdrant 就绪并批量导入向量库。
 * 支持按 projectId 引导指定项目，或引导所有已启用的项目。
 * 全局引导使用单次运行锁，项目引导使用项目级锁，进度统一通过 BootstrapState 追踪。
 */
@Service
public class KnowledgeBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBootstrapService.class);

    private final RagProperties properties;
    private final ProjectRegistry projectRegistry;
    private final ZipHtmlKnowledgeLoader zipLoader;
    private final RequirementIngestionService ingestionService;
    private final BootstrapState bootstrapState;
    private final RagObservability observability;
    private final QdrantHybridStore store;

    /** 注入配置、项目注册表、ZIP 加载器、导入服务、状态追踪、可观测性与向量存储。 */
    public KnowledgeBootstrapService(RagProperties properties, ProjectRegistry projectRegistry,
                                     ZipHtmlKnowledgeLoader zipLoader,
                                     RequirementIngestionService ingestionService,
                                     BootstrapState bootstrapState, RagObservability observability,
                                     QdrantHybridStore store) {
        this.properties = properties;
        this.projectRegistry = projectRegistry;
        this.zipLoader = zipLoader;
        this.ingestionService = ingestionService;
        this.bootstrapState = bootstrapState;
        this.observability = observability;
        this.store = store;
    }

    /** 在虚拟线程中异步启动所有已启用项目的引导。 */
    public void bootstrapAsync() {
        if (bootstrapState.running()) {
            return;
        }
        Thread.startVirtualThread(this::bootstrapAll);
    }

    /** 在虚拟线程中异步启动指定项目的引导。 */
    public void bootstrapAsync(String projectId) {
        if (bootstrapState.running(projectId)) {
            return;
        }
        Thread.startVirtualThread(() -> bootstrap(projectId));
    }

    /** 引导所有已配置且启用 bootstrap 的项目。 */
    public List<IngestResponse> bootstrapAll() {
        List<RagProperties.ProjectConfig> projects = projectRegistry.all();
        List<IngestResponse> results = new ArrayList<>();
        for (RagProperties.ProjectConfig project : projects) {
            if (project.knowledge() != null && project.knowledge().bootstrapEnabled()) {
                results.add(bootstrapProject(project));
            }
        }
        if (results.isEmpty()) {
            results.add(bootstrap());
        }
        return results;
    }

    /** 引导指定 projectId 的项目。 */
    public IngestResponse bootstrap(String projectId) {
        RagProperties.ProjectConfig project = projectRegistry.require(projectId);
        return bootstrapProject(project);
    }

    /** 使用默认配置的引导（向后兼容）。 */
    public IngestResponse bootstrap() {
        if (bootstrapState.running()) {
            throw new IllegalStateException("知识库导入正在进行中");
        }

        bootstrapState.start();
        RagProperties.Knowledge knowledge = properties.knowledge();
        Path zipPath = Path.of(knowledge.zipPath()).toAbsolutePath().normalize();
        String documentId = knowledge.documentId();
        String version = knowledge.version();
        String collection = properties.qdrant().collection();

        return doBootstrap(collection, zipPath, documentId, version);
    }

    /** 按项目配置执行引导，使用项目级锁。 */
    private IngestResponse bootstrapProject(RagProperties.ProjectConfig project) {
        if (!bootstrapState.tryStartProject(project.id())) {
            throw new IllegalStateException("项目 " + project.id() + " 知识库导入正在进行中");
        }

        RagProperties.ProjectKnowledge knowledge = project.knowledge();
        if (knowledge == null) {
            bootstrapState.finishProject(project.id());
            throw new IllegalArgumentException("项目 " + project.id() + " 未配置知识库");
        }

        bootstrapState.start();
        Path zipPath = Path.of(knowledge.zipPath()).toAbsolutePath().normalize();
        String documentId = knowledge.documentId();
        String version = knowledge.version();
        String collection = project.requirementCollection();

        try {
            return doBootstrap(collection, zipPath, documentId, version);
        } finally {
            bootstrapState.finishProject(project.id());
        }
    }

    /** 引导主流程：统计候选数 → 加载 ZIP 条目 → 等待 Qdrant 就绪 → 批量导入，并更新状态、可观测性与日志。 */
    private IngestResponse doBootstrap(String collection, Path zipPath, String documentId, String version) {
        try {
            bootstrapState.phase("scan");
            int zipTotal = zipLoader.countCandidates(zipPath);
            bootstrapState.filesTotal(zipTotal);

            bootstrapState.phase("zip");
            List<KnowledgeEntry> zipEntries = zipLoader.load(zipPath, (processed, fileName) ->
                    bootstrapState.fileProgress(processed, fileName));
            bootstrapState.zipFiles(zipEntries.size());
            bootstrapState.xlsxRows(0);

            List<KnowledgeEntry> allEntries = new ArrayList<>(zipEntries);

            bootstrapState.phase("wait-qdrant");
            store.waitUntilReady(Duration.ofMinutes(3));

            bootstrapState.phase("ingest");
            IngestResponse response = observability.observe("knowledge.bootstrap", documentId, version,
                    () -> ingestionService.ingestEntries(collection, documentId, version, allEntries));
            bootstrapState.chunks(response.chunks());
            bootstrapState.complete();
            log.atInfo().addKeyValue("event", "knowledge_bootstrap_completed")
                    .addKeyValue("collection", collection)
                    .addKeyValue("documentId", documentId).addKeyValue("version", version)
                    .addKeyValue("zipFiles", zipEntries.size())
                    .addKeyValue("chunks", response.chunks()).log("Knowledge bootstrap completed");
            return response;
        }
        catch (IOException exception) {
            bootstrapState.fail(exception.getMessage());
            log.atError().setCause(exception).addKeyValue("event", "knowledge_bootstrap_failed")
                    .addKeyValue("documentId", documentId).addKeyValue("version", version)
                    .log("Knowledge bootstrap failed");
            throw new IllegalStateException("知识库导入失败: " + exception.getMessage(), exception);
        }
        catch (RuntimeException exception) {
            bootstrapState.fail(exception.getMessage());
            throw exception;
        }
    }
}
