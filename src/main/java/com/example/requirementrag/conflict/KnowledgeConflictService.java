package com.example.requirementrag.conflict;

import com.example.requirementrag.conflict.KnowledgeConflictModels.AnalyzeRequest;
import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.ConflictType;
import com.example.requirementrag.conflict.KnowledgeConflictModels.KnowledgeClaim;
import com.example.requirementrag.conflict.KnowledgeConflictModels.KnowledgeConflict;
import com.example.requirementrag.conflict.KnowledgeConflictModels.KnowledgeConflictReport;
import com.example.requirementrag.conflict.KnowledgeConflictModels.KnowledgeEvidence;
import com.example.requirementrag.conflict.KnowledgeConflictModels.ReportStatus;
import com.example.requirementrag.conflict.KnowledgeConflictModels.ResolutionStatus;
import com.example.requirementrag.conflict.KnowledgeConflictModels.Severity;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 对已结构化、版本范围内的知识声明做确定性冲突检测。 */
@Service
public class KnowledgeConflictService {
    private static final int MAX_FACT_KEY_CHARS = 240;
    private static final int MAX_VALUE_CHARS = 2_000;
    private static final int MAX_EXCERPT_CHARS = 360;

    /** 分析请求中的声明并返回冲突报告。 */
    public KnowledgeConflictReport analyze(AnalyzeRequest request) {
        Objects.requireNonNull(request, "request");
        return analyze(request.projectId(), request.targetVersion(), request.claims());
    }

    /**
     * 分析指定项目与版本下的声明：校验项目/版本归属与 Wiki 证据支撑，
     * 并对同一事实键下结论不同的声明生成冲突；结果按严重级别排序。
     *
     * @param projectId     期望的项目，非空时校验声明归属
     * @param targetVersion 期望的版本，非空时校验声明归属
     * @param claims        待分析的声明列表
     * @return 冲突报告，含总体状态、统计与冲突列表
     */
    public KnowledgeConflictReport analyze(String projectId, String targetVersion, List<KnowledgeClaim> claims) {
        String expectedProject = clean(projectId, 160);
        String expectedVersion = clean(targetVersion, 160);
        List<String> warnings = new ArrayList<>();
        List<KnowledgeClaim> normalized = normalizeClaims(claims, warnings);
        List<KnowledgeConflict> conflicts = new ArrayList<>();
        List<KnowledgeClaim> comparable = new ArrayList<>();

        for (KnowledgeClaim claim : normalized) {
            if (hasText(expectedProject) && !expectedProject.equals(claim.projectId())) {
                conflicts.add(singleClaimConflict(ConflictType.PROJECT_CONTAMINATION, Severity.BLOCKING,
                        claim, "检索证据属于其他项目，已阻止作为当前项目事实使用"));
                continue;
            }
            if (hasText(expectedVersion) && !expectedVersion.equals(claim.version())) {
                conflicts.add(singleClaimConflict(ConflictType.VERSION_CONTAMINATION, Severity.BLOCKING,
                        claim, "检索证据属于其他版本，已阻止作为目标版本事实使用"));
                continue;
            }
            comparable.add(claim);
        }

        Set<String> primaryEvidenceIds = comparable.stream()
                .filter(claim -> claim.authority() == Authority.PRIMARY)
                .map(KnowledgeClaim::evidence)
                .map(KnowledgeEvidence::evidenceId)
                .filter(this::hasText)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (KnowledgeClaim claim : comparable) {
            if (claim.sourceType() != SourceType.WIKI) {
                continue;
            }
            boolean supported = claim.supportingEvidenceIds().stream().anyMatch(primaryEvidenceIds::contains);
            if (!supported) {
                conflicts.add(singleClaimConflict(ConflictType.WIKI_MISSING_PRIMARY_EVIDENCE, Severity.BLOCKING,
                        claim, "派生 Wiki 声明没有关联当前报告中的原始证据，不能作为已验证事实发布"));
            }
        }

        Map<String, List<KnowledgeClaim>> byFact = new LinkedHashMap<>();
        for (KnowledgeClaim claim : comparable) {
            byFact.computeIfAbsent(claim.factKey(), ignored -> new ArrayList<>()).add(claim);
        }
        Set<String> emitted = new HashSet<>();
        for (Map.Entry<String, List<KnowledgeClaim>> entry : byFact.entrySet()) {
            List<KnowledgeClaim> facts = entry.getValue();
            for (int leftIndex = 0; leftIndex < facts.size(); leftIndex++) {
                for (int rightIndex = leftIndex + 1; rightIndex < facts.size(); rightIndex++) {
                    KnowledgeClaim left = facts.get(leftIndex);
                    KnowledgeClaim right = facts.get(rightIndex);
                    if (normalizedValue(left.value()).equals(normalizedValue(right.value()))) {
                        continue;
                    }
                    ConflictType type = classify(left.sourceType(), right.sourceType());
                    String pairKey = conflictKey(type, entry.getKey(), left, right);
                    if (emitted.add(pairKey)) {
                        conflicts.add(pairConflict(type, severity(type), entry.getKey(), left, right));
                    }
                }
            }
        }

        conflicts.sort(Comparator.comparing((KnowledgeConflict conflict) -> conflict.severity().ordinal()).reversed()
                .thenComparing(conflict -> conflict.type().name())
                .thenComparing(KnowledgeConflict::factKey, Comparator.nullsFirst(String::compareTo)));
        ReportStatus status = reportStatus(conflicts, warnings);
        return new KnowledgeConflictReport(expectedProject, expectedVersion, status,
                normalized.size(), conflicts.size(), conflicts, List.copyOf(warnings));
    }

    /** 规范化声明列表：剔除非法项、按内容去重合并，并生成相应警告。 */
    private List<KnowledgeClaim> normalizeClaims(List<KnowledgeClaim> claims, List<String> warnings) {
        Map<String, KnowledgeClaim> unique = new LinkedHashMap<>();
        int ignored = 0;
        int legacyTest = 0;
        for (KnowledgeClaim value : claims == null ? List.<KnowledgeClaim>of() : claims) {
            if (value != null && value.sourceType() == SourceType.TEST) {
                legacyTest++;
            }
            KnowledgeClaim claim = normalize(value);
            if (claim == null) {
                ignored++;
                continue;
            }
            String key = claim.projectId() + "|" + claim.version() + "|" + claim.sourceType() + "|"
                    + claim.evidence().evidenceId() + "|" + claim.factKey() + "|"
                    + normalizedValue(claim.value());
            unique.merge(key, claim, this::mergeSupportingEvidence);
        }
        if (ignored > 0) {
            warnings.add("已忽略 " + ignored + " 条缺少项目、版本、事实键、事实值或证据标识的声明");
        }
        if (legacyTest > 0) {
            warnings.add("已将 " + legacyTest + " 条旧 TEST 来源声明规范化为 TEST_CASE");
        }
        int duplicates = Math.max(0, (claims == null ? 0 : claims.size()) - ignored - unique.size());
        if (duplicates > 0) {
            warnings.add("已合并 " + duplicates + " 条重复声明");
        }
        return List.copyOf(unique.values());
    }

    /** 单条声明规范化：清理字段、按来源推导权威级别、补齐缺失的 claimId；字段缺失返回 null。 */
    private KnowledgeClaim normalize(KnowledgeClaim value) {
        if (value == null || value.sourceType() == null || value.evidence() == null) {
            return null;
        }
        String projectId = clean(value.projectId(), 160);
        String version = clean(value.version(), 160);
        String factKey = clean(value.factKey(), MAX_FACT_KEY_CHARS).toLowerCase(Locale.ROOT);
        String factValue = clean(value.value(), MAX_VALUE_CHARS);
        String evidenceId = clean(value.evidence().evidenceId(), 240);
        if (!hasText(projectId) || !hasText(version) || !hasText(factKey)
                || !hasText(factValue) || !hasText(evidenceId)) {
            return null;
        }
        KnowledgeEvidence evidence = new KnowledgeEvidence(evidenceId,
                clean(value.evidence().title(), 240), clean(value.evidence().source(), 360),
                clean(value.evidence().location(), 360), clean(value.evidence().excerpt(), MAX_EXCERPT_CHARS));
        // 旧 TEST 统一回填为 TEST_CASE：新代码路径不再出现遗留 TEST 来源。
        SourceType sourceType = value.sourceType().normalized();
        Authority authority = sourceType == SourceType.WIKI ? Authority.DERIVED : Authority.PRIMARY;
        List<String> supporting = value.supportingEvidenceIds().stream()
                .map(item -> clean(item, 240))
                .filter(this::hasText)
                .distinct()
                .toList();
        String claimId = clean(value.claimId(), 240);
        if (!hasText(claimId)) {
            claimId = sourceType.name().toLowerCase(Locale.ROOT) + ':' + evidenceId + ':' + factKey;
        }
        return new KnowledgeClaim(claimId, projectId, version, factKey, factValue,
                sourceType, authority, evidence, supporting);
    }

    /** 合并重复声明的支撑证据 ID（去重后仅在新增时重建声明）。 */
    private KnowledgeClaim mergeSupportingEvidence(KnowledgeClaim existing, KnowledgeClaim duplicate) {
        LinkedHashSet<String> supporting = new LinkedHashSet<>(existing.supportingEvidenceIds());
        supporting.addAll(duplicate.supportingEvidenceIds());
        if (supporting.size() == existing.supportingEvidenceIds().size()) {
            return existing;
        }
        return new KnowledgeClaim(existing.claimId(), existing.projectId(), existing.version(), existing.factKey(),
                existing.value(), existing.sourceType(), existing.authority(), existing.evidence(), List.copyOf(supporting));
    }

    /** 为单条声明生成冲突（项目/版本污染、Wiki 缺少原始证据）。 */
    private KnowledgeConflict singleClaimConflict(ConflictType type, Severity severity,
                                                   KnowledgeClaim claim, String message) {
        String key = type + "|" + claim.factKey() + "|" + claim.evidence().evidenceId();
        return new KnowledgeConflict(id(key), type, severity, ResolutionStatus.OPEN,
                claim.factKey(), message, List.of(claim));
    }

    /** 为一对结论不同的声明生成冲突，消息按冲突类型给出对应的中文提示。 */
    private KnowledgeConflict pairConflict(ConflictType type, Severity severity, String factKey,
                                           KnowledgeClaim left, KnowledgeClaim right) {
        String message = switch (type) {
            case REQUIREMENT_CODE -> "需求期望与当前代码实现存在不同结论，需要确认实现偏差";
            case REQUIREMENT_TEST -> "需求期望与测试证据存在不同结论，需要确认验证结果";
            case CODE_TEST -> "代码实现与测试证据存在不同结论，需要核对提交和执行快照";
            case WIKI_PRIMARY -> "派生 Wiki 与原始证据存在不同结论，Wiki 应标记为过期并重新审核";
            case SOURCE_INTERNAL -> "同一来源对同一事实给出了不同结论，需要先解决来源内部歧义";
            default -> "知识来源存在不同结论，需要人工审核";
        };
        String key = conflictKey(type, factKey, left, right);
        return new KnowledgeConflict(id(key), type, severity, ResolutionStatus.OPEN,
                factKey, message, List.of(left, right));
    }

    /** 生成冲突去重键：类型 + 事实键 + 归一化结论 + 证据 ID（均排序后拼接）。 */
    private String conflictKey(ConflictType type, String factKey, KnowledgeClaim left, KnowledgeClaim right) {
        List<String> values = List.of(normalizedValue(left.value()), normalizedValue(right.value())).stream()
                .sorted().toList();
        List<String> evidence = List.of(left.evidence().evidenceId(), right.evidence().evidenceId()).stream()
                .sorted().toList();
        return type + "|" + factKey + "|" + String.join("|", values) + "|" + String.join("|", evidence);
    }

    /** 根据来源类型组合判定冲突类型：同源内部、Wiki 对原始证据，其余按需求/代码/测试配对。 */
    private ConflictType classify(SourceType left, SourceType right) {
        if (left == right) return ConflictType.SOURCE_INTERNAL;
        Set<SourceType> types = Set.of(left, right);
        if (types.contains(SourceType.WIKI)) return ConflictType.WIKI_PRIMARY;
        if (types.contains(SourceType.REQUIREMENT) && hasTestSource(types)) {
            return ConflictType.REQUIREMENT_TEST;
        }
        if (types.contains(SourceType.REQUIREMENT) && types.contains(SourceType.CODE)) {
            return ConflictType.REQUIREMENT_CODE;
        }
        if (types.contains(SourceType.CODE) && hasTestSource(types)) return ConflictType.CODE_TEST;
        return ConflictType.SOURCE_INTERNAL;
    }

    /** 是否属于测试类来源：兼容旧 TEST，并识别新 TEST_CASE / TEST_RESULT。 */
    private boolean hasTestSource(Set<SourceType> types) {
        return types.contains(SourceType.TEST)
                || types.contains(SourceType.TEST_CASE)
                || types.contains(SourceType.TEST_RESULT);
    }

    private Severity severity(ConflictType type) {
        return type == ConflictType.WIKI_PRIMARY ? Severity.BLOCKING : Severity.ERROR;
    }

    /** 汇总冲突与警告得到报告总体状态：有 BLOCKING 冲突则 BLOCKED，否则有冲突或警告则需复核。 */
    private ReportStatus reportStatus(List<KnowledgeConflict> conflicts, List<String> warnings) {
        if (conflicts.stream().anyMatch(conflict -> conflict.severity() == Severity.BLOCKING)) {
            return ReportStatus.BLOCKED;
        }
        return conflicts.isEmpty() && warnings.isEmpty() ? ReportStatus.CLEAR : ReportStatus.REVIEW_REQUIRED;
    }

    /** 依据输入内容生成确定性的冲突 ID（conflict- + UUID）。 */
    private String id(String value) {
        return "conflict-" + UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private String normalizedValue(String value) {
        return clean(value, MAX_VALUE_CHARS).toLowerCase(Locale.ROOT);
    }

    private String clean(String value, int limit) {
        String normalized = Objects.toString(value, "").replaceAll("\\s+", " ").trim();
        if (normalized.length() <= limit) return normalized;
        return normalized.substring(0, Math.max(0, limit - 1)) + "…";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
