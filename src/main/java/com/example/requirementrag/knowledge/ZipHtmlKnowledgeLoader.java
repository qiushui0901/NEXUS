package com.example.requirementrag.knowledge;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.KnowledgeEntry;
import com.example.requirementrag.service.TextPreprocessor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 从 ZIP 包中加载 HTML 知识条目：按目标版本文件夹过滤候选文件，
 * 使用 Jsoup 结构化抽取标题/表格/列表/图注，图走占位符预留 Vision 扩展；
 * 单文件解析上限 512KB，过滤系统噪声文件。
 *
 * <p>文档侧独立演进，代码侧索引不经过此 Loader。</p>
 */
@Component
public class ZipHtmlKnowledgeLoader {

    private static final int MAX_HTML_BYTES = 512_000;
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final long MAX_IMAGE_TOTAL_BYTES = 64L * 1024 * 1024;
    private static final int MAX_IMAGE_COUNT = 512;
    private static final Pattern HTML_CHARSET = Pattern.compile(
            "charset\\s*=\\s*[\"']?([A-Za-z0-9._-]+)", Pattern.CASE_INSENSITIVE);

    private final RagProperties properties;
    private final TextPreprocessor preprocessor;
    private final RequirementImageCaptioner imageCaptioner;

    /** 注入配置、文本预处理器与可选的图片内容理解实现。 */
    public ZipHtmlKnowledgeLoader(RagProperties properties, TextPreprocessor preprocessor,
                                  ObjectProvider<RequirementImageCaptioner> imageCaptionerProvider) {
        this.properties = properties;
        this.preprocessor = preprocessor;
        this.imageCaptioner = imageCaptionerProvider.getIfAvailable();
    }

    /**
     * 加载 ZIP 中符合条件的 HTML 条目，并通过 progress 回调报告进度。
     */
    public List<KnowledgeEntry> load(Path zipPath, BiConsumer<Integer, String> progress) throws IOException {
        if (!Files.isRegularFile(zipPath)) {
            throw new IOException("ZIP 知识库不存在: " + zipPath);
        }

        List<ZipCandidate> candidates = listCandidates(zipPath);
        // 收集 ZIP 内所有图片字节，供 <img src> 解析（大小写不敏感）
        Map<String, byte[]> imageIndex = buildImageIndex(zipPath);
        List<KnowledgeEntry> entries = new ArrayList<>(candidates.size());
        int processed = 0;
        for (ZipCandidate candidate : candidates) {
            processed++;
            progress.accept(processed, candidate.entryName());
            String text = parseHtml(candidate.bytes(), candidate.entryName(), imageIndex);
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
                byte[] bytes = readBounded(input, MAX_HTML_BYTES);
                if (bytes == null || bytes.length < minBytes) {
                    continue;
                }
                candidates.add(new ZipCandidate(entry.getName(), bytes));
            }
        }
        return candidates;
    }

    /** 构建 ZIP 内图片索引（key 为小写归一化路径）。 */
    private Map<String, byte[]> buildImageIndex(Path zipPath) {
        Map<String, byte[]> index = new LinkedHashMap<>();
        long totalBytes = 0;
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String normalized = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (isImageFile(normalized) && index.size() < MAX_IMAGE_COUNT) {
                    byte[] bytes = readBounded(input, MAX_IMAGE_BYTES);
                    if (bytes != null && totalBytes + bytes.length <= MAX_IMAGE_TOTAL_BYTES) {
                        index.put(normalized, bytes);
                        totalBytes += bytes.length;
                    }
                }
            }
        } catch (IOException ignored) {
            // 索引失败不阻塞主流程
        }
        return index;
    }

    /** 读取 ZIP 条目并在超过上限时丢弃该条目，避免无界内存分配。 */
    private byte[] readBounded(java.io.InputStream input, int maxBytes) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            if (total > maxBytes - read) {
                while (input.read(buffer) >= 0) { }
                return null;
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private boolean isImageFile(String normalized) {
        return normalized.endsWith(".png") || normalized.endsWith(".jpg") || normalized.endsWith(".jpeg")
                || normalized.endsWith(".gif") || normalized.endsWith(".webp") || normalized.endsWith(".bmp")
                || normalized.endsWith(".svg");
    }

    /** 解析 HTML 字节为清洗后的纯文本（结构化）。 */
    private String parseHtml(byte[] bytes, String filename, Map<String, byte[]> imageIndex) {
        byte[] bounded = bytes.length > MAX_HTML_BYTES ? java.util.Arrays.copyOf(bytes, MAX_HTML_BYTES) : bytes;
        String html = new String(bounded, detectCharset(bounded));
        Map<String, String> captions = captionImages(html, filename, imageIndex);
        String structured = extractStructuredText(html, filename, imageIndex, captions);
        return preprocessor.clean(structured);
    }

    /** 从 BOM / HTML meta charset 推断编码，兜底 UTF-8。 */
    private Charset detectCharset(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        int headLength = Math.min(bytes.length, 4_096);
        String head = new String(bytes, 0, headLength, StandardCharsets.ISO_8859_1);
        Matcher matcher = HTML_CHARSET.matcher(head);
        if (matcher.find()) {
            try {
                return Charset.forName(matcher.group(1));
            } catch (RuntimeException ignored) {
                // 未知编码按 UTF-8 处理
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** 兼容旧调用（无图索引时回退）。 */
    private String parseHtml(byte[] bytes, String filename) {
        return parseHtml(bytes, filename, Map.of());
    }

    /**
     * 文档级有界 Vision caption：数量、并发和超时均在实际 ZIP 导入链路生效。
     * 超时或单图失败只跳过 caption，保留原有图片占位符和 alt/图注。
     */
    private Map<String, String> captionImages(String html, String filename, Map<String, byte[]> imageIndex) {
        RagProperties.Vision vision = properties.vision();
        if (imageCaptioner == null || vision == null || !vision.resolvedEnabled()) return Map.of();
        Document doc = Jsoup.parse(html, filename);
        Map<String, CaptionRequest> requests = new LinkedHashMap<>();
        int max = vision.resolvedMaxImagesPerDoc();
        for (Element image : doc.select("img")) {
            if (requests.size() >= max) break;
            String src = image.attr("src").strip();
            String imageRef = resolveImageRef(src, imageIndex);
            byte[] bytes = imageBytesFor(imageRef, imageIndex);
            if (bytes == null || bytes.length == 0 || imageRef.isBlank()) continue;
            String alt = image.attr("alt").strip();
            String figureCaption = "";
            Element parent = image.parent();
            if (parent != null) {
                Element fig = parent.selectFirst("figcaption");
                if (fig != null) figureCaption = fig.text().strip();
            }
            requests.putIfAbsent(imageRef, new CaptionRequest(src, alt, figureCaption, bytes));
        }
        if (requests.isEmpty()) return Map.of();

        int concurrency = vision.resolvedConcurrency();
        long timeoutMs = vision.resolvedTimeoutMs();
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        Map<String, Future<String>> futures = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, CaptionRequest> entry : requests.entrySet()) {
                CaptionRequest request = entry.getValue();
                futures.put(entry.getKey(), executor.submit(() -> imageCaptioner.describe(
                        request.src(), request.alt(), request.caption(), request.bytes())));
            }
            long rounds = Math.max(1, (requests.size() + concurrency - 1L) / concurrency);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs * rounds);
            Map<String, String> captions = new LinkedHashMap<>();
            for (Map.Entry<String, Future<String>> entry : futures.entrySet()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    String caption = entry.getValue().get(Math.max(1L,
                            TimeUnit.NANOSECONDS.toMillis(remaining)), TimeUnit.MILLISECONDS);
                    if (caption != null && !caption.isBlank()) captions.put(entry.getKey(), caption.strip());
                } catch (TimeoutException exception) {
                    break;
                } catch (Exception exception) {
                    // 单图 Vision 失败不阻塞 HTML 正文导入。
                }
            }
            return Map.copyOf(captions);
        } finally {
            futures.values().forEach(future -> {
                if (!future.isDone()) future.cancel(true);
            });
            executor.shutdownNow();
        }
    }

    private record CaptionRequest(String src, String alt, String caption, byte[] bytes) {
    }

    /** Jsoup 结构化抽取：标题→markdown，表格→markdown，列表→-，图→占位符。 */
    private String extractStructuredText(String html, String filename, Map<String, byte[]> imageIndex) {
        return extractStructuredText(html, filename, imageIndex, Map.of());
    }

    private String extractStructuredText(String html, String filename, Map<String, byte[]> imageIndex,
                                         Map<String, String> imageCaptions) {
        Document doc = Jsoup.parse(html, filename);
        doc.select("script, style, nav, header, footer, noscript").remove();
        // 移除隐藏噪声
        doc.select("[style*=display:none], [hidden]").remove();

        StringBuilder out = new StringBuilder(Math.min(html.length(), MAX_HTML_BYTES));
        // 标题计数，用于后续图占位符关联章节
        final String[] currentHeading = {""};

        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(org.jsoup.nodes.Node node, int depth) {
                if (!(node instanceof Element el)) return;
                String tag = el.tagName().toLowerCase(Locale.ROOT);
                switch (tag) {
                    case "h1" -> {
                        String t = el.text().strip();
                        if (!t.isBlank()) {
                            currentHeading[0] = t;
                            out.append("\n# ").append(t).append("\n");
                        }
                    }
                    case "h2" -> {
                        String t = el.text().strip();
                        if (!t.isBlank()) {
                            currentHeading[0] = t;
                            out.append("\n## ").append(t).append("\n");
                        }
                    }
                    case "h3", "h4" -> {
                        String t = el.text().strip();
                        if (!t.isBlank()) {
                            currentHeading[0] = t;
                            String prefix = tag.equals("h3") ? "### " : "#### ";
                            out.append("\n").append(prefix).append(t).append("\n");
                        }
                    }
                    case "p", "div" -> {
                        // 段落由 textNodes 处理，这里仅保证换行
                    }
                    case "br" -> out.append("\n");
                    case "li" -> {
                        String t = el.ownText().strip();
                        if (!t.isBlank()) out.append("\n- ").append(t);
                    }
                    case "tr" -> {
                        // 表格行在 table 处理中统一输出，此处跳过避免重复
                    }
                    case "img" -> {
                        String alt = el.attr("alt").strip();
                        String src = el.attr("src").strip();
                        String figCaption = "";
                        Element parent = el.parent();
                        if (parent != null) {
                            Element fig = parent.selectFirst("figcaption");
                            if (fig != null) figCaption = fig.text().strip();
                        }
                        String imageRef = resolveImageRef(src, imageIndex);
                        out.append("\n[图片");
                        if (!currentHeading[0].isBlank()) out.append(": ").append(currentHeading[0]);
                        if (!alt.isBlank()) out.append(" | alt: ").append(alt);
                        if (!figCaption.isBlank()) out.append(" | 说明: ").append(figCaption);
                        if (!imageRef.isBlank()) out.append(" | src: ").append(imageRef);
                        String description = imageCaptions.get(imageRef);
                        if (description != null && !description.isBlank()) {
                            out.append(" | 内容: ").append(description);
                        }
                        if (isDataUri(src)) {
                            out.append(" | 类型:内嵌图");
                        } else if (!src.isBlank()) {
                            out.append(" | 类型:").append(guessImageType(src));
                        }
                        out.append("]\n");
                    }
                    case "table" -> out.append("\n").append(tableToMarkdown(el)).append("\n");
                    case "figcaption" -> {
                        // 已在 img 中合并，避免重复输出
                        if (el.parent() != null && el.parent().selectFirst("img") != null) {
                            // skip
                        } else {
                            String t = el.text().strip();
                            if (!t.isBlank()) out.append("\n").append(t).append("\n");
                        }
                    }
                    default -> {}
                }
            }

            @Override
            public void tail(org.jsoup.nodes.Node node, int depth) {
                if (node instanceof Element el) {
                    String tag = el.tagName().toLowerCase(Locale.ROOT);
                    if (tag.equals("p") || tag.equals("div") || tag.equals("section") || tag.equals("article")) {
                        String own = el.ownText().strip();
                        if (!own.isBlank()) {
                            // 避免与 li/h 重复：仅当元素不含块级子元素时输出 ownText
                            if (el.select("p, h1, h2, h3, h4, li, table, img").isEmpty()) {
                                out.append(own).append("\n");
                            }
                        } else if (el.text().strip().isBlank()) {
                            // 空段落跳过
                        }
                    }
                } else if (node instanceof org.jsoup.nodes.TextNode tn) {
                    String text = tn.text().strip();
                    if (text.isBlank()) return;
                    Element parent = (Element) tn.parent();
                    if (parent == null) return;
                    String pTag = parent.tagName().toLowerCase(Locale.ROOT);
                    // 已由 h/li/table/img 处理的标签不再重复输出文本节点
                    if (pTag.equals("h1") || pTag.equals("h2") || pTag.equals("h3") || pTag.equals("h4")
                            || pTag.equals("li") || pTag.equals("td") || pTag.equals("th")
                            || pTag.equals("figcaption") || pTag.equals("script") || pTag.equals("style")) {
                        return;
                    }
                    // 表格内文本已由 tableToMarkdown 处理
                    if (parent.closest("table") != null) return;
                    out.append(text).append(" ");
                }
            }
        }, doc.body() != null ? doc.body() : doc);

        return out.toString();
    }

    private String resolveImageRef(String src, Map<String, byte[]> imageIndex) {
        if (src.isBlank() || isDataUri(src) || src.startsWith("http://") || src.startsWith("https://")) {
            return src.length() > 120 ? src.substring(0, 120) + "..." : src;
        }
        String normalized = src.replace('\\', '/').toLowerCase(Locale.ROOT);
        // 去除 ./ 前缀与查询串
        if (normalized.startsWith("./")) normalized = normalized.substring(2);
        int q = normalized.indexOf('?');
        if (q >= 0) normalized = normalized.substring(0, q);
        int hash = normalized.indexOf('#');
        if (hash >= 0) normalized = normalized.substring(0, hash);
        // 在索引中模糊匹配尾部
        for (String key : imageIndex.keySet()) {
            if (key.endsWith(normalized) || normalized.endsWith(key)) {
                return key;
            }
        }
        return src;
    }

    private byte[] imageBytesFor(String imageRef, Map<String, byte[]> imageIndex) {
        if (imageRef == null || imageRef.isBlank() || isDataUri(imageRef)) {
            return null;
        }
        return imageIndex.get(imageRef.toLowerCase(Locale.ROOT));
    }

    private boolean isDataUri(String src) {
        return src.toLowerCase(Locale.ROOT).startsWith("data:image/");
    }

    private String guessImageType(String src) {
        String lower = src.toLowerCase(Locale.ROOT);
        if (lower.contains("flow") || lower.contains("流程")) return "流程图";
        if (lower.contains("sequence") || lower.contains("时序")) return "时序图";
        if (lower.contains("state") || lower.contains("状态")) return "状态图";
        if (lower.contains("proto") || lower.contains("原型") || lower.contains("wireframe")) return "原型图";
        return "配图";
    }

    private String tableToMarkdown(Element table) {
        List<Element> rows = table.select("tr");
        if (rows.isEmpty()) return "";
        StringBuilder md = new StringBuilder();
        boolean headerDone = false;
        for (Element row : rows) {
            List<Element> cells = row.select("th, td");
            if (cells.isEmpty()) continue;
            md.append("|");
            for (Element cell : cells) {
                String t = cell.text().strip().replace("|", "\\|").replace("\n", " ");
                md.append(" ").append(t.isBlank() ? " " : t).append(" |");
            }
            md.append("\n");
            if (!headerDone) {
                md.append("|");
                for (int i = 0; i < cells.size(); i++) md.append(" --- |");
                md.append("\n");
                headerDone = true;
            }
        }
        return md.toString();
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
