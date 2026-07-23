package com.example.requirementrag.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GitLab push webhook 事件载荷。
 */
public record GitLabPushEvent(String ref, String before, String after, Project project) {

    public record Project(@JsonProperty("path_with_namespace") String pathWithNamespace) {
    }
}
