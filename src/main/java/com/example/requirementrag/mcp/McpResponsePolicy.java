package com.example.requirementrag.mcp;

import com.example.requirementrag.evidence.EvidenceRef;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.SourceSnippet;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.model.RequirementDoubt;
import com.example.requirementrag.wiki.WikiModels;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * MCP 结果的统一响应边界与路径脱敏策略：
 * 负责参数校验、结果条数/文本长度截断、绝对路径与 URI 识别（防止泄漏仓库外路径），
 * 以及总响应体超限时的降级包装。所有 MCP 工具/资源的返回都经此类处理。
 */
@Component
public class McpResponsePolicy {

    /** 匹配形如 {@code scheme:...} 的 URI（如 {@code file:}、{@code http:}），用于识别非法路径 */
    private static final Pattern URI_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*");
    /** 匹配形如 {@code C:\...} 的 Windows 绝对路径 */
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[A-Za-z]:[\\\\/].*");

    private final McpProperties properties;
    private final JsonMapper jsonMapper;

    public McpResponsePolicy(McpProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 校验必填参数：为 null 或空白则抛出 {@link IllegalArgumentException}，否则返回去首尾空白的值。
     *
     * @param value 待校验的参数值
     * @param name  参数名，用于错误信息
     * @return 去空白后的参数值
     */
    public String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    /**
     * 校验两个值必须不同（如版本比较的起止版本），相同则抛出 {@link IllegalArgumentException}。
     *
     * @param first   第一个值
     * @param second  第二个值
     * @param message 二者相同时抛出的错误信息
     */
    public void distinct(String first, String second, String message) {
        if (first.equals(second)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 将客户端请求的结果条数收敛到 [1, maxResults] 区间；未提供时默认 10。
     *
     * @param requested 客户端请求的条数，可为 null
     * @return 收敛后的条数
     */
    public int limit(Integer requested) {
        int value = requested == null ? 10 : requested;
        return Math.min(Math.max(value, 1), properties.maxResults());
    }

    /**
     * 计算源码片段的合法结束行：默认从起始行起连续 {@code maxSourceLines} 行，
     * 并保证结束行不小于起始行、不超过起始行后的 {@code maxSourceLines} 行。
     *
     * @param startLine       起始行（从 1 计），null 视为 1
     * @param requestedEndLine 客户端请求的结束行，可为 null
     * @return 收敛后的结束行号
     * @throws IllegalArgumentException startLine 非正数，或 requestedEndLine 小于 startLine
     */
    public int endLine(Integer startLine, Integer requestedEndLine) {
        int start = startLine == null ? 1 : startLine;
        if (start < 1) {
            throw new IllegalArgumentException("startLine must be positive");
        }
        if (requestedEndLine != null && requestedEndLine < start) {
            throw new IllegalArgumentException("endLine must not be before startLine");
        }
        int requested = requestedEndLine == null ? start + properties.maxSourceLines() - 1 : requestedEndLine;
        return Math.min(requested, start + properties.maxSourceLines() - 1);
    }

    /**
     * 把需求证据块转换为对外可见的命中结果：文件名做安全兜底、正文截断到摘录上限。
     *
     * @param chunk      需求证据块
     * @param evidenceId 该块的稳定证据 ID
     * @return 脱敏/截断后的需求命中
     */
    public RequirementHit requirement(ChunkRecord chunk, String evidenceId) {
        return new RequirementHit(evidenceId, safe(chunk.filename()), chunk.parentOrder(),
                bounded(chunk.parentText()), chunk.version(), chunk.documentId());
    }

    /**
     * 把代码块转换为对外可见的命中结果：路径收敛为仓库相对路径，符号名/语言等做安全兜底，正文截断。
     *
     * @param chunk      代码块
     * @param evidenceId 该块的稳定证据 ID
     * @return 脱敏/截断后的代码命中
     */
    public CodeHit code(CodeChunk chunk, String evidenceId) {
        return new CodeHit(evidenceId, relativePath(chunk.filePath()), safe(chunk.symbolType()),
                safe(chunk.symbolName()), chunk.startLine(), chunk.endLine(), bounded(chunk.text()),
                safe(chunk.commitSha()), safe(chunk.language()));
    }

    /**
     * 复制并收敛源码片段：路径转为仓库相对路径、正文截断到摘录上限。
     *
     * @param snippet 原始源码片段
     * @return 脱敏后的源码片段
     */
    public SourceSnippet source(SourceSnippet snippet) {
        return new SourceSnippet(relativePath(snippet.filePath()), snippet.startLine(), snippet.endLine(),
                bounded(snippet.text()));
    }

    /**
     * 对证据列表逐条脱敏，并截断到 {@code maxEvidence} 条；null 视为空列表。
     *
     * @param evidence 原始证据列表，可为 null
     * @return 脱敏后、数量受限的证据列表
     */
    public List<EvidenceRef> evidence(List<EvidenceRef> evidence) {
        if (evidence == null) {
            return List.of();
        }
        return evidence.stream()
                .limit(properties.maxEvidence())
                .map(this::safeEvidence)
                .toList();
    }

    /**
     * 把 Wiki 证据转换为对外可见结果：来源路径收敛为仓库相对路径，其余文本字段截断、兜底。
     *
     * @param evidence 原始 Wiki 证据
     * @return 脱敏/截断后的 Wiki 证据
     */
    public WikiEvidence wikiEvidence(WikiModels.Evidence evidence) {
        return new WikiEvidence(safe(evidence.type()), bounded(evidence.title()),
                relativeSource(firstText(evidence.filePath(), evidence.source())), safe(evidence.version()),
                bounded(evidence.location()), bounded(evidence.excerpt()), safe(evidence.commit()),
                safe(evidence.symbol()), safe(evidence.verificationStatus()));
    }

    /**
     * 把 Wiki 代码条目转换为对外可见结果：路径收敛为仓库相对路径，其余字段截断、兜底。
     *
     * @param entry 原始 Wiki 代码条目
     * @return 脱敏/截断后的代码条目
     */
    public WikiCodeEntry wikiCodeEntry(WikiModels.CodeEntry entry) {
        return new WikiCodeEntry(bounded(entry.role()), relativeSource(entry.filePath()), safe(entry.symbol()),
                safe(entry.commit()), safe(entry.changeType()), safe(entry.verificationStatus()));
    }

    /**
     * 把声明级证据转换为对外可见结果：文本截断、证据 ID 受限。
     *
     * @param claim 原始声明
     * @return 脱敏/截断后的声明
     */
    public WikiClaim wikiClaim(WikiModels.Claim claim) {
        return new WikiClaim(safe(claim.claimId()), bounded(claim.section()), bounded(claim.text()),
                safe(claim.support() == null ? null : claim.support().name()),
                claim.evidenceIds() == null ? List.of() : claim.evidenceIds().stream().limit(20).toList());
    }

    /** 把索引摘要转换为对外可见条目。 */
    public WikiIndexEntry wikiIndexEntry(WikiModels.PageSummary summary, boolean stale) {
        return new WikiIndexEntry(safe(summary.featureId()), bounded(summary.title()),
                summary.pageType() == null ? "FEATURE" : summary.pageType().name(),
                bounded(summary.summary()), safe(summary.status() == null ? null : summary.status().name()),
                summary.evidenceCount(), stale);
    }

    /**
     * 把需求存疑条目转换为对外可见结果：文本字段截断、来源位置脱敏。
     *
     * @param doubt 原始需求存疑条目
     * @return 脱敏/截断后的存疑命中
     */
    public DoubtHit doubt(RequirementDoubt doubt) {
        return new DoubtHit(bounded(doubt.module()), bounded(doubt.feature()), bounded(doubt.question()),
                String.valueOf(doubt.type()), String.valueOf(doubt.status()), safeLocation(doubt.sourceLocation()));
    }

    /**
     * 判断结果是否因超出条数上限或证据截断而被打折：
     * 请求条数、实际条数超过 {@code maxResults}，或证据列表本身被截断，即视为 truncated。
     *
     * @param requestedLimit 客户端请求的条数
     * @param resultSize     实际返回的结果条数
     * @param evidence       证据列表
     * @return 是否发生截断
     */
    public boolean truncated(int requestedLimit, int resultSize, List<?> evidence) {
        return requestedLimit > properties.maxResults()
                || resultSize > properties.maxResults()
                || evidenceTruncated(evidence);
    }

    /** 单个文本是否超过摘录字符上限。 */
    public boolean textTruncated(String value) {
        return value != null && value.length() > properties.maxExcerptCharacters();
    }

    /** 字符串列表是否因条数超限或其中任一文本超长而被截断。 */
    public boolean textListTruncated(List<String> values) {
        return values != null && (values.size() > properties.maxResults()
                || values.stream().limit(properties.maxResults()).anyMatch(this::textTruncated));
    }

    /** 集合是否因条数超过 {@code maxResults} 而被截断。 */
    public boolean collectionTruncated(List<?> values) {
        return values != null && values.size() > properties.maxResults();
    }

    /** 证据列表是否因条数超过 {@code maxEvidence} 或其中某条证据的标题/位置/摘录超长而被截断。 */
    public boolean evidenceTruncated(List<?> evidence) {
        if (evidence == null) {
            return false;
        }
        if (evidence.size() > properties.maxEvidence()) {
            return true;
        }
        return evidence.stream().limit(properties.maxEvidence())
                .filter(EvidenceRef.class::isInstance)
                .map(EvidenceRef.class::cast)
                .anyMatch(ref -> textTruncated(ref.title())
                        || textTruncated(ref.location())
                        || textTruncated(ref.excerpt()));
    }

    /**
     * 强制总响应体大小上限：序列化后超过 {@code maxResponseCharacters} 时，
     * 清空 data 与 evidence 并追加 MCP_RESPONSE_TRUNCATED 警告（truncated=true）。
     * 序列化失败视为内部错误，直接抛 {@link IllegalStateException}。
     *
     * @param response 待约束的响应
     * @param <T>      data 类型
     * @return 未超限时原样返回；超限时返回降级后的响应
     */
    public <T> McpToolResponse<T> enforceTotalLimit(McpToolResponse<T> response) {
        try {
            if (jsonMapper.writeValueAsString(response).length() <= properties.maxResponseCharacters()) {
                return response;
            }
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException exception) {
            throw new IllegalStateException("MCP response serialization failed");
        }
        List<RagWarning> warnings = new ArrayList<>(response.warnings());
        warnings.add(new RagWarning("mcp", "MCP_RESPONSE_TRUNCATED",
                "Tool response exceeded the configured size limit", 0));
        return new McpToolResponse<>(response.resolved(), null, List.of(), response.quality(), warnings, true);
    }

    /**
     * 文本截断到 {@code maxExcerptCharacters} 字符，超出部分以省略号（…）结尾；null 转为空串。
     *
     * @param value 原始文本，可为 null
     * @return 截断或兜底后的文本
     */
    public String bounded(String value) {
        if (value == null) {
            return "";
        }
        int max = properties.maxExcerptCharacters();
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    /**
     * 校验并规范化仓库相对路径：拒绝 URI、Windows 绝对路径、绝对路径及越出仓库（以 {@code ..} 开头）的路径，
     * 分隔符统一为 {@code /}。
     *
     * @param value 原始路径，可为 null 或空白
     * @return 规范化后的仓库相对路径；null/空白时返回空串
     * @throws IllegalArgumentException 路径不是仓库相对路径时抛出
     */
    public String relativePath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (URI_SCHEME.matcher(value).matches() || WINDOWS_ABSOLUTE_PATH.matcher(value).matches()) {
            throw new IllegalArgumentException("repository-relative filePath required");
        }
        Path path = Path.of(value).normalize();
        if (path.isAbsolute() || path.startsWith("..")) {
            throw new IllegalArgumentException("repository-relative filePath required");
        }
        return path.toString().replace('\\', '/');
    }

    /** 单条证据脱敏：各字段截断/兜底，来源与位置做仓库相对化处理，endLine 置空。 */
    private EvidenceRef safeEvidence(EvidenceRef ref) {
        return new EvidenceRef(ref.evidenceId(), ref.type(), ref.projectId(), ref.version(),
                bounded(ref.title()), relativeSource(ref.source()), safeLocation(ref.location()), bounded(ref.excerpt()),
                safe(ref.commitSha()), ref.startLine(), ref.endLine(), null);
    }

    /** 尽力把来源文本转为仓库相对路径；失败（如含 URI 或越界路径）时返回空串，避免泄露非仓库路径。 */
    private String relativeSource(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return relativePath(value);
        }
        catch (IllegalArgumentException exception) {
            return "";
        }
    }

    /** 位置脱敏：以 / 或 .. 开头、或形如 URI/Windows 绝对路径的定位信息一律置空，其余截断后返回。 */
    private String safeLocation(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.startsWith("/") || value.startsWith("..") || URI_SCHEME.matcher(value).matches()
                || WINDOWS_ABSOLUTE_PATH.matcher(value).matches()) {
            return "";
        }
        return bounded(value);
    }

    /** null 安全兜底：null 转为空串。 */
    private String safe(String value) {
        return Objects.toString(value, "");
    }

    /** 取第一个非空白文本；两个都空白时返回后者（可能为 null）。 */
    private String firstText(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    /** 需求检索命中的对外表示。 */
    public record RequirementHit(String evidenceId, String filename, int parentOrder, String excerpt,
                                 String version, String documentId) {
    }

    /** 代码检索命中的对外表示。 */
    public record CodeHit(String evidenceId, String filePath, String symbolType, String symbolName,
                          int startLine, int endLine, String excerpt, String commitSha, String language) {
    }

    /** Wiki 证据的对外表示。 */
    public record WikiEvidence(String type, String title, String source, String version, String location,
                               String excerpt, String commit, String symbol, String verificationStatus) {
    }

    /** Wiki 代码条目的对外表示。 */
    public record WikiCodeEntry(String role, String filePath, String symbol, String commit, String changeType,
                                String verificationStatus) {
    }

    /** 声明级证据的对外表示。 */
    public record WikiClaim(String claimId, String section, String text, String support,
                            List<String> evidenceIds) {
    }

    /** Wiki 索引条目的对外表示：页面标识、类型、摘要、状态与新鲜度。 */
    public record WikiIndexEntry(String featureId, String title, String pageType, String summary,
                                 String status, int evidenceCount, boolean stale) {
    }

    /** 需求存疑条目的对外表示。 */
    public record DoubtHit(String module, String feature, String question, String type, String status,
                           String sourceLocation) {
    }
}
