package com.example.requirementrag.evidence;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Request-scoped whitelist of safe requirement and code evidence. */
public final class EvidenceRegistry {

    private static final int MAX_EXCERPT_CHARS = 360;
    private static final int MAX_ID_PART_CHARS = 80;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern DRIVE_PATH = Pattern.compile("^[A-Za-z]:/.*");

    private final List<EvidenceRef> references;
    private final Map<String, EvidenceRef> byId;
    private final Map<ChunkRecord, String> requirementIds;
    private final Map<CodeChunk, String> codeIds;

    private EvidenceRegistry(List<EvidenceRef> references, Map<String, EvidenceRef> byId,
                             Map<ChunkRecord, String> requirementIds, Map<CodeChunk, String> codeIds) {
        this.references = List.copyOf(references);
        this.byId = Collections.unmodifiableMap(new LinkedHashMap<>(byId));
        this.requirementIds = Collections.unmodifiableMap(new IdentityHashMap<>(requirementIds));
        this.codeIds = Collections.unmodifiableMap(new IdentityHashMap<>(codeIds));
    }

    public static EvidenceRegistry from(RetrievalBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        List<EvidenceRef> references = new ArrayList<>();
        Map<String, EvidenceRef> byId = new LinkedHashMap<>();
        Map<ChunkRecord, String> requirementIds = new IdentityHashMap<>();
        Map<CodeChunk, String> codeIds = new IdentityHashMap<>();

        for (ChunkRecord chunk : bundle.requirementEvidence()) {
            if (!sameScope(bundle.documentId(), chunk.documentId())
                    || !sameScope(bundle.version(), chunk.version())) {
                continue;
            }
            String fingerprint = String.join("|", safe(chunk.documentId()), safe(chunk.version()),
                    safe(chunk.filename()), safe(chunk.parentId()), Integer.toString(chunk.parentOrder()),
                    Integer.toString(chunk.childOrder()), safe(chunk.contentHash()));
            String evidenceId = uniqueId("requirement", chunk.id(), fingerprint, byId);
            String source = safeSource(chunk.filename());
            EvidenceRef reference = new EvidenceRef(
                    evidenceId,
                    EvidenceType.REQUIREMENT,
                    safeOrNull(bundle.resolvedProjectId()),
                    safeOrNull(firstText(chunk.version(), bundle.version())),
                    source.isBlank() ? "需求片段" : source,
                    source,
                    safeOrNull(chunk.parentId()),
                    boundedExcerpt(firstText(chunk.parentText(), chunk.childText())),
                    null,
                    null,
                    null,
                    safeOrNull(chunk.id()));
            references.add(reference);
            byId.put(evidenceId, reference);
            requirementIds.put(chunk, evidenceId);
        }

        for (CodeChunk chunk : bundle.codeEvidence()) {
            if (!sameScope(bundle.resolvedProjectId(), chunk.projectId())) {
                continue;
            }
            String fingerprint = String.join("|", safe(chunk.projectId()), safe(chunk.commitSha()),
                    safe(chunk.filePath()), safe(chunk.symbolName()), Integer.toString(chunk.startLine()),
                    Integer.toString(chunk.endLine()), safe(chunk.contentHash()));
            String evidenceId = uniqueId("code", chunk.id(), fingerprint, byId);
            String source = safeSource(chunk.filePath());
            String symbol = firstText(chunk.symbolName(), chunk.symbolType());
            String location = symbol.isBlank()
                    ? lineLocation(chunk.startLine(), chunk.endLine())
                    : symbol + " · " + lineLocation(chunk.startLine(), chunk.endLine());
            EvidenceRef reference = new EvidenceRef(
                    evidenceId,
                    EvidenceType.CODE,
                    safeOrNull(firstText(chunk.projectId(), bundle.resolvedProjectId())),
                    safeOrNull(bundle.version()),
                    symbol.isBlank() ? (source.isBlank() ? "代码片段" : source) : symbol,
                    source,
                    location,
                    boundedExcerpt(chunk.text()),
                    safeOrNull(chunk.commitSha()),
                    positiveOrNull(chunk.startLine()),
                    positiveOrNull(chunk.endLine()),
                    safeOrNull(chunk.id()));
            references.add(reference);
            byId.put(evidenceId, reference);
            codeIds.put(chunk, evidenceId);
        }

        return new EvidenceRegistry(references, byId, requirementIds, codeIds);
    }

    public List<EvidenceRef> references() {
        return references;
    }

    public Optional<EvidenceRef> find(String evidenceId) {
        if (evidenceId == null) return Optional.empty();
        return Optional.ofNullable(byId.get(evidenceId.trim()));
    }

    public boolean contains(String evidenceId) {
        return find(evidenceId).isPresent();
    }

    public Optional<String> evidenceId(ChunkRecord chunk) {
        return Optional.ofNullable(requirementIds.get(chunk));
    }

    public Optional<String> evidenceId(CodeChunk chunk) {
        return Optional.ofNullable(codeIds.get(chunk));
    }

    public List<String> evidenceIdsForRequirements(List<ChunkRecord> chunks) {
        if (chunks == null) return List.of();
        return chunks.stream().map(requirementIds::get).filter(Objects::nonNull).distinct().toList();
    }

    public List<String> evidenceIdsForCode(List<CodeChunk> chunks) {
        if (chunks == null) return List.of();
        return chunks.stream().map(codeIds::get).filter(Objects::nonNull).distinct().toList();
    }

    public String promptRequirementContext(List<ChunkRecord> chunks, int maxChars) {
        StringBuilder builder = new StringBuilder();
        if (chunks == null) return "";
        for (ChunkRecord chunk : chunks) {
            String id = requirementIds.get(chunk);
            if (id == null) continue;
            appendBounded(builder, "[evidenceId=" + id + "] 文件: " + safeSource(chunk.filename())
                    + "\n" + boundedExcerpt(firstText(chunk.parentText(), chunk.childText())) + "\n\n", maxChars);
            if (builder.length() >= maxChars) break;
        }
        return builder.toString();
    }

    public String promptCodeContext(List<CodeChunk> chunks, int maxChars) {
        StringBuilder builder = new StringBuilder();
        if (chunks == null) return "";
        for (CodeChunk chunk : chunks) {
            String id = codeIds.get(chunk);
            if (id == null) continue;
            String block = "[evidenceId=" + id + "] " + firstText(chunk.symbolName(), "代码片段")
                    + " · " + safeSource(chunk.filePath()) + " · "
                    + lineLocation(chunk.startLine(), chunk.endLine()) + "\n"
                    + boundedExcerpt(chunk.text()) + "\n\n";
            appendBounded(builder, block, maxChars);
            if (builder.length() >= maxChars) break;
        }
        return builder.toString();
    }

    private static void appendBounded(StringBuilder builder, String value, int maxChars) {
        if (maxChars <= 0 || builder.length() >= maxChars) return;
        int remaining = maxChars - builder.length();
        builder.append(value, 0, Math.min(value.length(), remaining));
    }

    private static String uniqueId(String namespace, String rawId, String fingerprint,
                                   Map<String, EvidenceRef> existing) {
        String idPart = normalizedIdPart(rawId);
        if (idPart.isBlank()) idPart = digest(fingerprint);
        String base = namespace + ":" + idPart;
        if (!existing.containsKey(base)) return base;
        String candidate = base + "-" + digest(fingerprint).substring(0, 8);
        int collisionIndex = 2;
        while (existing.containsKey(candidate)) {
            candidate = base + "-" + digest(fingerprint).substring(0, 8) + "-" + collisionIndex++;
        }
        return candidate;
    }

    private static boolean sameScope(String expected, String actual) {
        String expectedValue = safe(expected).trim();
        String actualValue = safe(actual).trim();
        return expectedValue.isBlank() || actualValue.isBlank() || expectedValue.equals(actualValue);
    }

    private static String normalizedIdPart(String rawId) {
        String value = safe(rawId).trim();
        if (value.isBlank() || !SAFE_ID.matcher(value).matches()) return "";
        return value.substring(0, Math.min(value.length(), MAX_ID_PART_CHARS));
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(safe(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(32);
            for (int index = 0; index < 16; index++) {
                builder.append(String.format(Locale.ROOT, "%02x", bytes[index]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String safeSource(String rawPath) {
        String value = safe(rawPath).trim().replace('\\', '/');
        if (value.isBlank()) return "";
        boolean absolute = value.startsWith("/") || DRIVE_PATH.matcher(value).matches();
        String[] parts = value.split("/");
        List<String> safeParts = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank() || ".".equals(part) || "..".equals(part)) continue;
            safeParts.add(part.replaceAll("[\\p{Cntrl}]", ""));
        }
        if (safeParts.isEmpty()) return "";
        return absolute ? safeParts.getLast() : String.join("/", safeParts);
    }

    private static String boundedExcerpt(String rawText) {
        String value = safe(rawText).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ")
                .replaceAll("\\s+", " ").trim();
        if (value.length() <= MAX_EXCERPT_CHARS) return value;
        return value.substring(0, MAX_EXCERPT_CHARS) + "…";
    }

    private static String lineLocation(int startLine, int endLine) {
        int safeStart = Math.max(1, startLine);
        int safeEnd = Math.max(safeStart, endLine);
        return safeStart == safeEnd ? "L" + safeStart : "L" + safeStart + "-L" + safeEnd;
    }

    private static Integer positiveOrNull(int value) {
        return value > 0 ? value : null;
    }

    private static String firstText(String first, String second) {
        return !safe(first).isBlank() ? safe(first) : safe(second);
    }

    private static String safeOrNull(String value) {
        String safe = safe(value).trim();
        return safe.isBlank() ? null : safe;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
