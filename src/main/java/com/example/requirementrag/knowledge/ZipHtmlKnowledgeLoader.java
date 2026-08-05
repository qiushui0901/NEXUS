package com.example.requirementrag.knowledge;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.KnowledgeEntry;
import com.example.requirementrag.service.TextPreprocessor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 从 ZIP 包中加载 HTML 知识条目：按目标版本文件夹过滤候选文件，
 * 去除标签提取正文并经预处理器清洗；单文件解析上限 512KB，过滤系统噪声文件。
 */
@Component
public class ZipHtmlKnowledgeLoader {

    private static final int MAX_HTML_BYTES = 512_000;

    private final RagProperties properties;
    private final TextPreprocessor preprocessor;

    /** 注入配置与文本预处理器。 */
    public ZipHtmlKnowledgeLoader(RagProperties properties, TextPreprocessor preprocessor) {
        this.properties = properties;
        this.preprocessor = preprocessor;
    }

    /**
     * 加载 ZIP 中符合条件的 HTML 条目，并通过 progress 回调报告进度。
     */
    public List<KnowledgeEntry> load(Path zipPath, BiConsumer<Integer, String> progress) throws IOException {
        if (!Files.isRegularFile(zipPath)) {
            throw new IOException("ZIP 知识库不存在: " + zipPath);
        }

        List<ZipCandidate> candidates = listCandidates(zipPath);
        List<KnowledgeEntry> entries = new ArrayList<>(candidates.size());
        int processed = 0;
        for (ZipCandidate candidate : candidates) {
            processed++;
            progress.accept(processed, candidate.entryName());
            String text = parseHtml(candidate.bytes(), candidate.entryName());
            if (text.isBlank()) {
                continue;
            }
            entries.add(new KnowledgeEntry(candidate.entryName(), text));
        }
        return entries;
    }

    /** 统计 ZIP 中符合条件的 HTML 候选文件数。 */
    public int countCandidates(Path zipPath) throws IOException {
        return listCandidates(zipPath).size();
    }

    /** 列出 ZIP 内通过过滤条件的 HTML 候选及其字节内容。 */
    private List<ZipCandidate> listCandidates(Path zipPath) throws IOException {
        List<ZipCandidate> candidates = new ArrayList<>();
        int minBytes = properties.knowledge().minHtmlBytes();
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory() || !isUsefulHtml(entry.getName(), entry.getSize(), minBytes)) {
                    continue;
                }
                byte[] bytes = input.readAllBytes();
                if (bytes.length < minBytes) {
                    continue;
                }
                candidates.add(new ZipCandidate(entry.getName(), bytes));
            }
        }
        return candidates;
    }

    /** 解析 HTML 字节为清洗后的纯文本。 */
    private String parseHtml(byte[] bytes, String filename) {
        byte[] bounded = bytes.length > MAX_HTML_BYTES ? java.util.Arrays.copyOf(bytes, MAX_HTML_BYTES) : bytes;
        return preprocessor.clean(stripHtml(new String(bounded, StandardCharsets.UTF_8)));
    }

    /** 去除 HTML 标签，保留可见文本。 */
    private String stripHtml(String html) {
        StringBuilder builder = new StringBuilder(Math.min(html.length(), MAX_HTML_BYTES));
        boolean inTag = false;
        for (int index = 0; index < html.length() && builder.length() < MAX_HTML_BYTES; index++) {
            char ch = html.charAt(index);
            if (ch == '<') {
                inTag = true;
                continue;
            }
            if (ch == '>') {
                inTag = false;
                continue;
            }
            if (!inTag) {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    /** 判断 HTML 文件是否有导入价值（路径、大小、类型过滤）。 */
    private boolean isUsefulHtml(String name, long size, int minBytes) {
        String normalized = name.replace('\\', '/');
        if (normalized.contains("__MACOSX/") || normalized.endsWith(".DS_Store")) {
            return false;
        }
        if (!normalized.toLowerCase(Locale.ROOT).endsWith(".html")) {
            return false;
        }
        if (!matchesTargetFolder(normalized)) {
            return false;
        }
        if (normalized.contains("/resources/")) {
            return false;
        }
        String fileName = Path.of(normalized).getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.startsWith("start") || "index.html".equals(fileName)) {
            return false;
        }
        return size <= 0 || size >= minBytes;
    }

    /** 判断文件路径是否匹配配置的目标版本文件夹前缀。 */
    private boolean matchesTargetFolder(String normalizedPath) {
        String prefix = properties.knowledge().resolvedZipFolderPrefix();
        if (prefix == null || prefix.isBlank()) {
            return true;
        }
        String folder = prefix.endsWith("/") ? prefix : prefix + "/";
        return normalizedPath.startsWith(folder);
    }

    /** ZIP 内单个 HTML 候选条目。 */
    private record ZipCandidate(String entryName, byte[] bytes) {
    }
}
