package com.example.requirementrag.knowledge;

import com.example.requirementrag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档图片理解服务（Vision — GLM 5.2）。
 * 默认关闭（先不批量注入），启用后可通过 {@link #captionSingle} / {@link #captionBatch}
 * 按需对单张或批量图片做 caption，失败回退 alt。
 * 文档侧专用，与代码侧检索完全隔离；走 OpenAI 兼容网关，model 默认 {@code glm-5.2}。
 */
@Service
public class ImageUnderstandingService implements RequirementImageCaptioner {

    private static final Logger log = LoggerFactory.getLogger(ImageUnderstandingService.class);
    private static final String PROMPT = """
            你是需求文档分析师。请用中文简洁描述这张图片（GLM 5.2 视觉模型）：
            1. 类型（流程图/时序图/原型图/状态图/表格截图/配图）
            2. 包含的关键元素与文字（OCR）
            3. 逻辑含义（50-120字）
            若无法识别，返回空字符串。
            """;

    private final RagProperties properties;
    private final ChatClient chatClient;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public ImageUnderstandingService(RagProperties properties, ChatClient chatClient) {
        this.properties = properties;
        this.chatClient = chatClient;
    }

    /**
     * 按需单张 caption（优先使用，批量注入暂不启用）。
     *
     * @param imageBytes 图片字节
     * @param imageKey   归一化路径（用于 mime 猜测与日志）
     * @return caption，失败或禁用时为空
     */
    @Override
    public String describe(String src, String alt, String caption, byte[] imageBytes) {
        String key = src == null || src.isBlank() ? "image.png" : src;
        String result = captionSingle(imageBytes, key);
        return result.isBlank() ? (caption == null || caption.isBlank() ? alt : caption) : result;
    }

    public String captionSingle(byte[] imageBytes, String imageKey) {
        RagProperties.Vision vision = properties.vision();
        if (vision == null || !vision.resolvedEnabled() || imageBytes == null || imageBytes.length == 0) {
            return "";
        }
        String cacheKey = sha256(imageBytes);
        String cached = cache.get(cacheKey);
        if (cached != null) return cached;
        String caption = tryCaption(imageBytes, imageKey == null ? "image.png" : imageKey);
        if (caption != null && !caption.isBlank()) cache.put(cacheKey, caption);
        return caption == null ? "" : caption;
    }

    /**
     * 批量获取图片 caption，带 SHA 去重与降级。
     * 当前 ingestion 链路暂不自动调用，按需通过 {@link #captionSingle} 触发即可。
     *
     * @param images key=归一化路径，value=图片字节
     * @return key→caption 映射，失败或禁用时为空或 alt 回退
     */
    public Map<String, String> captionBatch(Map<String, byte[]> images) {
        RagProperties.Vision vision = properties.vision();
        if (vision == null || !vision.resolvedEnabled() || images == null || images.isEmpty()) {
            return Map.of();
        }
        int max = vision.resolvedMaxImagesPerDoc();
        Map<String, String> result = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, byte[]> entry : images.entrySet()) {
            if (count >= max) break;
            String key = entry.getKey();
            byte[] bytes = entry.getValue();
            if (bytes == null || bytes.length == 0) continue;
            String cacheKey = sha256(bytes);
            String cached = cache.get(cacheKey);
            if (cached != null) {
                result.put(key, cached);
                count++;
                continue;
            }
            String caption = tryCaption(bytes, key);
            if (caption != null && !caption.isBlank()) {
                cache.put(cacheKey, caption);
                result.put(key, caption);
            }
            count++;
        }
        return result;
    }

    private String tryCaption(byte[] bytes, String key) {
        try {
            RagProperties.Vision vision = properties.vision();
            String model = vision.resolvedModel(properties.llm() != null ? properties.llm().generationModel() : null);
            String mime = guessMime(key);
            // Spring AI 2.0 vision: user message with media
            String response = chatClient.prompt()
                    .system(PROMPT)
                    .user(u -> u.text("请描述这张图片").media(org.springframework.util.MimeTypeUtils.parseMimeType(mime),
                            new org.springframework.core.io.ByteArrayResource(bytes)))
                    .options(com.example.requirementrag.service.GenerationChatOptions.forModel(model))
                    .call().content();
            return response == null ? "" : response.strip();
        } catch (Exception e) {
            log.warn("vision caption failed for {}: {}", key, e.toString());
            return "";
        }
    }

    private String guessMime(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    private String sha256(byte[] bytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(bytes.length);
        }
    }
}
