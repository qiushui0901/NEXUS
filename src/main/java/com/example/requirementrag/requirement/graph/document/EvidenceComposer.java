package com.example.requirementrag.requirement.graph.document;

import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.EvidenceBundle;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.EvidenceItem;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.SupportMode;
import org.springframework.stereotype.Component;

import java.util.List;

/** 证据组合（Phase 4）：把单个/多个锚点组合为可回查的证据包。 */
@Component
public class EvidenceComposer {

    public EvidenceBundle compose(String bundleId, List<EvidenceItem> items) {
        List<EvidenceItem> safe = items == null ? List.of() : List.copyOf(items);
        SupportMode mode;
        if (safe.isEmpty()) {
            mode = SupportMode.UNAVAILABLE;
        } else if (safe.size() == 1) {
            mode = SupportMode.DIRECT;
        } else {
            mode = SupportMode.COMPOSITE_SUPPORTED;
        }
        return new EvidenceBundle(bundleId, mode, safe);
    }

    public EvidenceItem item(String sourceAnchorId, String windowId, String quote,
                             int startOffset, int endOffset, String role, String extractionMethod) {
        return new EvidenceItem(sourceAnchorId, windowId, quote, startOffset, endOffset, role, extractionMethod);
    }
}