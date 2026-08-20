package com.example.requirementrag.knowledge;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.knowledge.management.KnowledgeIngestionTracker;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.EventStatus;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.Stage;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.TriggerType;
import com.example.requirementrag.model.IngestResponse;
import com.example.requirementrag.model.KnowledgeEntry;
import com.example.requirementrag.observability.RagObservability;
import com.example.requirementrag.service.RequirementIngestionService;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final KnowledgeIngestionTracker ingestionTracker;

    /** 注入配置、项目注册表、ZIP 加载器、导入服务、状态追踪、可观测性与向量存储。 */
    public KnowledgeBootstrapService(RagProperties properties, ProjectRegistry projectRegistry,
                                     ZipHtmlKnowledgeLoader zipLoader,
                                     RequirementIngestionService ingestionService,
                                     BootstrapState bootstrapState, RagObservability observability,
                                     QdrantHybridStore store) {
        this(properties, projectRegistry, zipLoader, ingestionService, bootstrapState, observability, store,
                (KnowledgeIngestionTracker) null);
    }

    @Autowired
    public KnowledgeBootstrapService(RagProperties properties, ProjectRegistry projectRegistry,
                                     ZipHtmlKnowledgeLoader zipLoader,
                                     RequirementIngestionService ingestionService,
                                     BootstrapState bootstrapState, RagObservability observability,
                                     QdrantHybridStore store,
                                     ObjectProvider<KnowledgeIngestionTracker> ingestionTracker) {
        this(properties, projectRegistry, zipLoader, ingestionService, bootstrapState, observability, store,
                ingestionTracker.getIfAvailable());
    }

    private KnowledgeBootstrapService(RagProperties properties, ProjectRegistry projectRegistry,
                                      ZipHtmlKnowledgeLoader zipLoader,
                                      RequirementIngestionService ingestionService,
                                      BootstrapState bootstrapState, RagObservability observability,
                                      QdrantHybridStore store, KnowledgeIngestionTracker ingestionTracker) {
        this.properties = properties;
        this.projectRegistry = projectRegistry;
        this.zipLoader = zipLoader;
        this.ingestionService = ingestionService;
        this.bootstrapState = bootstrapState;
        this.observability = observability;
        this.store = store;
        this.ingestionTracker = ingestionTracker;
    }

    /** 在虚拟线程中异步启动所有已启用项目的引导。 */
    public void bootstrapAsync() {
        if (bootstrapState.running()) {
            return;
        }
        Thread thread = new Thread(this::bootstrapAll, "nexus-bootstrap");
        thread.setDaemon(true);
        thread.start();
    }
    /** 兼容旧调用：仓库配置 ID 与知识状态 ID 相同。 */
    public void bootstrapAsync(String projectId) {
        bootstrapAsync(projectId, projectId);
    }

    /** 在后台线程中异步启动指定仓库配置，并把知识状态归属到独立的业务项目 ID。 */
    public void bootstrapAsync(String repositoryProjectId, String knowledgeProjectId) {
        if (bootstrapState.running(knowledgeProjectId)) {
            return;
        }
        Thread thread = new Thread(() -> bootstrap(repositoryProjectId, knowledgeProjectId),
                "nexus-bootstrap-" + knowledgeProjectId);
        thread.setDaemon(true);
        thread.start();
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
        return bootstrap(projectId, projectId);
    }

    /** 使用仓库配置读取需求来源，但把状态写入业务项目知识库。 */
    public IngestResponse bootstrap(String repositoryProjectId, String knowledgeProjectId) {
        RagProperties.ProjectConfig project = projectRegistry.require(repositoryProjectId);
        return bootstrapProject(project, knowledgeProjectId);
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
        String projectId = projectRegistry.find(null).map(RagProperties.ProjectConfig::id).orElse("default");
        KnowledgeIngestionTracker.Context context = startTracking(projectId, collection, version);

        return doBootstrap(collection, zipPath, documentId, version, context);
    }

    /** 按项目配置执行引导，使用项目级锁。 */
    /** 按仓库配置执行引导，使用业务项目级状态归属和锁。 */
    private IngestResponse bootstrapProject(RagProperties.ProjectConfig project) {
        return bootstrapProject(project, project.id());
    }

    private IngestResponse bootstrapProject(RagProperties.ProjectConfig project, String knowledgeProjectId) {
        if (!bootstrapState.tryStartProject(knowledgeProjectId)) {
            throw new IllegalStateException("项目 " + knowledgeProjectId + " 知识库导入正在进行中");
        }
        try {
            RagProperties.ProjectKnowledge knowledge = project.knowledge();
            if (knowledge == null) {
                throw new IllegalArgumentException("项目 " + project.id() + " 未配置知识库");
            }
            bootstrapState.start();
            Path zipPath = Path.of(knowledge.zipPath()).toAbsolutePath().normalize();
            String documentId = knowledge.documentId();
            String version = knowledge.version();
            String collection = project.requirementCollection();
            KnowledgeIngestionTracker.Context context = startTracking(knowledgeProjectId, collection, version);
            return doBootstrap(collection, zipPath, documentId, version, context);
        } catch (RuntimeException exception) {
            bootstrapState.fail(exception.getMessage());
            throw exception;
        } finally {
            bootstrapState.finishProject(knowledgeProjectId);
        }
    }

    /** 引导主流程：统计候选数 → 加载 ZIP 条目 → 等待 Qdrant 就绪 → 批量导入，并更新状态、可观测性与日志。 */
    private IngestResponse doBootstrap(String collection, Path zipPath, String documentId, String version,
                                       KnowledgeIngestionTracker.Context context) {
        try {
            bootstrapState.phase("scan");
            progress(context, Stage.DISCOVER, 0, 0, null);
            int zipTotal = zipLoader.countCandidates(zipPath);
            bootstrapState.filesTotal(zipTotal);
            progress(context, Stage.DISCOVER, zipTotal, 0, null);
            event(context, Stage.DISCOVER, EventStatus.SUCCEEDED, zipTotal, zipTotal, 0, null);

            bootstrapState.phase("zip");
            List<KnowledgeEntry> zipEntries = zipLoader.load(zipPath, (processed, fileName) -> {
                bootstrapState.fileProgress(processed, fileName);
                progress(context, Stage.PARSE, zipTotal, processed, fileName);
            });
            bootstrapState.zipFiles(zipEntries.size());
            bootstrapState.xlsxRows(0);
            event(context, Stage.PARSE, EventStatus.SUCCEEDED,
                    zipTotal, zipEntries.size(), Math.max(0, zipTotal - zipEntries.size()), null);

            List<KnowledgeEntry> allEntries = new ArrayList<>(zipEntries);

            bootstrapState.phase("wait-qdrant");
            progress(context, Stage.PARSE, zipTotal, zipEntries.size(), "等待 Qdrant");
            store.waitUntilReady(Duration.ofMinutes(3));

            bootstrapState.phase("ingest");
            IngestResponse response = observability.observe("knowledge.bootstrap", documentId, version,
                    () -> ingestionService.ingestEntries(collection, documentId, version, allEntries, context));
            bootstrapState.chunks(response.chunks());
            bootstrapState.complete();
            if (ingestionTracker != null) ingestionTracker.complete(context, response.chunks());
            log.atInfo().addKeyValue("event", "knowledge_bootstrap_completed")
                    .addKeyValue("collection", collection)
                    .addKeyValue("documentId", documentId).addKeyValue("version", version)
                    .addKeyValue("zipFiles", zipEntries.size())
                    .addKeyValue("chunks", response.chunks()).log("Knowledge bootstrap completed");
            return response;
        }
        catch (IOException exception) {
            bootstrapState.fail(exception.getMessage());
            if (ingestionTracker != null) ingestionTracker.fail(context, exception);
            log.atError().setCause(exception).addKeyValue("event", "knowledge_bootstrap_failed")
                    .addKeyValue("documentId", documentId).addKeyValue("version", version)
                    .log("Knowledge bootstrap failed");
            throw new IllegalStateException("知识库导入失败: " + exception.getMessage(), exception);
        }
        catch (RuntimeException exception) {
            bootstrapState.fail(exception.getMessage());
            if (ingestionTracker != null) ingestionTracker.fail(context, exception);
            throw exception;
        }
    }

    private KnowledgeIngestionTracker.Context startTracking(String projectId, String collection, String revision) {
        if (ingestionTracker == null) return KnowledgeIngestionTracker.Context.disabled();
        return ingestionTracker.start(projectId, projectId, collection, revision, TriggerType.BOOTSTRAP);
    }

    private void progress(KnowledgeIngestionTracker.Context context, Stage stage,
                          int total, int processed, String currentFile) {
        if (ingestionTracker != null) {
            ingestionTracker.progress(context, stage, total, processed, currentFile);
        }
    }

    private void event(KnowledgeIngestionTracker.Context context, Stage stage, EventStatus status,
                       int input, int output, int excluded, Throwable error) {
        if (ingestionTracker != null) {
            ingestionTracker.event(context, "RUN", context.runId(), stage, status,
                    input, output, excluded, error);
        }
    }
}
