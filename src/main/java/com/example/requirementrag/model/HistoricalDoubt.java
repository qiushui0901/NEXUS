package com.example.requirementrag.model;

/** 历史版本存疑记录，含版本、模块、问题与产品解答。 */
public record HistoricalDoubt(String version, String module, String question, String answer) {
}
