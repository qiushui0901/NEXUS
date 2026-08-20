package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.model.ChunkRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 需求语义图与 Qdrant 回查共用的稳定证据 ID 规则。 */
final class RequirementGraphEvidence {
    private RequirementGraphEvidence() {
    }

    static String id(String projectId, String version, ChunkRecord chunk) {
        String filename = safe(chunk == null ? null : chunk.filename());
        String parentId = safe(chunk == null ? null : chunk.parentId());
        String contentHash = safe(chunk == null ? null : chunk.contentHash());
        String seed = safe(projectId) + "|" + safe(version) + "|" + filename + "|"
                + parentId + "|" + (chunk == null ? 0 : chunk.parentOrder()) + "|" + contentHash;
        return "requirement:" + sha256(seed).substring(0, 32);
    }

    static String excerpt(String text, int limit) {
        String value = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (value.length() <= limit) return value;
        return value.substring(0, Math.max(0, limit - 1)) + "…";
    }

    static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
