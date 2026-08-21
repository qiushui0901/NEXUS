package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.model.ChunkRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

/** Stable requirement evidence IDs plus exact quote resolution helpers. */
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

    static Span resolve(String source, String quote, int baseOffset) {
        String text = source == null ? "" : source;
        String value = quote == null ? "" : quote.trim();
        if (value.isBlank()) return new Span("", -1, -1, RequirementGraphModels.EvidenceResolutionStatus.UNAVAILABLE);
        int exact = text.indexOf(value);
        if (exact >= 0) {
            return new Span(value, baseOffset + exact, baseOffset + exact + value.length(),
                    RequirementGraphModels.EvidenceResolutionStatus.RESOLVED);
        }
        String normalizedText = normalize(text);
        String normalizedQuote = normalize(value);
        int normalized = normalizedText.indexOf(normalizedQuote);
        if (normalized >= 0) {
            int start = mapNormalizedOffset(text, normalized);
            int end = mapNormalizedOffset(text, normalized + normalizedQuote.length());
            return new Span(value, baseOffset + start, baseOffset + end,
                    RequirementGraphModels.EvidenceResolutionStatus.RESOLVED);
        }
        return new Span(value, -1, -1, RequirementGraphModels.EvidenceResolutionStatus.UNAVAILABLE);
    }

    static String spanId(String projectId, String version, String filename, String parentId,
                         int parentOrder, String contentHash, int startOffset, int endOffset, String quote) {
        String seed = safe(projectId) + "|" + safe(version) + "|" + safe(filename) + "|" + safe(parentId)
                + "|" + parentOrder + "|" + safe(contentHash) + "|" + startOffset + "|" + endOffset + "|" + safe(quote);
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

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("\\s+", " ").trim();
    }

    private static int mapNormalizedOffset(String original, int normalizedOffset) {
        if (normalizedOffset <= 0) return 0;
        int seen = 0;
        for (int index = 0; index < original.length(); index++) {
            String part = normalize(String.valueOf(original.charAt(index)));
            if (part.isEmpty()) continue;
            seen += part.length();
            if (seen >= normalizedOffset) return index + 1;
        }
        return original.length();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record Span(String quote, int startOffset, int endOffset,
                RequirementGraphModels.EvidenceResolutionStatus status) {
    }
}
