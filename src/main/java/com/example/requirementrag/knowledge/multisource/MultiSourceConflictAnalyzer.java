package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.AnswerStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.MultiSourceConflictType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 多源冲突分析器：按 factKey 聚合统一 Claim，比较规范化值，识别需求-参数、参数-测试、测试结果-预期冲突。
 * 不做文本相似度判断；冲突基于可比较的规范化值。
 */
@Component
public class MultiSourceConflictAnalyzer {

    /** 分析一组统一 Claim，返回冲突描述列表。 */
    public List<String> analyze(List<UnifiedKnowledgeClaim> claims) {
        Map<String, List<UnifiedKnowledgeClaim>> byFactKey = new LinkedHashMap<>();
        for (UnifiedKnowledgeClaim claim : claims) {
            if (claim == null) continue;
            String group = groupKey(claim);
            if (group.isBlank()) continue;
            byFactKey.computeIfAbsent(group, ignored -> new ArrayList<>()).add(claim);
        }
        List<String> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<UnifiedKnowledgeClaim>> entry : byFactKey.entrySet()) {
            List<UnifiedKnowledgeClaim> group = entry.getValue();
            UnifiedKnowledgeClaim requirement = firstType(group, SourceType.REQUIREMENT);
            UnifiedKnowledgeClaim semantic = firstType(group, SourceType.REQUIREMENT_SEMANTIC);
            UnifiedKnowledgeClaim parameter = firstType(group, SourceType.PARAMETER_TABLE);
            UnifiedKnowledgeClaim testCase = firstType(group, SourceType.TEST_CASE);
            UnifiedKnowledgeClaim testResult = firstType(group, SourceType.TEST_RESULT);
            if (requirement != null && parameter != null && !sameValue(requirement.value(), parameter.value())) {
                conflicts.add(MultiSourceConflictType.REQUIREMENT_PARAMETER + ":factKey=" + entry.getKey()
                        + " 需求(" + requirement.value() + ") 与参数表(" + parameter.value() + ")不一致");
            }
            // 需求语义候选与参数事实不一致：语义候选是需求文档派生的期望，冲突以参数表为准展示，
            // 但语义侧值带候选标记，提示需要人工复核（语义候选不能覆盖参数事实）。
            if (semantic != null && parameter != null && !sameValue(semantic.value(), parameter.value())) {
                conflicts.add(MultiSourceConflictType.REQUIREMENT_PARAMETER + ":factKey=" + entry.getKey()
                        + " 需求语义候选(" + semantic.value() + ") 与参数表(" + parameter.value() + ")不一致");
            }
            if (parameter != null && testCase != null && !sameValue(parameter.value(), testCase.value())) {
                conflicts.add(MultiSourceConflictType.PARAMETER_TEST + ":factKey=" + entry.getKey()
                        + " 参数表(" + parameter.value() + ") 与测试预期(" + testCase.value() + ")不一致");
            }
            if (testResult != null && "FAILED".equalsIgnoreCase(testResult.value())) {
                conflicts.add(MultiSourceConflictType.TEST_RESULT_EXPECTATION + ":factKey=" + entry.getKey()
                        + " 最近测试结果为 FAILED");
            }
        }
        return List.copyOf(conflicts);
    }

    /** 返回冲突事实分组键集合（用于排序惩罚，避免解析展示字符串）。 */
    public Set<String> conflictGroups(List<UnifiedKnowledgeClaim> claims) {
        Set<String> groups = new LinkedHashSet<>();
        Map<String, List<UnifiedKnowledgeClaim>> byFactKey = new LinkedHashMap<>();
        for (UnifiedKnowledgeClaim claim : claims) {
            if (claim == null) continue;
            String group = groupKey(claim);
            if (group.isBlank()) continue;
            byFactKey.computeIfAbsent(group, ignored -> new ArrayList<>()).add(claim);
        }
        for (Map.Entry<String, List<UnifiedKnowledgeClaim>> entry : byFactKey.entrySet()) {
            List<UnifiedKnowledgeClaim> group = entry.getValue();
            UnifiedKnowledgeClaim requirement = firstType(group, SourceType.REQUIREMENT);
            UnifiedKnowledgeClaim semantic = firstType(group, SourceType.REQUIREMENT_SEMANTIC);
            UnifiedKnowledgeClaim parameter = firstType(group, SourceType.PARAMETER_TABLE);
            UnifiedKnowledgeClaim testCase = firstType(group, SourceType.TEST_CASE);
            UnifiedKnowledgeClaim testResult = firstType(group, SourceType.TEST_RESULT);
            if ((requirement != null && parameter != null && !sameValue(requirement.value(), parameter.value()))
                    || (semantic != null && parameter != null && !sameValue(semantic.value(), parameter.value()))
                    || (parameter != null && testCase != null && !sameValue(parameter.value(), testCase.value()))
                    || (testResult != null && "FAILED".equalsIgnoreCase(testResult.value()))) {
                groups.add(entry.getKey());
            }
        }
        return Set.copyOf(groups);
    }

    /** 分组键：优先 subject|predicate（跨来源事实对齐），缺失时回退 factKey。 */
    private String groupKey(UnifiedKnowledgeClaim claim) {
        String subjectPredicate = (safe(claim.subject()) + "|" + safe(claim.predicate())).toLowerCase(Locale.ROOT);
        if (!subjectPredicate.isBlank() && !"|".equals(subjectPredicate)) return subjectPredicate;
        return safe(claim.factKey()).toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    /** 根据冲突与可用证据计算结论状态。 */
    public AnswerStatus resolveStatus(List<UnifiedKnowledgeClaim> claims, List<String> conflicts) {
        boolean hasPrimary = claims.stream().anyMatch(claim -> claim.sourceType() == SourceType.REQUIREMENT
                || claim.sourceType() == SourceType.PARAMETER_TABLE);
        boolean hasAny = !claims.isEmpty();
        if (!conflicts.isEmpty()) {
            return hasPrimary ? AnswerStatus.CONFLICTED : AnswerStatus.REVIEW_REQUIRED;
        }
        if (!hasAny) return AnswerStatus.NO_RESULT;
        if (hasPrimary) return AnswerStatus.CONFIRMED;
        if (claims.stream().anyMatch(claim -> claim.sourceType() == SourceType.TEST_RESULT
                && "FAILED".equalsIgnoreCase(claim.value()))) {
            return AnswerStatus.REVIEW_REQUIRED;
        }
        return AnswerStatus.SUPPORTED;
    }

    private UnifiedKnowledgeClaim firstType(List<UnifiedKnowledgeClaim> group, SourceType type) {
        return group.stream().filter(claim -> claim.sourceType() == type).findFirst().orElse(null);
    }

    private boolean sameValue(String left, String right) {
        if (left == null && right == null) return true;
        if (left == null || right == null) return false;
        String a = left.trim();
        String b = right.trim();
        BigDecimal leftDecimal = decimal(a);
        BigDecimal rightDecimal = decimal(b);
        if (leftDecimal != null && rightDecimal != null) return leftDecimal.compareTo(rightDecimal) == 0;
        return a.equalsIgnoreCase(b);
    }

    private BigDecimal decimal(String value) {
        String normalized = value.replace("%", "").replace("分钟", "").replace("min", "").replace(",", "").trim();
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}