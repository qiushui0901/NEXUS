package com.example.requirementrag.evidence;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** 请求作用域内的安全证据白名单（需求与代码），生成确定性 evidenceId。 */
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

    /**
     * 从检索结果包构建证据注册表：过滤范围外分块，生成确定性 evidenceId 并去重。
     *
     * @param bundle 本次检索的完整结果包
     * @return 不可变的证据注册表
     */
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

    /** 按 ID 查找证据引用，不存在时返回空 Optional。 */
    public Optional<EvidenceRef> find(String evidenceId) {
        if (evidenceId == null) return Optional.empty();
        return Optional.ofNullable(byId.get(evidenceId.trim()));
    }

    public boolean contains(String evidenceId) {
        return find(evidenceId).isPresent();
    }

    /** 返回需求分块在本次检索中生成的 evidenceId（按对象身份精确匹配）。 */
    public Optional<String> evidenceId(ChunkRecord chunk) {
        return Optional.ofNullable(requirementIds.get(chunk));
    }

    /** 返回代码分块在本次检索中生成的 evidenceId（按对象身份精确匹配）。 */
    public Optional<String> evidenceId(CodeChunk chunk) {
        return Optional.ofNullable(codeIds.get(chunk));
    }

    /** 批量返回需求分块的 evidenceId 列表（保持顺序、去重，未命中的跳过）。 */
    public List<String> evidenceIdsForRequirements(List<ChunkRecord> chunks) {
        if (chunks == null) return List.of();
        return chunks.stream().map(requirementIds::get).filter(Objects::nonNull).distinct().toList();
    }

    /** 批量返回代码分块的 evidenceId 列表（保持顺序、去重，未命中的跳过）。 */
    public List<String> evidenceIdsForCode(List<CodeChunk> chunks) {
        if (chunks == null) return List.of();
        return chunks.stream().map(codeIds::get).filter(Objects::nonNull).distinct().toList();
    }

    /** 需求正文上下文切片：文本 + 覆盖报告（纳入/省略块与覆盖模块），预算内按模块轮转保留代表块。 */
    public record ContextSlice(String text, int includedChunks, int omittedChunks, int coveredModules) {}

    /** 将需求分块组装为带 evidenceId 标注的提示上下文，总长度受 maxChars 限制。 */
    public String promptRequirementContext(List<ChunkRecord> chunks, int maxChars) {
        return requirementContextSlice(chunks, maxChars).text();
    }

    /**
     * 预算内按文件模块轮转选取需求块（每模块至少保留一条代表块），
     * 预算用尽后的省略块计入覆盖报告，杜绝后部模块静默丢失。
     */
    public ContextSlice requirementContextSlice(List<ChunkRecord> chunks, int maxChars) {
        if (chunks == null || chunks.isEmpty()) {
            return new ContextSlice("", 0, 0, 0);
        }
        List<ChunkRecord> ordered = new java.util.ArrayList<>(chunks);
        ordered.sort(Comparator.comparing(chunk -> firstText(chunk.filename(), "")));
        java.util.Map<String, List<ChunkRecord>> byModule = new java.util.LinkedHashMap<>();
        for (ChunkRecord chunk : ordered) {
            byModule.computeIfAbsent(moduleOf(chunk.filename()), key -> new java.util.ArrayList<>()).add(chunk);
        }
        StringBuilder builder = new StringBuilder();
        int included = 0;
        int omitted = 0;
        List<String> modules = new java.util.ArrayList<>(byModule.keySet());
        for (int round = 0; included < chunks.size() && !modules.isEmpty(); round++) {
            boolean progressed = false;
            for (String module : modules) {
                List<ChunkRecord> pending = byModule.get(module);
                if (pending.isEmpty()) continue;
                ChunkRecord chunk = pending.remove(0);
                String id = requirementIds.get(chunk);
                String block = "[evidenceId=" + (id == null ? "?" : id) + "] 文件: "
                        + safeSource(chunk.filename()) + "\n"
                        + boundedExcerpt(firstText(chunk.parentText(), chunk.childText())) + "\n\n";
                if (maxChars > 0 && builder.length() >= maxChars) {
                    omitted += 1 + pending.size();
                    pending.clear();
                    continue;
                }
                int remaining = maxChars <= 0 ? block.length() : maxChars - builder.length();
                if (remaining <= 0) {
                    omitted += 1 + pending.size();
                    pending.clear();
                    continue;
                }
                builder.append(block, 0, Math.min(block.length(), remaining));
                included++;
                progressed = true;
                if (maxChars > 0 && builder.length() >= maxChars) {
                    for (List<ChunkRecord> rest : byModule.values()) {
                        omitted += rest.size();
                        rest.clear();
                    }
                    break;
                }
            }
            if (!progressed) {
                for (List<ChunkRecord> rest : byModule.values()) omitted += rest.size();
                break;
            }
        }
        return new ContextSlice(builder.toString(), included, omitted, modules.size());
    }

    /** 需求文件名归属模块：取首个路径段（无路径时用文件名本身）。 */
    private static String moduleOf(String filename) {
        String normalized = filename == null ? "" : filename.replace('\\', '/');
        int slash = normalized.indexOf('/');
        return (slash < 0 ? normalized : normalized.substring(0, slash)).trim();
    }

    /** 将代码分块组装为带 evidenceId 标注的提示上下文，总长度受 maxChars 限制。 */
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

    /** 追加值但保证 builder 总长度不超出 maxChars。 */
    private static void appendBounded(StringBuilder builder, String value, int maxChars) {
        if (maxChars <= 0 || builder.length() >= maxChars) return;
        int remaining = maxChars - builder.length();
        builder.append(value, 0, Math.min(value.length(), remaining));
    }

    /** 生成命名空间内唯一且确定性的 evidenceId，冲突时追加内容摘要后缀。 */
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

    /** 判断两个作用域值是否属于同一范围（任一为空视为匹配）。 */
    private static boolean sameScope(String expected, String actual) {
        String expectedValue = safe(expected).trim();
        String actualValue = safe(actual).trim();
        return expectedValue.isBlank() || actualValue.isBlank() || expectedValue.equals(actualValue);
    }

    /** 规范化原始 ID：仅保留安全字符（字母数字._-）并限长，非法返回空串。 */
    private static String normalizedIdPart(String rawId) {
        String value = safe(rawId).trim();
        if (value.isBlank() || !SAFE_ID.matcher(value).matches()) return "";
        return value.substring(0, Math.min(value.length(), MAX_ID_PART_CHARS));
    }

    /** 计算内容指纹的 SHA-256 摘要（取前 16 字节十六进制）。 */
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

    /** 清理来源路径：统一斜杠、移除相对路径段与控制字符；绝对路径只保留文件名。 */
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
        return absolute ? safeParts.get(safeParts.size() - 1) : String.join("/", safeParts);
    }

    /** 清洗摘录文本：替换控制字符、压缩空白并限制长度。 */
    private static String boundedExcerpt(String rawText) {
        String value = safe(rawText).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ")
                .replaceAll("\\s+", " ").trim();
        if (value.length() <= MAX_EXCERPT_CHARS) return value;
        return value.substring(0, MAX_EXCERPT_CHARS) + "…";
    }

    /** 格式化行号范围，如 L12 或 L12-L34。 */
    private static String lineLocation(int startLine, int endLine) {
        int safeStart = Math.max(1, startLine);
        int safeEnd = Math.max(safeStart, endLine);
        return safeStart == safeEnd ? "L" + safeStart : "L" + safeStart + "-L" + safeEnd;
    }

    private static Integer positiveOrNull(int value) {
        return value > 0 ? value : null;
    }

    /** 返回第一个非空文本，两者皆空返回空串。 */
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
