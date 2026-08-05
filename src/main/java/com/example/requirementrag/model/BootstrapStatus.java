package com.example.requirementrag.model;

import java.time.Instant;

/**
 * 知识库引导（bootstrap）进度快照：暴露初始化阶段、已处理文件数与分块数、当前正在处理的文件
 * 及错误信息，供前端轮询展示引导过程。
 */
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
