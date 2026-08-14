package com.example.requirementrag.code;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.CodeChunk;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 Java 源码扫描器：提取类与方法源码片段用于向量化。
 */
@Component
public class JavaCodeScanner {

    static final int MAX_CHUNK_CHARS = 6_000;
    static final int TYPE_CONTEXT_CHARS = 2_000;
    private static final int CHUNK_OVERLAP_CHARS = 400;

    private static final Pattern TYPE_PATTERN = Pattern.compile("\\b(class|interface|enum)\\s+([A-Z][A-Za-z0-9_]*)");
    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "\\b(?:public|private|protected|static|final|synchronized|native|abstract|default|\\s)+" +
                    "[\\w<>\\[\\], ?]+\\s+([a-z][A-Za-z0-9_]*)\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{");

    /**
     * 扫描配置中的 Java 仓库，返回可写入向量库的代码 chunk。
     */
    public ScanResult scan(RagProperties.Code config) throws IOException {
        Path root = Path.of(config.repositoryPath()).toAbsolutePath().normalize();
        String projectId = config.projectId();
        String commitSha = gitCommit(root);
        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> include(root, path, config))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .toList();
        }

        List<CodeChunk> chunks = new ArrayList<>();
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.indexOf('\0') >= 0 || text.getBytes(StandardCharsets.UTF_8).length > config.resolvedMaxFileBytes()) {
                continue;
            }
            String relative = normalize(root.relativize(file).toString());
            chunks.addAll(extractFile(projectId, commitSha, relative, text));
        }
        return new ScanResult(projectId, commitSha, files.size(), chunks);
    }

    /**
     * 扫描指定 commit 下的部分 Java 文件（增量索引用），文件内容通过 git show 读取。
     *
     * @param config            代码配置（含项目 ID、路径过滤与大小限制）
     * @param commitSha         目标 commit
     * @param relativeFilePaths 相对于仓库根的文件路径列表
     * @return 提取出的代码 chunk 列表
     */
    public List<CodeChunk> scanFiles(RagProperties.Code config, String commitSha, List<String> relativeFilePaths)
            throws IOException {
        Path root = Path.of(config.repositoryPath()).toAbsolutePath().normalize();
        String projectId = config.projectId();
        List<CodeChunk> chunks = new ArrayList<>();
        for (String relative : relativeFilePaths) {
            String normalized = normalize(relative);
            if (!normalized.endsWith(".java")) {
                continue;
            }
            Path file = root.resolve(normalized).normalize();
            if (!file.startsWith(root) || !include(root, file, config)) {
                continue;
            }
            String text = gitShow(root, commitSha, normalized);
            if (text == null || text.indexOf('\0') >= 0
                    || text.getBytes(StandardCharsets.UTF_8).length > config.resolvedMaxFileBytes()) {
                continue;
            }
            chunks.addAll(extractFile(projectId, commitSha, normalized, text));
        }
        return chunks;
    }

    /** 通过 git show 读取指定 commit 下文件内容；git 失败或文件不存在时返回 null。 */
    private String gitShow(Path repoRoot, String commitSha, String relativePath) {
        try {
            Process process = new ProcessBuilder("git", "show", commitSha + ":" + relativePath)
                    .directory(repoRoot.toFile())
                    .redirectErrorStream(false)
                    .start();
            byte[] stdout = process.getInputStream().readAllBytes();
            process.getErrorStream().readAllBytes();
            if (process.waitFor() != 0) {
                return null;
            }
            return new String(stdout, StandardCharsets.UTF_8);
        }
        catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /** 从源码文本中按正则提取类与方法片段并切分为 chunk；未命中任何类型/方法时退化为整个文件级 chunk。 */
    private List<CodeChunk> extractFile(String projectId, String commitSha, String filePath, String text) {
        List<CodeChunk> chunks = new ArrayList<>();
        int[] lineStarts = lineStarts(text);

        Matcher typeMatcher = TYPE_PATTERN.matcher(text);
        while (typeMatcher.find()) {
            String typeName = typeMatcher.group(2);
            int blockEnd = findBlockEnd(text, typeMatcher.end());
            int end = Math.min(blockEnd, typeMatcher.start() + TYPE_CONTEXT_CHARS);
            if (end > typeMatcher.start()) {
                chunks.addAll(chunks(projectId, commitSha, filePath, "class", typeName, text,
                        typeMatcher.start(), end, lineStarts));
            }
        }

        Matcher methodMatcher = METHOD_PATTERN.matcher(text);
        while (methodMatcher.find()) {
            String methodName = methodMatcher.group(1);
            if (isControlKeyword(methodName)) {
                continue;
            }
            int end = findBlockEnd(text, methodMatcher.end() - 1);
            if (end > methodMatcher.start()) {
                chunks.addAll(chunks(projectId, commitSha, filePath, "method", methodName, text, methodMatcher.start(), end, lineStarts));
            }
        }

        if (chunks.isEmpty()) {
            chunks.addAll(chunks(projectId, commitSha, filePath, "file", Path.of(filePath).getFileName().toString(),
                    text, 0, Math.min(text.length(), MAX_CHUNK_CHARS), lineStarts));
        }
        return chunks;
    }

    /** 将 [startOffset, endOffset) 区间切分为不超过 MAX_CHUNK_CHARS 的 chunk，段间保留 CHUNK_OVERLAP_CHARS 重叠。 */
    private List<CodeChunk> chunks(String projectId, String commitSha, String filePath, String symbolType,
                                   String symbolName, String fullText, int startOffset, int endOffset,
                                   int[] lineStarts) {
        int safeStart = Math.max(0, Math.min(startOffset, fullText.length()));
        int safeEnd = Math.max(safeStart, Math.min(endOffset, fullText.length()));
        List<CodeChunk> chunks = new ArrayList<>();
        int segmentStart = safeStart;
        while (segmentStart < safeEnd) {
            int segmentEnd = Math.min(segmentStart + MAX_CHUNK_CHARS, safeEnd);
            if (segmentEnd < safeEnd) {
                int lineBreak = fullText.lastIndexOf('\n', segmentEnd);
                if (lineBreak > segmentStart + MAX_CHUNK_CHARS / 2) {
                    segmentEnd = lineBreak + 1;
                }
            }
            String source = fullText.substring(segmentStart, segmentEnd).strip();
            if (!source.isEmpty()) {
                int startLine = lineOf(lineStarts, segmentStart);
                int endLine = lineOf(lineStarts, segmentEnd);
                String hash = sha256(projectId + '\n' + commitSha + '\n' + filePath + '\n' + symbolType
                        + '\n' + symbolName + '\n' + source);
                String id = UUID.nameUUIDFromBytes(hash.getBytes(StandardCharsets.UTF_8)).toString();
                chunks.add(new CodeChunk(id, projectId, commitSha, filePath, symbolType, symbolName,
                        startLine, endLine, source, hash));
            }
            if (segmentEnd >= safeEnd) {
                break;
            }
            segmentStart = Math.max(segmentStart + 1, segmentEnd - CHUNK_OVERLAP_CHARS);
        }
        return chunks;
    }

    /** 从指定位置起找配对的代码块结束位置，扫描时跳过字符串与字符字面量；未找到闭合大括号时返回全文长度。 */
    private int findBlockEnd(String text, int searchFrom) {
        int open = text.indexOf('{', Math.max(0, searchFrom - 1));
        if (open < 0) {
            return -1;
        }
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean escaped = false;
        for (int i = open; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (!inChar && ch == '"') {
                inString = !inString;
                continue;
            }
            if (!inString && ch == '\'') {
                inChar = !inChar;
                continue;
            }
            if (inString || inChar) {
                continue;
            }
            if (ch == '{') {
                depth++;
            }
            else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return text.length();
    }

    /** 计算文本中每一行的起始偏移（首行为 0）。 */
    private int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 二分查找偏移量所在的 1 起行号。 */
    private int lineOf(int[] lineStarts, int offset) {
        int low = 0;
        int high = lineStarts.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lineStarts[mid] <= offset) {
                low = mid + 1;
            }
            else {
                high = mid - 1;
            }
        }
        return Math.max(1, high + 1);
    }

    /** 按配置的 includes/excludes（相对路径匹配，语义见 {@link CodePathFilter}）判断文件是否参与扫描。 */
    private boolean include(Path root, Path file, RagProperties.Code config) {
        String relative = "/" + normalize(root.relativize(file).toString());
        return !CodePathFilter.excluded(relative, config.excludes())
                && CodePathFilter.included(relative, config.includes());
    }

    /** 读取仓库当前 HEAD commit SHA，失败时返回 "unknown"。 */
    private String gitCommit(Path root) {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 && !output.isBlank() ? output : "unknown";
        }
        catch (IOException exception) {
            return "unknown";
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }

    /** 排除被方法正则误配的流程控制关键字（如 if/for 等非方法声明）。 */
    private boolean isControlKeyword(String name) {
        return List.of("if", "for", "while", "switch", "catch").contains(name);
    }

    private String normalize(String path) {
        return path.replace('\\', '/');
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 扫描结果。
     */
    public record ScanResult(String projectId, String commitSha, int files, List<CodeChunk> chunks) {
    }
}
