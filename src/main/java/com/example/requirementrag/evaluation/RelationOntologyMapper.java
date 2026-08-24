package com.example.requirementrag.evaluation;

import com.example.requirementrag.requirement.graph.RequirementGraphModels.RelationType;

import java.util.Locale;

/**
 * 生产关系本体（{@link RelationType}）谓词归一化。
 *
 * <p>只把金标/预测谓词按字符串规范化为生产 `RelationType` 名称；<b>不做语义猜测映射</b>。
 * 例如 REWARDS→USES / CONSUMES→USES 这类“为了让 F1 不为 0”的近似映射已被移除，
 * 因为它们的方向与业务语义并不等价，会造成不符合业务语义的正匹配。
 *
 * <p>非本体谓词（REWARDS/HAS_FLOW/MUST_NOT_* 等）应通过评测报告单独计数，
 * 而不是被强制纳入本体对齐关系 F1。
 */
public final class RelationOntologyMapper {

    private RelationOntologyMapper() {
    }

    /** 返回生产关系类型名；不属于生产本体时返回 null。 */
    public static String toProductionType(String predicate) {
        if (predicate == null || predicate.isBlank()) return null;
        String normalized = predicate.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        for (RelationType type : RelationType.values()) {
            if (type.name().equals(normalized)) return type.name();
        }
        return null;
    }
}