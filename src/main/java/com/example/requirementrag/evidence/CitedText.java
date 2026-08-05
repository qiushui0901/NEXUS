package com.example.requirementrag.evidence;

import java.util.List;

/** 一条生成结论及其经服务端校验的证据引用。 */
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

    /** 构造无证据支撑的结论（UNSUPPORTED）。 */
    public static CitedText unsupported(String text) {
        return new CitedText(text, List.of(), EvidenceSupportStatus.UNSUPPORTED);
    }
}
