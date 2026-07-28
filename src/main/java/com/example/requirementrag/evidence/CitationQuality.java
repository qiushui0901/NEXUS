package com.example.requirementrag.evidence;

/** Aggregate citation coverage for one generated answer. */
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

    public static CitationQuality empty() {
        return new CitationQuality(0, 0, 0, 0, 0.0, CitationQualityStatus.INSUFFICIENT_EVIDENCE);
    }
}
