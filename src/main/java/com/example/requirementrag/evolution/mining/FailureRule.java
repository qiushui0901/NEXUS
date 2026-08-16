package com.example.requirementrag.evolution.mining;

import com.example.requirementrag.evolution.experience.RetrievalExperience;

import java.util.function.Predicate;

/** 失败挖掘规则：判定一条经验是否属于某类失败。 */
public record FailureRule(
        FailureType failureType,
        String reason,
        Predicate<RetrievalExperience> predicate
) {
}
