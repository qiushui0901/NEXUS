package com.example.requirementrag.evidence;

import java.util.List;

/** 按稳定响应索引关联的既有计划章节的引用投影。 */
public record PlanSectionCitation(
        int sectionIndex,
        String title,
        List<String> evidenceIds,
        EvidenceSupportStatus supportStatus
) {
    public PlanSectionCitation {
        title = title == null ? "" : title;
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        supportStatus = supportStatus == null ? EvidenceSupportStatus.UNSUPPORTED : supportStatus;
    }
}
