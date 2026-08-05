package com.example.requirementrag.knowledge.build;

import com.example.requirementrag.wiki.WikiModels.GenerationResult;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 知识草稿的持久化契约模型：审核、发布与回滚流程所需的数据结构。 */
public final class KnowledgeDraftModels {
    private KnowledgeDraftModels() {}

    /** 草稿生命周期状态：DRAFT 待提交 → IN_REVIEW 审核中 → APPROVED/REJECTED/SPLIT/MERGED → PUBLISHED 已发布。 */
    public enum DraftStatus {
        DRAFT,
        IN_REVIEW,
        APPROVED,
        REJECTED,
        PUBLISHED,
        SPLIT,
        MERGED
    }

    /** 状态流转请求：目标状态与可选备注。 */
    public record TransitionRequest(
            @NotNull DraftStatus targetStatus,
            @Size(max = 1000) String comment
    ) {}

    /** 审计记录：一次状态流转的来源状态、去向状态、操作人、时间与备注。 */
    public record AuditEntry(
            DraftStatus fromStatus,
            DraftStatus toStatus,
            String actor,
            String occurredAt,
            String comment
    ) {}

    /** 发布信息：发布 ID、发布时间、操作人、上一份正式快照 ID 及回滚记录。 */
    public record Publication(
            String publicationId,
            String publishedAt,
            String publishedBy,
            String previousSnapshotId,
            String rolledBackAt,
            String rolledBackBy,
            String rollbackComment
    ) {}

    /** 草稿元数据：构建/项目/版本标识、状态、修订号、时间线、操作人、审计历史与发布信息。 */
    public record DraftMetadata(
            String buildId,
            String projectId,
            String version,
            DraftStatus status,
            long revision,
            String createdAt,
            String updatedAt,
            String createdBy,
            List<AuditEntry> history,
            Publication publication
    ) {
        public DraftMetadata {
            history = history == null ? List.of() : List.copyOf(history);
        }
    }

    /** 发布结果：更新后的草稿元数据与 Wiki 生成结果。 */
    public record PublishResult(DraftMetadata draft, GenerationResult wiki) {}

    /** 回滚结果：更新后的草稿元数据与 Wiki 生成结果。 */
    public record RollbackResult(DraftMetadata draft, GenerationResult wiki) {}
}
