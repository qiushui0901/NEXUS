package com.example.requirementrag.requirement.graph;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * MIX / HYBRID 多通道检索的融合权重，可按配置调整。
 *
 * <p>默认权重与评审建议一致：
 * <pre>
 *   finalScore = 0.30×text + 0.20×entity + 0.15×relation
 *              + 0.15×path + 0.15×evidence + 0.05×freshness
 * </pre>
 */
@ConfigurationProperties("app.rag.requirement-graph.fusion")
public record RequirementGraphFusionProperties(
        double textWeight,
        double entityWeight,
        double relationWeight,
        double pathWeight,
        double evidenceWeight,
        double freshnessWeight
) {
    @ConstructorBinding
    public RequirementGraphFusionProperties {
        double total = textWeight + entityWeight + relationWeight + pathWeight + evidenceWeight + freshnessWeight;
        if (total <= 0) {
            textWeight = 0.30;
            entityWeight = 0.20;
            relationWeight = 0.15;
            pathWeight = 0.15;
            evidenceWeight = 0.15;
            freshnessWeight = 0.05;
        }
    }

    public double total() {
        return textWeight + entityWeight + relationWeight + pathWeight + evidenceWeight + freshnessWeight;
    }

    /** 归一化到总和为 1.0 的权重，用于可解释的加权融合。 */
    public double normalized(double weight) {
        double sum = total();
        return sum == 0 ? 0 : weight / sum;
    }

    public static RequirementGraphFusionProperties defaults() {
        return new RequirementGraphFusionProperties(0.30, 0.20, 0.15, 0.15, 0.15, 0.05);
    }
}
