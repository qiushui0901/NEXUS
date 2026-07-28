package com.example.requirementrag.evidence;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceCitationServiceTest {

    private final EvidenceCitationService service = new EvidenceCitationService();

    @Test
    void acceptsAndDeduplicatesWhitelistedEvidenceIds() {
        EvidenceCitationService.Session session = service.open(registryWithRequirement());

        CitedText cited = session.cite("结论", List.of("requirement:req-1", "requirement:req-1"));

        assertThat(cited.evidenceIds()).containsExactly("requirement:req-1");
        assertThat(cited.supportStatus()).isEqualTo(EvidenceSupportStatus.SUPPORTED);
        assertThat(session.warnings()).isEmpty();
        assertThat(session.quality().status()).isEqualTo(CitationQualityStatus.VERIFIED);
        assertThat(session.quality().coverageRate()).isEqualTo(1.0);
    }

    @Test
    void filtersUnknownIdsAndMarksClaimAsPartiallySupported() {
        EvidenceCitationService.Session session = service.open(registryWithRequirement());

        CitedText cited = session.cite("结论", List.of("requirement:req-1", "requirement:unknown"));

        assertThat(cited.evidenceIds()).containsExactly("requirement:req-1");
        assertThat(cited.supportStatus()).isEqualTo(EvidenceSupportStatus.PARTIAL);
        assertThat(session.warnings()).extracting(warning -> warning.code())
                .containsExactly("INVALID_EVIDENCE_REFERENCE");
        assertThat(session.quality().status()).isEqualTo(CitationQualityStatus.REVIEW_REQUIRED);
        assertThat(session.quality().coverageRate()).isEqualTo(0.5);
    }

    @Test
    void marksMissingReferencesAsUnsupportedWhenEvidenceWasAvailable() {
        EvidenceCitationService.Session session = service.open(registryWithRequirement());

        CitedText cited = session.cite("结论", List.of());

        assertThat(cited.supportStatus()).isEqualTo(EvidenceSupportStatus.UNSUPPORTED);
        assertThat(session.warnings()).extracting(warning -> warning.code())
                .containsExactly("MISSING_EVIDENCE_REFERENCE");
        assertThat(session.quality().unsupportedClaims()).isEqualTo(1);
        assertThat(session.quality().status()).isEqualTo(CitationQualityStatus.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void doesNotCreateMissingCitationWarningWhenRetrievalReturnedNoEvidence() {
        EvidenceRegistry empty = EvidenceRegistry.from(new RetrievalBundle("query", RetrievalProfile.DEVELOPMENT_PLAN,
                "project-a", null, null, List.of(), List.of()));
        EvidenceCitationService.Session session = service.open(empty);

        session.cite("没有检索结果", List.of());

        assertThat(session.warnings()).isEmpty();
        assertThat(session.quality().status()).isEqualTo(CitationQualityStatus.INSUFFICIENT_EVIDENCE);
    }

    private EvidenceRegistry registryWithRequirement() {
        ChunkRecord requirement = new ChunkRecord("req-1", "doc-a", "1.0", "docs/spec.md", "section-a",
                "业务规则", "业务规则", "hash-a", 1, 1);
        return EvidenceRegistry.from(new RetrievalBundle("query", RetrievalProfile.DEVELOPMENT_PLAN,
                "project-a", "doc-a", "1.0", List.of(requirement), List.of()));
    }
}
