package com.example.requirementrag.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GitLab push webhook 事件载荷。
 */
public record GitLabPushEvent(String ref, String before, String after, Project project) {

    /** GitLab 项目信息，pathWithNamespace 为带命名空间的完整项目路径（如 group/repo）。 */
    public record Project(@JsonProperty("path_with_namespace") String pathWithNamespace) {
    }
}
