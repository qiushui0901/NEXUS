package com.example.requirementrag.evidence;

import java.util.List;

/** 为旧版开发方案响应字段补充的引用元数据（增量、不影响原有字段）。 */
public record PlanCitationBundle(
        CitedText summary,
        List<CitedText> productUnderstanding,
        List<CitedText> developmentConstraints,
        CitedText similarModule,
        List<CitedText> chainOverview,
        List<PlanSectionCitation> sections,
        List<CitedText> implementationOrder,
        List<CitedText> steps,
        List<CitedText> risks,
        List<EvidenceRef> references,
        CitationQuality quality
) {
    public PlanCitationBundle {
        summary = summary == null ? CitedText.unsupported("") : summary;
        productUnderstanding = copy(productUnderstanding);
        developmentConstraints = copy(developmentConstraints);
        similarModule = similarModule == null ? CitedText.unsupported("") : similarModule;
        chainOverview = copy(chainOverview);
        sections = sections == null ? List.of() : List.copyOf(sections);
        implementationOrder = copy(implementationOrder);
        steps = copy(steps);
        risks = copy(risks);
        references = references == null ? List.of() : List.copyOf(references);
        quality = quality == null ? CitationQuality.empty() : quality;
    }

    /** 构造无任何引用数据的空捆绑。 */
    public static PlanCitationBundle empty() {
        return new PlanCitationBundle(CitedText.unsupported(""), List.of(), List.of(),
                CitedText.unsupported(""), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), CitationQuality.empty());
    }

    private static List<CitedText> copy(List<CitedText> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
