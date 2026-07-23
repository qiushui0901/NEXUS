package com.example.requirementrag.model;

import tools.jackson.databind.JsonNode;

/** 开发方案流中的一个可独立渲染事件。 */
public record DevelopmentPlanStreamEvent(
        String type,
        long sequence,
        JsonNode payload,
        String message
) {
}
