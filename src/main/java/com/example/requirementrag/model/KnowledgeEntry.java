package com.example.requirementrag.model;

/** 知识条目，包含来源标识与正文文本。 */
public record KnowledgeEntry(String source, String text) {
}
