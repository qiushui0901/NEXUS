package com.example.requirementrag.model;

import java.time.Instant;

/** 知识库引导进度快照。 */
public record BootstrapStatus(
        String state,
        String phase,
        int filesTotal,
        int filesProcessed,
        int chunks,
        String currentFile,
        String error,
        Instant startedAt,
        Instant completedAt
) {
    /** 返回空闲状态的默认快照。 */
    public static BootstrapStatus idle() {
        return new BootstrapStatus("IDLE", "", 0, 0, 0, "", null, null, null);
    }
}
