package com.example.requirementrag.knowledge.multisource.alignment;

import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.VersionContext;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 版本上下文服务（Phase 1）：把“当前实现”结论绑定 repository + commit（+环境）。
 *
 * <p>commit 取自代码符号图最近一次索引；repository 取自知识项目到代码项目的映射。
 * 不存在的代码快照不会伪造 commit。
 */
@Service
public class VersionContextService {

    private final CodeCentricAlignmentStore store;
    private final CodeSymbolLoader codeSymbolLoader;

    public VersionContextService(CodeCentricAlignmentStore store, CodeSymbolLoader codeSymbolLoader) {
        this.store = store;
        this.codeSymbolLoader = codeSymbolLoader;
    }

    /** 解析并保存当前版本上下文（幂等：按 项目/版本/环境/repository/commit 唯一）。 */
    public VersionContext resolve(String projectId, String businessVersion, String environment) {
        String codeProjectId = codeSymbolLoader.codeProjectId(projectId);
        String commitSha = codeSymbolLoader.load(projectId).commitSha();
        String env = environment == null || environment.isBlank() ? "default" : environment;
        String contextId = "vc:" + sha256(projectId + "|" + businessVersion + "|" + env
                + "|" + codeProjectId + "|" + (commitSha == null ? "" : commitSha)).substring(0, 24);
        VersionContext context = new VersionContext(
                contextId, projectId, businessVersion, codeProjectId, commitSha, env,
                "ACTIVE", null, Instant.now().toString());
        store.upsertVersionContext(context);
        return context;
    }

    /** 为历史业务版本创建不携带当前代码 commit 的上下文，避免把当前实现伪装成历史实现。 */
    public VersionContext resolveHistorical(String projectId, String businessVersion, String environment) {
        String codeProjectId = codeSymbolLoader.codeProjectId(projectId);
        String env = environment == null || environment.isBlank() ? "default" : environment;
        String contextId = "vc:" + sha256(projectId + "|" + businessVersion + "|" + env
                + "|" + codeProjectId + "|historical").substring(0, 24);
        VersionContext context = new VersionContext(
                contextId, projectId, businessVersion, codeProjectId, null, env,
                "HISTORICAL", null, Instant.now().toString());
        store.upsertVersionContext(context);
        return context;
    }

    /** 查询已保存的版本上下文。 */
    public Optional<VersionContext> find(String projectId, String businessVersion, String environment) {
        return store.findVersionContext(projectId, businessVersion, environment);
    }

    /** 列出某业务版本的全部版本上下文（不同环境）。 */
    public List<VersionContext> list(String projectId, String businessVersion) {
        return store.listVersionContexts(projectId, businessVersion);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}