package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeQueryIntent;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;

import java.util.List;

/**
 * 多源候选适配器：把现有知识链路（需求文档、语义图、代码知识、符号图）投影为统一 Claim。
 */
public interface MultiSourceCandidateAdapter {

    /** 该适配器负责的来源类型。 */
    SourceType sourceType();

    /** 加载指定项目/版本/查询下的统一 Claim。 */
    List<UnifiedKnowledgeClaim> load(String projectId, String version, String query);

    /**
     * 带查询意图的候选加载：需要按意图门禁的来源（如需求语义候选在 NORMATIVE 意图下的可见性）
     * 覆写此方法；默认忽略意图，与旧契约保持一致。
     */
    default List<UnifiedKnowledgeClaim> load(String projectId, String version, String query,
                                             KnowledgeQueryIntent intent) {
        return load(projectId, version, query);
    }

    /**
     * 带状态反馈的候选加载：返回候选 + 非致命警告（如候选截断），由检索层并入响应 warnings，
     * 保证调用方能感知"结果不完整"。默认实现无额外警告，与旧契约保持一致。
     */
    default CandidateLoad loadDetailed(String projectId, String version, String query,
                                       KnowledgeQueryIntent intent) {
        return new CandidateLoad(load(projectId, version, query, intent), List.of());
    }

    /** 候选加载结果：claims + 非致命警告（如截断、降级），警告使用稳定码。 */
    record CandidateLoad(List<UnifiedKnowledgeClaim> claims, List<String> warnings) {
        public CandidateLoad {
            claims = claims == null ? List.of() : List.copyOf(claims);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}