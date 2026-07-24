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

/** Shared, deterministic parent-chunk comparison used by drafts and formal version reports. */
public final class RequirementChunkDiff {
    private RequirementChunkDiff() {}

    public enum Type { ADDED, MODIFIED, REMOVED }
    public record ParentChange(Type type, ChunkRecord before, ChunkRecord after) {}

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

    public static List<ChunkRecord> deduplicate(List<ChunkRecord> chunks) {
        return List.copyOf(parentMap(chunks).values());
    }

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
