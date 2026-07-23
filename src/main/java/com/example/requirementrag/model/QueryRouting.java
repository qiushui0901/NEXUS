package com.example.requirementrag.model;

/**
 * 查询路由结果：将用户问题映射到目标项目与侧别。
 */
public record QueryRouting(
        String projectId,
        String side,
        double confidence,
        String routingMethod
) {
}
