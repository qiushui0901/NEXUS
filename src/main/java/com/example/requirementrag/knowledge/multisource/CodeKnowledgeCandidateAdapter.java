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
        if (query == null || query.isBlank()) return List.of();
        Set<UnifiedKnowledgeClaim> claims = new LinkedHashSet<>();
        try {
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
        } catch (RuntimeException exception) {
            // 代码检索不可用时降级为空候选，不把故障伪装成无结果事实。
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