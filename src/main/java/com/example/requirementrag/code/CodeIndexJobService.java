package com.example.requirementrag.code;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.CodeIndexJobState;
import com.example.requirementrag.model.CodeIndexJobStatus;
import com.example.requirementrag.model.CodeIndexResponse;
import com.example.requirementrag.retrieval.EmbeddingUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 在后台执行完整代码索引，并保留每个项目最近一次任务状态。
 * 同一项目同一时刻只允许一个完整索引任务运行。
 */
@Service
public class CodeIndexJobService {

    private static final Logger log = LoggerFactory.getLogger(CodeIndexJobService.class);

    private final CodeKnowledgeService codeKnowledgeService;
    private final ProjectRegistry projectRegistry;
    private final Executor backgroundExecutor;
    private final Map<String, CodeIndexJobStatus> statuses = new ConcurrentHashMap<>();

    @Autowired
    public CodeIndexJobService(CodeKnowledgeService codeKnowledgeService, ProjectRegistry projectRegistry) {
        this(codeKnowledgeService, projectRegistry,
                command -> Thread.ofVirtual().name("nexus-code-index").start(command));
    }

    CodeIndexJobService(CodeKnowledgeService codeKnowledgeService,
                        ProjectRegistry projectRegistry,
                        Executor backgroundExecutor) {
        this.codeKnowledgeService = codeKnowledgeService;
        this.projectRegistry = projectRegistry;
        this.backgroundExecutor = backgroundExecutor;
    }

    /** 启动后台索引；若该项目已在运行，直接返回当前任务。 */
    public synchronized CodeIndexJobStatus start(String projectId) {
        String resolvedProjectId = projectRegistry.require(projectId).id();
        CodeIndexJobStatus current = statuses.get(resolvedProjectId);
        if (current != null && current.state() == CodeIndexJobState.RUNNING) {
            return current;
        }

        String startedAt = Instant.now().toString();
        CodeIndexJobStatus running = CodeIndexJobStatus.running(resolvedProjectId, startedAt,
                existingChunkCount(resolvedProjectId));
        statuses.put(resolvedProjectId, running);
        try {
            backgroundExecutor.execute(() -> executeIndex(resolvedProjectId, startedAt));
        }
        catch (RuntimeException exception) {
            CodeIndexJobStatus failed = CodeIndexJobStatus.failed(resolvedProjectId, startedAt,
                    Instant.now().toString(), "无法启动后台索引任务，请稍后重试");
            statuses.put(resolvedProjectId, failed);
            throw exception;
        }
        return running;
    }

    /** 返回项目最近一次后台索引状态。 */
    public CodeIndexJobStatus status(String projectId) {
        String resolvedProjectId = projectRegistry.require(projectId).id();
        CodeIndexJobStatus current = statuses.get(resolvedProjectId);
        return current != null
                ? current
                : CodeIndexJobStatus.idle(resolvedProjectId, existingChunkCount(resolvedProjectId));
    }

    /** 读取项目已有代码 chunk 数用于任务状态展示；读取失败时按 0 处理。 */
    private int existingChunkCount(String projectId) {
        try {
            return Math.toIntExact(codeKnowledgeService.count(projectId));
        }
        catch (RuntimeException exception) {
            log.debug("Unable to read existing code chunk count for project {}", projectId, exception);
            return 0;
        }
    }

    /** 后台执行完整索引：成功写入 completed 状态，任何异常写入 failed 状态并记录错误日志。 */
    private void executeIndex(String projectId, String startedAt) {
        try {
            log.info("Starting background code index for project {}", projectId);
            CodeIndexResponse response = codeKnowledgeService.index(projectId);
            statuses.put(projectId, CodeIndexJobStatus.completed(response, startedAt, Instant.now().toString()));
            log.info("Completed background code index for project {} with {} files and {} chunks",
                    projectId, response.files(), response.chunks());
        }
        catch (Exception exception) {
            statuses.put(projectId, CodeIndexJobStatus.failed(projectId, startedAt, Instant.now().toString(),
                    publicFailureMessage(exception)));
            log.error("Background code index failed for project {}", projectId, exception);
        }
    }

    /** 将异常映射为面向用户的失败信息：嵌入服务不可用、仓库读取失败、其他异常分别对应不同提示。 */
    private String publicFailureMessage(Exception exception) {
        if (exception instanceof EmbeddingUnavailableException) {
            return exception.getMessage();
        }
        if (exception instanceof IOException) {
            return "无法读取代码仓库，请检查项目路径和文件权限";
        }
        return "代码索引失败，请查看服务日志后重试";
    }
}
