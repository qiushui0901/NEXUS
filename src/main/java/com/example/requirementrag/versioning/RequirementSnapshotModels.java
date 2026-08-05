package com.example.requirementrag.versioning;

import java.util.List;

/** 可人工审阅的需求版本事实，不含向量及向量库负载。 */
public final class RequirementSnapshotModels {
    private RequirementSnapshotModels() {}

    /** 快照条目的操作类型：写入或删除。 */
    public enum Operation {
        UPSERT,
        REMOVE
    }

    /** 一次需求来源文件的信息，仅记录路径、位置、哈希与大小，不包含内容。 */
    public record Source(
            String path,
            String location,
            String contentHash,
            long bytes
    ) {}

    /** 快照中的单条需求条目，通过操作类型增量应用到基线之上。 */
    public record Entry(
            String entryId,
            String filename,
            int parentOrder,
            String text,
            String contentHash,
            Operation operation
    ) {
        /** 让存量来源与测试代码兼容早于显式操作字段的 schema-v1 条目。 */
        public Entry(String entryId, String filename, int parentOrder, String text, String contentHash) {
            this(entryId, filename, parentOrder, text, contentHash, null);
        }

        /** 返回生效的操作类型，缺省按 UPSERT 处理。 */
        public Operation effectiveOperation() {
            return operation == null ? Operation.UPSERT : operation;
        }
    }

    /** 一个需求版本的整体快照：含来源清单与增量条目，条目经 baseRequirementVersion 继承基线。 */
    public record Snapshot(
            int schemaVersion,
            String projectId,
            String documentId,
            String requirementVersion,
            String baseRequirementVersion,
            List<String> aliases,
            String generatedAt,
            List<Source> sources,
            List<Entry> entries
    ) {
        public Snapshot {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            sources = sources == null ? List.of() : List.copyOf(sources);
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }
}
