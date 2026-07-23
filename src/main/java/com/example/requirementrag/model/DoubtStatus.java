package com.example.requirementrag.model;

/** 存疑确认状态。 */
public enum DoubtStatus {
    /** 尚未获得产品答复。 */
    UNANSWERED,
    /** 产品答复仍不清晰。 */
    AMBIGUOUS,
    /** 存在与其他规则冲突。 */
    CONFLICT
}
