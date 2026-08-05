package com.example.requirementrag.evidence;

import com.example.requirementrag.model.RagWarning;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 对照当前检索白名单校验模型引用，并累计声明支持度统计。 */
@Service
public class EvidenceCitationService {

    /** 打开一次针对指定证据注册表的引用校验会话。 */
    public Session open(EvidenceRegistry registry) {
        return new Session(registry);
    }

    /** 单次请求的引用校验会话：累计声明计数、支持状态与警告。 */
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

        /**
         * 校验一条结论的引用并累计支持度统计（仅非空文本计入）。
         *
         * @param text                结论文本
         * @param requestedEvidenceIds 模型请求引用的证据 ID 列表
         * @return 校验后的结论，含过滤后的证据 ID 与支持状态
         */
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

        /** 按稳定响应索引生成计划章节的引用投影。 */
        public PlanSectionCitation citeSection(int index, String title, List<String> requestedEvidenceIds) {
            CitedText citation = cite(title, requestedEvidenceIds);
            return new PlanSectionCitation(index, title, citation.evidenceIds(), citation.supportStatus());
        }

        public List<RagWarning> warnings() {
            return List.copyOf(warnings.values());
        }

        /** 汇总引用质量：全部支持为 VERIFIED，部分支持需复核，无任何支持为证据不足。 */
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

        /** 是否存在需关注的引用问题（部分/不受支持或存在警告）。 */
        public boolean hasIssues() {
            return partialClaims > 0 || unsupportedClaims > 0 || !warnings.isEmpty();
        }

        public List<EvidenceRef> references() {
            return registry.references();
        }

        /** 白名单校验：过滤不在注册表中的引用、截断超量引用，并生成相应警告。 */
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

        /** 内部校验结果：过滤后的证据 ID 与支持状态。 */
        private record Validation(List<String> evidenceIds, EvidenceSupportStatus status) {
        }
    }
}
