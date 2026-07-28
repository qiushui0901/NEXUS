package com.example.requirementrag.evidence;

import com.example.requirementrag.model.RagWarning;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates model citations against the current retrieval whitelist. */
@Service
public class EvidenceCitationService {

    public Session open(EvidenceRegistry registry) {
        return new Session(registry);
    }

    public static final class Session {
        private static final String STAGE = "evidence.validate";
        private static final int MAX_REFERENCES_PER_CLAIM = 8;

        private final EvidenceRegistry registry;
        private final Map<String, RagWarning> warnings = new LinkedHashMap<>();
        private int totalClaims;
        private int supportedClaims;
        private int partialClaims;
        private int unsupportedClaims;

        private Session(EvidenceRegistry registry) {
            this.registry = registry;
        }

        public CitedText cite(String text, List<String> requestedEvidenceIds) {
            Validation validation = validate(requestedEvidenceIds);
            if (text != null && !text.isBlank()) {
                totalClaims++;
                switch (validation.status()) {
                    case SUPPORTED -> supportedClaims++;
                    case PARTIAL -> partialClaims++;
                    case UNSUPPORTED -> unsupportedClaims++;
                }
            }
            return new CitedText(text, validation.evidenceIds(), validation.status());
        }

        public PlanSectionCitation citeSection(int index, String title, List<String> requestedEvidenceIds) {
            CitedText citation = cite(title, requestedEvidenceIds);
            return new PlanSectionCitation(index, title, citation.evidenceIds(), citation.supportStatus());
        }

        public List<RagWarning> warnings() {
            return List.copyOf(warnings.values());
        }

        public CitationQuality quality() {
            double coverage = totalClaims == 0
                    ? 0.0
                    : (supportedClaims + partialClaims * 0.5) / totalClaims;
            CitationQualityStatus status;
            if (totalClaims > 0 && supportedClaims == totalClaims) {
                status = CitationQualityStatus.VERIFIED;
            } else if (supportedClaims == 0 && partialClaims == 0) {
                status = CitationQualityStatus.INSUFFICIENT_EVIDENCE;
            } else {
                status = CitationQualityStatus.REVIEW_REQUIRED;
            }
            return new CitationQuality(totalClaims, supportedClaims, partialClaims, unsupportedClaims,
                    coverage, status);
        }

        public boolean hasIssues() {
            return partialClaims > 0 || unsupportedClaims > 0 || !warnings.isEmpty();
        }

        public List<EvidenceRef> references() {
            return registry.references();
        }

        private Validation validate(List<String> requestedEvidenceIds) {
            List<String> requested = normalize(requestedEvidenceIds);
            if (requested.isEmpty()) {
                if (!registry.references().isEmpty()) {
                    addWarning("MISSING_EVIDENCE_REFERENCE", "部分生成结论缺少可回查证据");
                }
                return new Validation(List.of(), EvidenceSupportStatus.UNSUPPORTED);
            }

            List<String> accepted = new ArrayList<>();
            boolean rejected = false;
            for (String evidenceId : requested) {
                if (registry.contains(evidenceId) && accepted.size() < MAX_REFERENCES_PER_CLAIM) {
                    accepted.add(evidenceId);
                } else {
                    rejected = true;
                }
            }
            if (rejected) {
                addWarning("INVALID_EVIDENCE_REFERENCE", "部分生成引用不属于本次检索证据，已过滤");
            }
            if (accepted.isEmpty()) {
                return new Validation(List.of(), EvidenceSupportStatus.UNSUPPORTED);
            }
            return new Validation(List.copyOf(accepted),
                    rejected ? EvidenceSupportStatus.PARTIAL : EvidenceSupportStatus.SUPPORTED);
        }

        private List<String> normalize(List<String> values) {
            if (values == null || values.isEmpty()) return List.of();
            Set<String> unique = new LinkedHashSet<>();
            for (String value : values) {
                if (value == null) continue;
                String normalized = value.trim();
                if (!normalized.isBlank()) unique.add(normalized);
            }
            return List.copyOf(unique);
        }

        private void addWarning(String code, String message) {
            warnings.putIfAbsent(code + "|" + message, new RagWarning(STAGE, code, message, 0));
        }

        private record Validation(List<String> evidenceIds, EvidenceSupportStatus status) {
        }
    }
}
