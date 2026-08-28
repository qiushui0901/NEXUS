package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.AssessmentItem;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntityView;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.FactAssessment;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.FactRef;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.VersionFactBlock;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityQueryPlan;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 实体事实优先级与实现偏差（dev md §9）。
 *
 * <p>按问题类型选择主证据视图：当前行为 CODE &gt; TEST_RESULT &gt; PARAMETER_TABLE &gt; REQUIREMENT；
 * 当前数值 PARAMETER_TABLE &gt; CODE &gt; TEST_RESULT &gt; REQUIREMENT。实现偏差由确定性信号生成：
 * 需求目标 ≠ 参数值（REQUIREMENT_PARAMETER_MISMATCH）、FAILED 测试（REQUIREMENT_IMPLEMENTATION_GAP）、
 * 代码+参数+失败测试并存（CODE_PARAMETER_MISMATCH）。**不做来源仲裁、不修改任何来源事实。**
 */
@Service
public class EntityFactPriorityService {

    /** 分区容量上限（与聚合器一致）。 */
    private static final int CAP = 20;

    public FactAssessment assess(EntityQueryPlan plan, EntityView view) {
        List<FactRef> code = view.currentFacts().code();
        List<FactRef> parameters = view.currentFacts().parameterTables();
        List<FactRef> testResults = view.currentFacts().testResults();
        List<FactRef> latestRequirements = latestRequirements(view);

        // 当前行为：CODE > TEST_RESULT > PARAMETER_TABLE > REQUIREMENT
        List<AssessmentItem> currentBehavior = new ArrayList<>();
        List<FactRef> behaviorSource = pickBehaviorSource(code, testResults, parameters, latestRequirements);
        for (FactRef ref : behaviorSource) {
            currentBehavior.add(new AssessmentItem(
                    "CURRENT_BEHAVIOR", valueOf(ref), ref.sourceType(), statusFor(ref)));
            if (currentBehavior.size() >= CAP) break;
        }

        // 当前数值：PARAMETER_TABLE 优先
        List<AssessmentItem> currentValues = new ArrayList<>();
        for (FactRef ref : parameters) {
            currentValues.add(new AssessmentItem(
                    "CURRENT_VALUE", valueOf(ref), ref.sourceType(), statusFor(ref)));
            if (currentValues.size() >= CAP) break;
        }

        // 验证：TEST_RESULT；FAILED → REVIEW_REQUIRED；无证据 → UNVERIFIED
        List<AssessmentItem> validation = new ArrayList<>();
        for (FactRef ref : testResults) {
            boolean failed = isFailed(ref);
            validation.add(new AssessmentItem("VALIDATION", valueOf(ref), ref.sourceType(),
                    failed ? "REVIEW_REQUIRED" : statusFor(ref)));
            if (validation.size() >= CAP) break;
        }

        // 需求目标：最新有效需求
        List<AssessmentItem> requirementTarget = new ArrayList<>();
        for (FactRef ref : latestRequirements) {
            requirementTarget.add(new AssessmentItem(
                    "REQUIREMENT_TARGET", valueOf(ref), ref.sourceType(), statusFor(ref)));
            if (requirementTarget.size() >= CAP) break;
        }

        // 实现偏差（确定性信号，可审计）
        List<AssessmentItem> gaps = new ArrayList<>();
        boolean anyFailed = testResults.stream().anyMatch(EntityFactPriorityService::isFailed);
        for (FactRef requirement : latestRequirements) {
            for (FactRef parameter : parameters) {
                if (!sameFactSubject(requirement, parameter)) {
                    continue;
                }
                String reqTarget = valueOf(requirement);
                String paramValue = valueOf(parameter);
                if (!reqTarget.isBlank() && !paramValue.isBlank() && !sameValue(requirement, parameter)) {
                    gaps.add(new AssessmentItem("REQUIREMENT_PARAMETER_MISMATCH",
                            "需求目标=" + reqTarget + "，当前数值表=" + paramValue,
                            "REQUIREMENT/PARAMETER_TABLE", "CONFLICTED"));
                }
            }
        }
        if (anyFailed) {
            gaps.add(new AssessmentItem("REQUIREMENT_IMPLEMENTATION_GAP",
                    "存在 FAILED 测试，需求目标可能尚未实现", "TEST_RESULT", "REVIEW_REQUIRED"));
        }
        if (!code.isEmpty() && !parameters.isEmpty() && anyFailed) {
            gaps.add(new AssessmentItem("CODE_PARAMETER_MISMATCH",
                    "存在代码实现与数值表配置，但相关测试失败，代码可能未实现参数值",
                    "CODE/PARAMETER_TABLE", "REVIEW_REQUIRED"));
        }

        return new FactAssessment(currentBehavior, currentValues, validation,
                requirementTarget, gaps);
    }

    /** 当前行为主证据：CODE > TEST_RESULT > PARAMETER_TABLE > REQUIREMENT。 */
    private List<FactRef> pickBehaviorSource(List<FactRef> code, List<FactRef> testResults,
                                             List<FactRef> parameters, List<FactRef> requirements) {
        if (!code.isEmpty()) return code;
        if (!testResults.isEmpty()) return testResults;
        if (!parameters.isEmpty()) return parameters;
        return requirements;
    }

    /** 事实状态：来源 Evidence 才能支持结论；代码位置只能证明可回源，不能证明具体行为。 */
    private static String statusFor(FactRef ref) {
        boolean hasEvidence = ref.evidenceIds() != null && ref.evidenceIds().stream()
                .anyMatch(id -> id != null && !id.startsWith("code:"));
        if (hasEvidence) return "SUPPORTED";
        if ("CODE".equals(ref.sourceType()) && ref.location() != null && !ref.location().isBlank()) {
            return "TRACEABLE";
        }
        return "UNVERIFIED";
    }

    /** 最新业务版本块中的需求事实（按版本数值取最新块的，而非遍历顺序第一条）。 */
    private List<FactRef> latestRequirements(EntityView view) {
        if (view.timeline().isEmpty()) {
            return List.of();
        }
        VersionFactBlock latest = null;
        for (VersionFactBlock block : view.timeline()) {
            if (!block.requirements().isEmpty() && (latest == null
                    || versionCompare(block.businessVersion(), latest.businessVersion()) > 0)) {
                latest = block;
            }
        }
        return latest == null ? List.of() : latest.requirements();
    }

    /** 数值感知版本比较（5.9 < 5.10），与知识库版本排序一致。 */
    private static int versionCompare(String left, String right) {
        String[] l = left.split("[.\\-]", -1);
        String[] r = right.split("[.\\-]", -1);
        int max = Math.max(l.length, r.length);
        for (int i = 0; i < max; i++) {
            String a = i < l.length ? l[i] : "0";
            String b = i < r.length ? r[i] : "0";
            try {
                int comparison = Long.compare(Long.parseLong(a), Long.parseLong(b));
                if (comparison != 0) return comparison;
            } catch (NumberFormatException ignored) {
                int comparison = a.compareTo(b);
                if (comparison != 0) return comparison;
            }
        }
        return 0;
    }

    private boolean sameFactSubject(FactRef left, FactRef right) {
        String a = factIdentity(left);
        String b = factIdentity(right);
        return !a.isBlank() && a.equalsIgnoreCase(b);
    }

    /** factKey 的最后一段是来源谓词（如 rule/value），不能参与跨来源事实配对。 */
    private static String factIdentity(FactRef ref) {
        if (ref.factKey() != null && !ref.factKey().isBlank()) {
            String[] parts = ref.factKey().split("\\|", -1);
            if (parts.length >= 4) {
                return String.join("|", java.util.Arrays.copyOf(parts, parts.length - 1))
                        .toLowerCase(java.util.Locale.ROOT);
            }
            return ref.factKey().trim().toLowerCase(java.util.Locale.ROOT);
        }
        return normalizeKey(ref.subject());
    }

    /** 比较值时同时检查显式/内嵌单位；范围与非数值按完整值比较，不能误当成单个数字。 */
    static boolean sameValue(FactRef left, FactRef right) {
        String leftUnit = effectiveUnit(left);
        String rightUnit = effectiveUnit(right);
        if (!leftUnit.equals(rightUnit)) return false;
        String leftValue = normalizeValue(left.objectValue(), leftUnit);
        String rightValue = normalizeValue(right.objectValue(), rightUnit);
        if (leftValue.equals(rightValue)) return true;
        if (!isSingleNumber(leftValue) || !isSingleNumber(rightValue)) return false;
        try {
            return new java.math.BigDecimal(leftValue).compareTo(new java.math.BigDecimal(rightValue)) == 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    static boolean sameNumeric(String left, String right) {
        return sameValue(new FactRef(null, null, "", "", left, "", "", List.of(), null),
                new FactRef(null, null, "", "", right, "", "", List.of(), null));
    }

    private static String normalizeKey(String subject) {
        return subject == null ? "" : subject.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String effectiveUnit(FactRef ref) {
        String explicit = normalizeUnit(ref.unit());
        if (!explicit.isBlank()) return explicit;
        String value = ref.objectValue() == null ? "" : ref.objectValue().trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[-+]?\\d+(?:\\.\\d+)?(?:\\s*)([^0-9.+-].*)$").matcher(value);
        return matcher.find() ? normalizeUnit(matcher.group(1)) : "";
    }

    private static String normalizeUnit(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizeValue(String value, String unit) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", "");
        if (!unit.isBlank()) {
            normalized = normalized.replaceAll("(?i)" + java.util.regex.Pattern.quote(unit) + "$", "");
        }
        return normalized;
    }

    private static boolean isSingleNumber(String value) {
        return value.matches("[-+]?\\d+(?:\\.\\d+)?");
    }

    static boolean isFailed(FactRef ref) {
        String value = ref.objectValue() == null ? "" : ref.objectValue().toUpperCase(java.util.Locale.ROOT);
        return value.contains("FAIL") || value.contains("ERROR") || value.contains("失败");
    }

    private String valueOf(FactRef ref) {
        String subject = ref.subject() == null || ref.subject().isBlank() ? ref.externalId() : ref.subject();
        String value = ref.objectValue();
        String unit = ref.unit();
        if (value == null || value.isBlank()) {
            return subject == null ? "" : subject;
        }
        String joined = subject == null || subject.isBlank() ? value : subject + "=" + value;
        return unit == null || unit.isBlank() ? joined : joined + unit;
    }
}