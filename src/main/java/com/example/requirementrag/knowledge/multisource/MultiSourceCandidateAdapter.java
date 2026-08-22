package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
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
}