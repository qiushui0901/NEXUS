package com.example.requirementrag.knowledge;

/**
 * 需求图片内容理解扩展点：将 HTML 中的图片字节交给实现方（OCR / Vision）产出中文描述，
 * 描述会作为结构化占位符的一部分写入分块，使图片可被检索命中。
 *
 * <p>未配置实现时保持现有“图片占位符”行为，不影响主流程。</p>
 */
@FunctionalInterface
public interface RequirementImageCaptioner {

    /**
     * 描述一张需求文档图片。
     *
     * @param src        图片地址（原始 src，可能为空）
     * @param alt        图片 alt 文本（可能为空）
     * @param caption    图注/说明（可能为空）
     * @param imageBytes 图片原始字节；无法解析时可能为 null
     * @return 图片内容描述；返回空白表示不追加
     */
    String describe(String src, String alt, String caption, byte[] imageBytes);
}
