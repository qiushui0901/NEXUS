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
 * 多源冲突分析器：按分组键聚合统一 Claim，比较规范化值，识别需求-参数、参数-测试、测试结果-预期冲突。
 * 不做文本相似度判断；冲突基于可比较的规范化值。
 *
 * <p>分组采用两个维度，各有分工：</p>
 * <ul>
 *   <li><b>内部冲突（VERSION_INTERNAL）按 factKey 分组</b>：同来源、同 factKey 的多个不同值才是
 *       "同一事实不一致"；不同 factKey（不同领域事实）即使 subject|predicate 相同也不误合并；</li>
 *   <li><b>跨来源冲突按 subject|predicate 分组，且按来源的"值集合"比较（不取第一条）</b>：
 *       配对双方值集合不同且各自唯一、且任一 Claim 对 factKey 一致 → 确定冲突；否则（factKey 未对齐，
 *       或任一来源存在多个不同值）报 {@code POTENTIAL_CROSS_SOURCE_CONFLICT}——subject|predicate 配对
 *       或多值归属是推测，待 BusinessConcept/统一词汇表对齐后升级为确定冲突。</li>
 * </ul>
 *
 * <p>冲突分级对下游的影响：</p>
 * <ul>
 *   <li>确定冲突：可把结论状态推成 {@code CONFLICTED}，并参与 conflictPenalty 排序惩罚；</li>
 *   <li>POTENTIAL：结论状态最多到 {@code REVIEW_REQUIRED}，不参与排序惩罚（不按确定冲突扣分）。</li>
 * </ul>
 */
@Component
public class MultiSourceConflictAnalyzer {
    /** "数值 + 可选单位"提取：30秒 / 30.0 s / 1,000 / 5% 等统一为 (number, unit)。 */
    private static final java.util.regex.Pattern NUMBER_WITH_UNIT =
            java.util.regex.Pattern.compile("^(-?[0-9]+(?:\\.[0-9]+)?)\\s*(.*)$");
    /** 裸数值的规范形式（内部判定 claim.unit 是否需要补齐）。 */
    private static final java.util.regex.Pattern BARE_NUMBER =
            java.util.regex.Pattern.compile("-?[0-9]+(\\.[0-9]+)?");
    /** 潜在（非确定对齐）跨源冲突的消息前缀；resolveStatus / conflictGroups 依赖该前缀分级。 */
    private static final String POTENTIAL_PREFIX = "POTENTIAL_CROSS_SOURCE_CONFLICT:";

    /** 分析一组统一 Claim，返回冲突描述列表。 */
    public List<String> analyze(List<UnifiedKnowledgeClaim> claims) {
        Map<String, List<UnifiedKnowledgeClaim>> byInternalKey = new LinkedHashMap<>();
        Map<String, List<UnifiedKnowledgeClaim>> byCrossKey = new LinkedHashMap<>();
        for (UnifiedKnowledgeClaim claim : claims) {
            if (claim == null) continue;
            String internalKey = internalGroupKey(claim);
            String crossKey = crossSourceGroupKey(claim);
            if (!internalKey.isBlank()) {
                byInternalKey.computeIfAbsent(internalKey, ignored -> new ArrayList<>()).add(claim);
            }
            if (!crossKey.isBlank()) {
                byCrossKey.computeIfAbsent(crossKey, ignored -> new ArrayList<>()).add(claim);
            }
        }
        List<String> conflicts = new ArrayList<>();
        // 内部冲突：factKey 维度分组，同来源多个不同规范化值才算不一致。
        for (Map.Entry<String, List<UnifiedKnowledgeClaim>> entry : byInternalKey.entrySet()) {
            addInternalValueConflicts(entry.getValue(), entry.getKey(), conflicts);
        }
        // 跨来源冲突：subject|predicate 维度分组，按来源值集合比较，与输入顺序无关。
        for (Map.Entry<String, List<UnifiedKnowledgeClaim>> entry : byCrossKey.entrySet()) {
            addCrossSourceConflicts(entry.getValue(), entry.getKey(), conflicts);
        }
        return List.copyOf(conflicts);
    }

    /** 跨源配对：需求-参数、语义候选-参数、参数-测试；任一来源存在多值时不做确定结论。 */
    private void addCrossSourceConflicts(List<UnifiedKnowledgeClaim> group, String groupKey,
                                         List<String> conflicts) {
        Map<SourceType, List<UnifiedKnowledgeClaim>> bySource = new LinkedHashMap<>();
        for (UnifiedKnowledgeClaim claim : group) {
            if (claim.value() == null || claim.value().isBlank()) continue;
            bySource.computeIfAbsent(claim.sourceType(), ignored -> new ArrayList<>()).add(claim);
        }
        addPairConflict(bySource.get(SourceType.REQUIREMENT), bySource.get(SourceType.PARAMETER_TABLE),
                MultiSourceConflictType.REQUIREMENT_PARAMETER, groupKey, "需求", "参数表", conflicts);
        // 需求语义候选与参数事实不一致：语义侧值带候选标记，提示需要人工复核（语义候选不能覆盖参数事实）。
        addPairConflict(bySource.get(SourceType.REQUIREMENT_SEMANTIC), bySource.get(SourceType.PARAMETER_TABLE),
                MultiSourceConflictType.REQUIREMENT_PARAMETER, groupKey, "需求语义候选", "参数表", conflicts);
        addPairConflict(bySource.get(SourceType.PARAMETER_TABLE), bySource.get(SourceType.TEST_CASE),
                MultiSourceConflictType.PARAMETER_TEST, groupKey, "参数表", "测试预期", conflicts);
        List<UnifiedKnowledgeClaim> testResults = bySource.getOrDefault(SourceType.TEST_RESULT, List.of());
        if (testResults.stream().anyMatch(claim -> "FAILED".equalsIgnoreCase(claim.value()))) {
            conflicts.add(MultiSourceConflictType.TEST_RESULT_EXPECTATION + ":factKey=" + groupKey
                    + " 最近测试结果为 FAILED");
        }
    }

    /**
     * 两个来源的值集合比较（与输入顺序无关）：
     * 集合相同 → 无冲突；集合不同 → 双方各自唯一且 factKey 对齐为确定冲突，否则为 POTENTIAL
     * （factKey 未对齐，或某来源存在多个不同值——具体与哪一条配对无法确定，需人工复核）。
     */
    private void addPairConflict(List<UnifiedKnowledgeClaim> left, List<UnifiedKnowledgeClaim> right,
                                 MultiSourceConflictType type, String groupKey, String leftLabel,
                                 String rightLabel, List<String> conflicts) {
        if (left == null || left.isEmpty() || right == null || right.isEmpty()) return;
        Map<String, String> leftByCanonical = canonicalValues(left);
        Map<String, String> rightByCanonical = canonicalValues(right);
        if (leftByCanonical.keySet().equals(rightByCanonical.keySet())) return;
        boolean singleEach = leftByCanonical.size() == 1 && rightByCanonical.size() == 1;
        boolean confirmed = singleEach && anyFactKeyAligned(left, right);
        conflicts.add((confirmed ? "" : POTENTIAL_PREFIX) + type + ":factKey=" + groupKey
                + " " + leftLabel + "(" + sortedDisplay(leftByCanonical) + ") 与"
                + rightLabel + "(" + sortedDisplay(rightByCanonical) + ")不一致"
                + (singleEach ? "" : "（某来源存在多个值，需人工复核）"));
    }

    /** 展示值按字典序排序：消息内容与 Claim 输入顺序无关。 */
    private String sortedDisplay(Map<String, String> canonicalToDisplay) {
        return canonicalToDisplay.values().stream().sorted().reduce((a, b) -> a + "/" + b).orElse("");
    }

    /** 该来源的规范化值 → 首次出现的原始展示值。 */
    private Map<String, String> canonicalValues(List<UnifiedKnowledgeClaim> claims) {
        Map<String, String> values = new LinkedHashMap<>();
        for (UnifiedKnowledgeClaim claim : claims) {
            values.putIfAbsent(canonical(claim), claim.value().trim());
        }
        return values;
    }

    /** 配对两侧是否存在任一 factKey 相等的 Claim 对 → 确定对齐；否则对齐只是 subject|predicate 推测。 */
    private boolean anyFactKeyAligned(List<UnifiedKnowledgeClaim> left, List<UnifiedKnowledgeClaim> right) {
        Set<String> rightKeys = new LinkedHashSet<>();
        for (UnifiedKnowledgeClaim claim : right) {
            String key = safe(claim.factKey()).toLowerCase(Locale.ROOT);
            if (!key.isBlank()) rightKeys.add(key);
        }
        return left.stream().map(claim -> safe(claim.factKey()).toLowerCase(Locale.ROOT))
                .anyMatch(rightKeys::contains);
    }

    /** 返回参与排序惩罚的冲突分组键：内部 factKey 冲突 + 确定跨源冲突（POTENTIAL 不惩罚）。 */
    public Set<String> conflictGroups(List<UnifiedKnowledgeClaim> claims) {
        Set<String> groups = new LinkedHashSet<>();
        Map<String, List<UnifiedKnowledgeClaim>> byInternalKey = new LinkedHashMap<>();
        Map<String, List<UnifiedKnowledgeClaim>> byCrossKey = new LinkedHashMap<>();
        for (UnifiedKnowledgeClaim claim : claims) {
            if (claim == null) continue;
            byInternalKey.computeIfAbsent(internalGroupKey(claim), ignored -> new ArrayList<>()).add(claim);
            byCrossKey.computeIfAbsent(crossSourceGroupKey(claim), ignored -> new ArrayList<>()).add(claim);
        }
        for (Map.Entry<String, List<UnifiedKnowledgeClaim>> entry : byInternalKey.entrySet()) {
            if (hasInternalValueConflict(entry.getValue())) groups.add(entry.getKey());
        }
        for (Map.Entry<String, List<UnifiedKnowledgeClaim>> entry : byCrossKey.entrySet()) {
            if (hasConfirmedCrossSourceConflict(entry.getValue())) groups.add(entry.getKey());
        }
        return Set.copyOf(groups);
    }

    /** 该 Claim 的冲突分组键集合（内部 + 跨来源两个维度），供排序惩罚使用。 */
    public Set<String> groupKeys(UnifiedKnowledgeClaim claim) {
        Set<String> keys = new LinkedHashSet<>(2);
        String internal = internalGroupKey(claim);
        String cross = crossSourceGroupKey(claim);
        if (!internal.isBlank()) keys.add(internal);
        if (!cross.isBlank()) keys.add(cross);
        return keys;
    }

    /** 内部分组键：优先 factKey（不同领域事实不误合并），缺失时回退 subject|predicate。 */
    private String internalGroupKey(UnifiedKnowledgeClaim claim) {
        String factKey = safe(claim.factKey()).toLowerCase(Locale.ROOT);
        if (!factKey.isBlank()) return factKey;
        return (safe(claim.subject()) + "|" + safe(claim.predicate())).toLowerCase(Locale.ROOT);
    }

    /** 跨来源分组键：优先 subject|predicate（跨源 factKey 口径不一致时的对齐手段），缺失时回退 factKey。 */
    private String crossSourceGroupKey(UnifiedKnowledgeClaim claim) {
        String subjectPredicate = (safe(claim.subject()) + "|" + safe(claim.predicate())).toLowerCase(Locale.ROOT);
        if (!subjectPredicate.isBlank() && !"|".equals(subjectPredicate)) return subjectPredicate;
        return safe(claim.factKey()).toLowerCase(Locale.ROOT);
    }

    /** 同来源同一事实存在多个不同值（跨窗口/重复抽取）时生成内部冲突，避免“只取第一条”。 */
    private void addInternalValueConflicts(List<UnifiedKnowledgeClaim> group, String factKey,
                                           List<String> conflicts) {
        Map<SourceType, Map<String, String>> valuesBySource = new LinkedHashMap<>();
        for (UnifiedKnowledgeClaim claim : group) {
            if (claim == null || claim.value() == null || claim.value().isBlank()) continue;
            valuesBySource.computeIfAbsent(claim.sourceType(), ignored -> new LinkedHashMap<>())
                    .putIfAbsent(canonical(claim), claim.value().trim());
        }
        for (Map.Entry<SourceType, Map<String, String>> sourceEntry : valuesBySource.entrySet()) {
            if (sourceEntry.getValue().size() < 2) continue;
            String values = sortedDisplay(sourceEntry.getValue()).replace("/", " vs ");
            conflicts.add(MultiSourceConflictType.VERSION_INTERNAL + ":factKey=" + factKey
                    + " 同来源(" + sourceEntry.getKey() + ")内部不一致: " + values);
        }
    }

    private boolean hasInternalValueConflict(List<UnifiedKnowledgeClaim> group) {
        Map<SourceType, Set<String>> distinctValuesBySource = new LinkedHashMap<>();
        for (UnifiedKnowledgeClaim claim : group) {
            if (claim == null || claim.value() == null || claim.value().isBlank()) continue;
            distinctValuesBySource.computeIfAbsent(claim.sourceType(), ignored -> new LinkedHashSet<>())
                    .add(canonical(claim));
        }
        return distinctValuesBySource.values().stream().anyMatch(values -> values.size() >= 2);
    }

    /** 是否存在确定跨源冲突（与 addPairConflict 的确定判据一致）：POTENTIAL 不算，不参与惩罚。 */
    private boolean hasConfirmedCrossSourceConflict(List<UnifiedKnowledgeClaim> group) {
        Map<SourceType, List<UnifiedKnowledgeClaim>> bySource = new LinkedHashMap<>();
        for (UnifiedKnowledgeClaim claim : group) {
            if (claim.value() == null || claim.value().isBlank()) continue;
            bySource.computeIfAbsent(claim.sourceType(), ignored -> new ArrayList<>()).add(claim);
        }
        if (confirmedPair(bySource.get(SourceType.REQUIREMENT), bySource.get(SourceType.PARAMETER_TABLE))
                || confirmedPair(bySource.get(SourceType.REQUIREMENT_SEMANTIC),
                bySource.get(SourceType.PARAMETER_TABLE))
                || confirmedPair(bySource.get(SourceType.PARAMETER_TABLE), bySource.get(SourceType.TEST_CASE))) {
            return true;
        }
        return bySource.getOrDefault(SourceType.TEST_RESULT, List.of()).stream()
                .anyMatch(claim -> "FAILED".equalsIgnoreCase(claim.value()));
    }

    /** 值集合不同 + 双方各自唯一 + factKey 对齐 → 确定冲突。 */
    private boolean confirmedPair(List<UnifiedKnowledgeClaim> left, List<UnifiedKnowledgeClaim> right) {
        if (left == null || left.isEmpty() || right == null || right.isEmpty()) return false;
        Map<String, String> leftValues = canonicalValues(left);
        Map<String, String> rightValues = canonicalValues(right);
        return !leftValues.keySet().equals(rightValues.keySet())
                && leftValues.size() == 1 && rightValues.size() == 1
                && anyFactKeyAligned(left, right);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 根据冲突与可用证据计算结论状态。冲突分级：
     * 存在确定冲突且冲突涉及的分组内有 PRIMARY 来源 → CONFLICTED；仅 POTENTIAL 冲突
     * 或冲突组内无 PRIMARY 来源（如孤立失败测试结果）→ 最多 REVIEW_REQUIRED——
     * 不因候选集其它位置存在无关的 PRIMARY Claim 把整个查询推成 CONFLICTED。
     */
    public AnswerStatus resolveStatus(List<UnifiedKnowledgeClaim> claims, List<String> conflicts) {
        boolean hasPrimary = claims.stream().anyMatch(claim -> claim.sourceType() == SourceType.REQUIREMENT
                || claim.sourceType() == SourceType.PARAMETER_TABLE);
        boolean hasAny = !claims.isEmpty();
        if (!conflicts.isEmpty()) {
            return confirmedConflictInvolvesPrimary(claims) ? AnswerStatus.CONFLICTED : AnswerStatus.REVIEW_REQUIRED;
        }
        if (!hasAny) return AnswerStatus.NO_RESULT;
        if (hasPrimary) return AnswerStatus.CONFIRMED;
        if (claims.stream().anyMatch(claim -> claim.sourceType() == SourceType.TEST_RESULT
                && "FAILED".equalsIgnoreCase(claim.value()))) {
            return AnswerStatus.REVIEW_REQUIRED;
        }
        return AnswerStatus.SUPPORTED;
    }

    /**
     * 确定冲突涉及的分组内是否有 PRIMARY 来源（REQUIREMENT / PARAMETER_TABLE）：
     * 复用 {@link #conflictGroups} 的分组判据（内部 factKey 冲突 + factKey 对齐的跨源
     * 冲突 / 失败测试），不解析冲突消息文本——消息中 {@code factKey=} 之后是分组键 +
     * 空格 + 描述，按子串截取会把描述误当分组键，导致确定冲突永远匹配不上分组。
     * 孤立失败测试结果（组内只有 TEST_RESULT）不会因候选集其它位置有参数表 Claim 而 CONFLICTED。
     */
    private boolean confirmedConflictInvolvesPrimary(List<UnifiedKnowledgeClaim> claims) {
        Set<String> confirmedGroups = conflictGroups(claims);
        if (confirmedGroups.isEmpty()) return false;
        return claims.stream().filter(claim -> claim.sourceType() == SourceType.REQUIREMENT
                        || claim.sourceType() == SourceType.PARAMETER_TABLE)
                .flatMap(claim -> groupKeys(claim).stream())
                .anyMatch(confirmedGroups::contains);
    }

    /** 值 + claim 单位的联合归一化：值为裸数值且 claim 带单位时用 claim 单位补齐。 */
    private String canonical(UnifiedKnowledgeClaim claim) {
        String valueCanonical = canonicalValue(claim.value());
        String unit = claim.unit() == null ? "" : claim.unit().trim();
        if (!unit.isBlank() && BARE_NUMBER.matcher(valueCanonical).matches()) {
            return valueCanonical + "@" + normalizeUnit(unit);
        }
        return valueCanonical;
    }

    /**
     * 值归一化：提取"数值 + 单位"后统一比较，避免 "30秒" 与 "30.0秒"、"1,000" 与 "1000" 被误判为不一致。
     * 数值按去尾零十进制比较；单位做常见别名归并（秒/s、分钟/分/min、小时/时/h、天/d、%）；
     * 单位不同（如 秒 vs 分钟）仍视为不同值——不做跨单位换算，避免掩盖真实冲突。
     */
    private String canonicalValue(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        java.util.regex.Matcher matcher = NUMBER_WITH_UNIT.matcher(trimmed.replace(",", ""));
        if (matcher.matches()) {
            String unit = normalizeUnit(matcher.group(2));
            try {
                String number = new BigDecimal(matcher.group(1)).stripTrailingZeros().toPlainString();
                return unit.isEmpty() ? number : number + "@" + unit;
            } catch (NumberFormatException ignored) {
                // 非常规数值（如溢出）退化为文本比较
            }
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private String normalizeUnit(String unit) {
        String normalized = unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "秒", "s" -> "s";
            case "分钟", "分", "min" -> "min";
            case "小时", "时", "h", "hr" -> "h";
            case "天", "d" -> "d";
            case "%", "percent", "百分比" -> "%";
            default -> normalized;
        };
    }
}
