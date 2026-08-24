package com.example.requirementrag.evaluation;

import com.example.requirementrag.requirement.graph.RequirementGraphModels.RelationType;

import java.util.Locale;
import java.util.Map;

/**
 * 领域金标谓词 → 生产关系本体（{@link RelationType}）映射。
 *
 * <p>用途：评测「本体对齐关系 F1」时，把语义等价的领域谓词归一为生产关系类型，
 * 避免金标用 REWARDS/HAS_FLOW 等非生产谓词导致生产链路关系 F1 恒为 0，从而无法衡量真实能力。
 *
 * <p>当前只映射语义明确的一对一关系；其余谓词视为非本体（业务属性/边界约束/实现状态），
 * 不计入本体对齐 F1，但单独计数暴露在报告中。
 */
public final class RelationOntologyMapper {

    private static final Map<String, String> MAPPING = Map.of(
            "REWARDS", "USES",
            "REWARDS_ONLY", "USES",
            "USES_CURRENCY", "USES",
            "CONSUMES", "USES",
            "SETS_STATE", "CHANGES_STATE"
    );

    private RelationOntologyMapper() {
    }

    /** 返回生产关系类型名；无法映射或不属于生产本体时返回 null。 */
    public static String toProductionType(String predicate) {
        if (predicate == null || predicate.isBlank()) return null;
        String normalized = predicate.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        for (RelationType type : RelationType.values()) {
            if (type.name().equals(normalized)) return type.name();
        }
        return MAPPING.get(normalized);
    }
}