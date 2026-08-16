package com.example.requirementrag.evolution.mining;

import com.example.requirementrag.evolution.experience.RetrievalExperience;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按 queryHash + failureType + indexVersion 对失败经验聚类，避免重复候选。 */
public final class FailureClusterer {

    private FailureClusterer() {
    }

    /** 返回聚类 key -> 经验列表。 */
    public static Map<String, List<RetrievalExperience>> cluster(List<RetrievalExperience> experiences,
                                                                  FailureType failureType,
                                                                  String indexVersion) {
        Map<String, List<RetrievalExperience>> groups = new LinkedHashMap<>();
        for (RetrievalExperience experience : experiences) {
            String key = experience.queryHash() + "|" + failureType.name() + "|" + indexVersion;
            groups.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(experience);
        }
        return groups;
    }
}
