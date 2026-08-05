package com.example.requirementrag.versioning;

import com.example.requirementrag.model.ChunkRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 由草稿与正式版本报告共用的、确定性的父需求块比对逻辑。 */
public final class RequirementChunkDiff {
    private RequirementChunkDiff() {}

    /** 需求变化类型：新增、修改、删除。 */
    public enum Type { ADDED, MODIFIED, REMOVED }
    /** 单个父需求块的前后变化记录，before/after 按类型可能为 null。 */
    public record ParentChange(Type type, ChunkRecord before, ChunkRecord after) {}

    /**
     * 比对两个版本的需求块，产出新增、修改、删除三类变化。
     * 先按稳定的 parentId 配对；ID 在重新导入时变更后，回退到“文件名 + parentOrder”的位置配对；
     * 配对成功的块再比较内容哈希判定是否修改。
     *
     * @param beforeChunks 旧版本的需求块
     * @param afterChunks  新版本的需求块
     * @return 变化列表，顺序确定（先按配对表，后按旧列表剩余项）
     */
    public static List<ParentChange> compare(List<ChunkRecord> beforeChunks, List<ChunkRecord> afterChunks) {
        List<ChunkRecord> before = deduplicate(beforeChunks);
        List<ChunkRecord> after = deduplicate(afterChunks);
        Map<Integer, Integer> matches = new LinkedHashMap<>();
        boolean[] usedBefore = new boolean[before.size()];

        // Stable parent IDs are the primary key when they survive between versions.
        for (int afterIndex = 0; afterIndex < after.size(); afterIndex++) {
            String parentId = after.get(afterIndex).parentId();
            if (!hasText(parentId)) continue;
            for (int beforeIndex = 0; beforeIndex < before.size(); beforeIndex++) {
                if (!usedBefore[beforeIndex] && parentId.trim().equals(safe(before.get(beforeIndex).parentId()).trim())) {
                    matches.put(afterIndex, beforeIndex);
                    usedBefore[beforeIndex] = true;
                    break;
                }
            }
        }
        // If IDs changed during re-import, filename + parent order provides a deterministic fallback.
        for (int afterIndex = 0; afterIndex < after.size(); afterIndex++) {
            if (matches.containsKey(afterIndex)) continue;
            String position = position(after.get(afterIndex));
            for (int beforeIndex = 0; beforeIndex < before.size(); beforeIndex++) {
                if (!usedBefore[beforeIndex] && position.equals(position(before.get(beforeIndex)))) {
                    matches.put(afterIndex, beforeIndex);
                    usedBefore[beforeIndex] = true;
                    break;
                }
            }
        }

        List<ParentChange> changes = new ArrayList<>();
        for (int afterIndex = 0; afterIndex < after.size(); afterIndex++) {
            Integer beforeIndex = matches.get(afterIndex);
            ChunkRecord current = after.get(afterIndex);
            if (beforeIndex == null) changes.add(new ParentChange(Type.ADDED, null, current));
            else if (!hash(before.get(beforeIndex)).equals(hash(current))) {
                changes.add(new ParentChange(Type.MODIFIED, before.get(beforeIndex), current));
            }
        }
        for (int beforeIndex = 0; beforeIndex < before.size(); beforeIndex++) {
            if (!usedBefore[beforeIndex]) changes.add(new ParentChange(Type.REMOVED, before.get(beforeIndex), null));
        }
        return List.copyOf(changes);
    }

    /**
     * 按 parentId（或位置键）去重，同一键只保留第一个块。
     *
     * @param chunks 需求块列表
     * @return 去重后的不可变列表
     */
    public static List<ChunkRecord> deduplicate(List<ChunkRecord> chunks) {
        return List.copyOf(parentMap(chunks).values());
    }

    /**
     * 计算需求块内容哈希：优先使用块自带的 contentHash，否则对 parentText 计算 SHA-256。
     *
     * @param chunk 需求块
     * @return 十六进制内容哈希
     */
    public static String hash(ChunkRecord chunk) {
        if (chunk != null && hasText(chunk.contentHash())) return chunk.contentHash().trim();
        try {
            String text = chunk == null || chunk.parentText() == null ? "" : chunk.parentText().strip();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static Map<String, ChunkRecord> parentMap(List<ChunkRecord> chunks) {
        Map<String, ChunkRecord> parents = new LinkedHashMap<>();
        for (ChunkRecord chunk : chunks == null ? List.<ChunkRecord>of() : chunks) {
            if (chunk == null) continue;
            String filename = chunk.filename() == null ? "" : chunk.filename().replace('\\', '/');
            String key = hasText(chunk.parentId()) ? "id:" + chunk.parentId().trim()
                    : "position:" + filename + ':' + chunk.parentOrder();
            parents.putIfAbsent(key, chunk);
        }
        return parents;
    }

    private static String position(ChunkRecord chunk) {
        return safe(chunk.filename()).replace('\\', '/') + ':' + chunk.parentOrder();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
