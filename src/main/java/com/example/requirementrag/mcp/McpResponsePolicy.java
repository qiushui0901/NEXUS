package com.example.requirementrag.mcp;

import com.example.requirementrag.evidence.EvidenceRef;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.SourceSnippet;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.model.RequirementDoubt;
import com.example.requirementrag.wiki.WikiModels;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Central response bounding and path-redaction policy for MCP results. */
@Component
public class McpResponsePolicy {

    private static final Pattern URI_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*");
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[A-Za-z]:[\\\\/].*");

    private final McpProperties properties;
    private final JsonMapper jsonMapper;

    public McpResponsePolicy(McpProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    public int limit(Integer requested) {
        int value = requested == null ? 10 : requested;
        return Math.min(Math.max(value, 1), properties.maxResults());
    }

    public int endLine(Integer startLine, Integer requestedEndLine) {
        int start = Math.max(1, startLine == null ? 1 : startLine);
        int requested = requestedEndLine == null ? start + properties.maxSourceLines() - 1 : requestedEndLine;
        return Math.min(requested, start + properties.maxSourceLines() - 1);
    }

    public RequirementHit requirement(ChunkRecord chunk, String evidenceId) {
        return new RequirementHit(evidenceId, safe(chunk.filename()), chunk.parentOrder(),
                bounded(chunk.parentText()), chunk.version(), chunk.documentId());
    }

    public CodeHit code(CodeChunk chunk, String evidenceId) {
        return new CodeHit(evidenceId, relativePath(chunk.filePath()), safe(chunk.symbolType()),
                safe(chunk.symbolName()), chunk.startLine(), chunk.endLine(), bounded(chunk.text()),
                safe(chunk.commitSha()), safe(chunk.language()));
    }

    public SourceSnippet source(SourceSnippet snippet) {
        return new SourceSnippet(relativePath(snippet.filePath()), snippet.startLine(), snippet.endLine(),
                bounded(snippet.text()));
    }

    public List<EvidenceRef> evidence(List<EvidenceRef> evidence) {
        if (evidence == null) {
            return List.of();
        }
        return evidence.stream()
                .limit(properties.maxEvidence())
                .map(this::safeEvidence)
                .toList();
    }

    public WikiEvidence wikiEvidence(WikiModels.Evidence evidence) {
        return new WikiEvidence(safe(evidence.type()), bounded(evidence.title()),
                relativeSource(firstText(evidence.filePath(), evidence.source())), safe(evidence.version()),
                bounded(evidence.location()), bounded(evidence.excerpt()), safe(evidence.commit()),
                safe(evidence.symbol()), safe(evidence.verificationStatus()));
    }

    public WikiCodeEntry wikiCodeEntry(WikiModels.CodeEntry entry) {
        return new WikiCodeEntry(bounded(entry.role()), relativeSource(entry.filePath()), safe(entry.symbol()),
                safe(entry.commit()), safe(entry.changeType()), safe(entry.verificationStatus()));
    }

    public DoubtHit doubt(RequirementDoubt doubt) {
        return new DoubtHit(bounded(doubt.module()), bounded(doubt.feature()), bounded(doubt.question()),
                String.valueOf(doubt.type()), String.valueOf(doubt.status()), safeLocation(doubt.sourceLocation()));
    }

    public boolean truncated(int requestedLimit, int resultSize, List<?> evidence) {
        return requestedLimit > properties.maxResults()
                || resultSize > properties.maxResults()
                || (evidence != null && evidence.size() > properties.maxEvidence());
    }

    public <T> McpToolResponse<T> enforceTotalLimit(McpToolResponse<T> response) {
        try {
            if (jsonMapper.writeValueAsString(response).length() <= properties.maxResponseCharacters()) {
                return response;
            }
        }
        catch (RuntimeException exception) {
            throw new IllegalStateException("MCP response serialization failed");
        }
        List<RagWarning> warnings = new ArrayList<>(response.warnings());
        warnings.add(new RagWarning("mcp", "MCP_RESPONSE_TRUNCATED",
                "Tool response exceeded the configured size limit", 0));
        return new McpToolResponse<>(response.resolved(), null, List.of(), response.quality(), warnings, true);
    }

    public String bounded(String value) {
        if (value == null) {
            return "";
        }
        int max = properties.maxExcerptCharacters();
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

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

    private EvidenceRef safeEvidence(EvidenceRef ref) {
        return new EvidenceRef(ref.evidenceId(), ref.type(), ref.projectId(), ref.version(),
                bounded(ref.title()), relativeSource(ref.source()), safeLocation(ref.location()), bounded(ref.excerpt()),
                safe(ref.commitSha()), ref.startLine(), ref.endLine(), null);
    }

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

    private String safe(String value) {
        return Objects.toString(value, "");
    }

    private String firstText(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    public record RequirementHit(String evidenceId, String filename, int parentOrder, String excerpt,
                                 String version, String documentId) {
    }

    public record CodeHit(String evidenceId, String filePath, String symbolType, String symbolName,
                          int startLine, int endLine, String excerpt, String commitSha, String language) {
    }

    public record WikiEvidence(String type, String title, String source, String version, String location,
                               String excerpt, String commit, String symbol, String verificationStatus) {
    }

    public record WikiCodeEntry(String role, String filePath, String symbol, String commit, String changeType,
                                String verificationStatus) {
    }

    public record DoubtHit(String module, String feature, String question, String type, String status,
                           String sourceLocation) {
    }
}
