package com.example.requirementrag.evidence;

import java.util.List;

/** One generated conclusion plus server-validated evidence references. */
public record CitedText(
        String text,
        List<String> evidenceIds,
        EvidenceSupportStatus supportStatus
) {
    public CitedText {
        text = text == null ? "" : text;
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        supportStatus = supportStatus == null ? EvidenceSupportStatus.UNSUPPORTED : supportStatus;
    }

    public static CitedText unsupported(String text) {
        return new CitedText(text, List.of(), EvidenceSupportStatus.UNSUPPORTED);
    }
}
