package com.example.requirementrag.model;

/** 后台代码索引任务的公开状态。 */
public record CodeIndexJobStatus(
        CodeIndexJobState state,
        String projectId,
        String commitSha,
        int files,
        int chunks,
        String startedAt,
        String completedAt,
        String message
) {

    /** 构造空闲状态：无后台任务时返回，按是否已有存量索引给出不同提示。 */
    public static CodeIndexJobStatus idle(String projectId, int existingChunks) {
        String message = existingChunks > 0
                ? "当前没有后台任务，已有代码索引可直接使用"
                : "尚未建立代码索引";
        return new CodeIndexJobStatus(CodeIndexJobState.IDLE, projectId, null, 0, existingChunks,
                null, null, message);
    }

    /** 构造运行中状态：提示已有索引在完成前仍可使用。 */
    public static CodeIndexJobStatus running(String projectId, String startedAt, int existingChunks) {
        return new CodeIndexJobStatus(CodeIndexJobState.RUNNING, projectId, null, 0, existingChunks,
                startedAt, null, "正在扫描代码并生成向量，已有索引在完成前仍可使用");
    }

    /** 由索引结果构造已完成状态，携带文件数与分块数。 */
    public static CodeIndexJobStatus completed(CodeIndexResponse response, String startedAt, String completedAt) {
        return new CodeIndexJobStatus(CodeIndexJobState.COMPLETED, response.projectId(), response.commitSha(),
                response.files(), response.chunks(), startedAt, completedAt, "代码索引已完成");
    }

    /** 构造失败状态，携带失败原因消息。 */
    public static CodeIndexJobStatus failed(String projectId, String startedAt, String completedAt, String message) {
        return new CodeIndexJobStatus(CodeIndexJobState.FAILED, projectId, null, 0, 0,
                startedAt, completedAt, message);
    }
}
