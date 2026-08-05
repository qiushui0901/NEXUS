package com.example.requirementrag.evidence;

/** 一次生成答案的引用覆盖率聚合：支持/部分/不支持声明数与覆盖率。 */
public record CitationQuality(
        int totalClaims,
        int supportedClaims,
        int partialClaims,
        int unsupportedClaims,
        double coverageRate,
        CitationQualityStatus status
) {
    public CitationQuality {
        coverageRate = Math.max(0.0, Math.min(1.0, coverageRate));
        status = status == null ? CitationQualityStatus.INSUFFICIENT_EVIDENCE : status;
    }

    /** 构造没有任何声明时的空质量对象（证据不足）。 */
    public static CitationQuality empty() {
        return new CitationQuality(0, 0, 0, 0, 0.0, CitationQualityStatus.INSUFFICIENT_EVIDENCE);
    }
}
