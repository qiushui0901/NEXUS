package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import com.example.requirementrag.model.CodeChunk;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * CODE 来源适配器：把代码检索命中（符号）投影为统一 Claim，保留 repositoryId/commitSha/filePath/符号位置。
 * 作为实现证据（SECONDARY），不覆盖需求规范。
 */
@Component
public class CodeKnowledgeCandidateAdapter implements MultiSourceCandidateAdapter {
    private final CodeKnowledgeService codeKnowledgeService;

    public CodeKnowledgeCandidateAdapter(CodeKnowledgeService codeKnowledgeService) {
        this.codeKnowledgeService = codeKnowledgeService;
    }

    @Override
    public SourceType sourceType() {
        return SourceType.CODE;
    }

    @Override
    public List<UnifiedKnowledgeClaim> load(String projectId, String version, String query) {
        try {
            return loadUnchecked(projectId, version, query);
        } catch (RuntimeException exception) {
            // 旧契约：直接调用 load() 时降级为空候选；故障告警走 loadDetailed（检索层唯一入口）。
            return List.of();
        }
    }

    @Override
    public MultiSourceCandidateAdapter.CandidateLoad loadDetailed(String projectId, String version,
                                                                  String query,
                                                                  MultiSourceKnowledgeModels.KnowledgeQueryIntent intent) {
        try {
            return new MultiSourceCandidateAdapter.CandidateLoad(loadUnchecked(projectId, version, query), List.of());
        } catch (RuntimeException exception) {
            // 中（第七批 Review M4）：代码检索故障不能静默成"无代码结果"——返回稳定告警码并入检索响应，
            // 调用方与用户可区分"检索故障"与"确实无命中"（此前 catch 吞异常，上层只见空候选）。
            return new MultiSourceCandidateAdapter.CandidateLoad(List.of(), List.of("CODE_SEARCH_FAILED"));
        }
    }

    private List<UnifiedKnowledgeClaim> loadUnchecked(String projectId, String version, String query)
            throws RuntimeException {
        if (query == null || query.isBlank()) return List.of();
        Set<UnifiedKnowledgeClaim> claims = new LinkedHashSet<>();
        List<CodeChunk> hits = codeKnowledgeService.search(query, projectId, 200);
        for (CodeChunk chunk : hits == null ? List.<CodeChunk>of() : hits) {
            if (chunk == null) continue;
            String symbolName = safe(chunk.symbolName());
            String symbolType = safe(chunk.symbolType());
            String subject = symbolName.isBlank() ? safe(chunk.filePath()) : symbolName;
            String value = safe(chunk.businessDescCn()).isBlank() ? safe(chunk.text()) : chunk.businessDescCn();
            String module = safe(chunk.module()).isBlank() ? safe(chunk.className()) : chunk.module();
            String evidenceLocation = safe(chunk.filePath()) + "#" + subject
                    + ":" + chunk.startLine() + "-" + chunk.endLine()
                    + "@" + safe(chunk.commitSha());
            claims.add(new UnifiedKnowledgeClaim(
                    chunk.id(), projectId, version,
                    factKey(projectId, version, symbolType, subject),
                    subject, symbolType.isBlank() ? "symbol" : symbolType, value, "TEXT", null,
                    SourceType.CODE, Authority.SECONDARY, KnowledgeStatus.SUPPORTED,
                    version, null, evidenceLocation, module));
        }
        return List.copyOf(claims);
    }

    private String factKey(String projectId, String version, String left, String right) {
        return (projectId + "|" + version + "|" + safe(left) + "|" + safe(right)).toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}