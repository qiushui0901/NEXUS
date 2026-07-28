package com.example.requirementrag.evidence;

import java.util.List;

/** Citation projection for an existing plan section, associated by stable response index. */
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
