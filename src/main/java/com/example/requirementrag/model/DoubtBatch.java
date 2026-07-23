package com.example.requirementrag.model;

import java.util.List;

/** 存疑批次，封装不可变存疑列表。 */
public record DoubtBatch(List<RequirementDoubt> doubts) {

    /** 规范化构造：null 转为空列表并防御性拷贝。 */
    public DoubtBatch {
        doubts = doubts == null ? List.of() : List.copyOf(doubts);
    }
}
