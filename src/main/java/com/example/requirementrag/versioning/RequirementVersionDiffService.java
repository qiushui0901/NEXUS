package com.example.requirementrag.versioning;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.versioning.RequirementSnapshotModels.Entry;
import com.example.requirementrag.versioning.RequirementSnapshotModels.Snapshot;
import com.example.requirementrag.versioning.VersionModels.ChangeType;
import com.example.requirementrag.versioning.VersionModels.RequirementChange;
import com.example.requirementrag.versioning.VersionModels.RequirementDiff;
import com.example.requirementrag.versioning.VersionModels.VersionManifest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 基于可审阅快照比对需求父块差异，快照缺失时回退到 Qdrant 载荷。 */
@Service
public class RequirementVersionDiffService {
    private static final int EXCERPT_LIMIT = 360;

    private final QdrantHybridStore store;
    private final ProjectRegistry projectRegistry;
    private final RequirementSnapshotRepository snapshots;

    public RequirementVersionDiffService(QdrantHybridStore store, ProjectRegistry projectRegistry,
                                         RequirementSnapshotRepository snapshots) {
        this.store = store;
        this.projectRegistry = projectRegistry;
        this.snapshots = snapshots;
    }

    /**
     * 对比两个版本清单之间的需求变化；任一方缺需求引用时返回不可用结果。
     * 优先物化快照比对；快照缺失时回退从 Qdrant 滚动读取，且过滤删除项。
     *
     * @param projectId 项目标识
     * @param from      起始版本清单
     * @param to        目标版本清单
     * @return 含三类计数与明细的需求差异
     */
    public RequirementDiff compare(String projectId, VersionManifest from, VersionManifest to) {
        if (!hasReference(from) || !hasReference(to)) return RequirementDiff.unavailable();
        List<ChunkRecord> before;
        List<ChunkRecord> after;
        Optional<Snapshot> beforeSnapshot = snapshots.materialize(projectId,
                from.requirementDocumentId(), from.requirementVersion());
        Optional<Snapshot> afterSnapshot = snapshots.materialize(projectId,
                to.requirementDocumentId(), to.requirementVersion());
        List<RequirementChunkDiff.ParentChange> parentChanges;
        if (beforeSnapshot.isPresent() && afterSnapshot.isPresent()) {
            before = chunks(beforeSnapshot.get());
            after = chunks(afterSnapshot.get());
            parentChanges = RequirementChunkDiff.compare(before, after);
        } else {
            String collection = projectRegistry.resolveRequirementCollection(projectId);
            before = store.scrollVersion(collection, from.requirementDocumentId(), from.requirementVersion());
            after = store.scrollVersion(collection, to.requirementDocumentId(), to.requirementVersion());
            parentChanges = RequirementChunkDiff.compare(before, after).stream()
                    .filter(change -> change.type() != RequirementChunkDiff.Type.REMOVED)
                    .toList();
        }
        List<RequirementChange> changes = parentChanges.stream()
                .map(item -> change(ChangeType.valueOf(item.type().name()), item.before(), item.after()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        changes.sort(Comparator.comparing(RequirementChange::filename,
                        Comparator.nullsFirst(String::compareTo))
                .thenComparingInt(RequirementChange::parentOrder)
                .thenComparing(change -> change.type().name()));
        return new RequirementDiff(VersionModels.Availability.AVAILABLE,
                count(changes, ChangeType.ADDED), count(changes, ChangeType.MODIFIED),
                count(changes, ChangeType.REMOVED), changes);
    }

    /** 将快照条目转为 ChunkRecord 列表。 */
    private List<ChunkRecord> chunks(Snapshot snapshot) {
        return snapshot.entries().stream().map(entry -> chunk(snapshot, entry)).toList();
    }

    /** 将快照条目包装为虚拟父块记录，条目自身充当 parent。 */
    private ChunkRecord chunk(Snapshot snapshot, Entry entry) {
        return new ChunkRecord(entry.entryId() + "-child", snapshot.documentId(), snapshot.requirementVersion(),
                entry.filename(), entry.entryId(), entry.text(), entry.text(), entry.contentHash(),
                entry.parentOrder(), 0);
    }

    /** 由父块变化生成需求变化明细，附前后哈希与截断摘要。 */
    private RequirementChange change(ChangeType type, ChunkRecord before, ChunkRecord after) {
        ChunkRecord source = after != null ? after : before;
        return new RequirementChange(type, source.filename(), source.parentId(), source.parentOrder(),
                before == null ? null : RequirementChunkDiff.hash(before), after == null ? null : RequirementChunkDiff.hash(after),
                before == null ? null : excerpt(before.parentText()), after == null ? null : excerpt(after.parentText()));
    }

    private int count(List<RequirementChange> changes, ChangeType type) {
        return (int) changes.stream().filter(change -> change.type() == type).count();
    }

    private boolean hasReference(VersionManifest manifest) {
        return hasText(manifest.requirementDocumentId()) && hasText(manifest.requirementVersion());
    }

    /** 将文本压缩为单行，并截断到 360 字符作为摘要。 */
    private String excerpt(String text) {
        String normalized = safe(text).replaceAll("\\s+", " ").trim();
        return normalized.length() <= EXCERPT_LIMIT ? normalized : normalized.substring(0, EXCERPT_LIMIT) + "…";
    }

    private String safe(String value) { return value == null ? "" : value; }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}
