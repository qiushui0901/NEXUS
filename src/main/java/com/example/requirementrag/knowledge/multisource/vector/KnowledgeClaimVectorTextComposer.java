package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

/**
 * 确定性类型化检索文本组合器（§5.3）。
 * <p>
 * 按 SourceType 选择固定字段顺序渲染嵌入文本。同一 Claim 的文本稳定不漂移——
 * 字段顺序不因数据库行序、字段填充顺序变化。Raw ID、时间戳、状态标签、孤立数值、
 * 完整重复 Evidence 片段不得主导嵌入文本。
 * <p>
 * 空文本（Optional.empty）表示该 Claim 不符合投影入选条件（如值-only 参数），
 * 调用方应跳过该 Claim 不为其创建 Qdrant 点。
 */
@Component
public class KnowledgeClaimVectorTextComposer {

    /** 默认 0.9.6 投影包含的来源类型。 */
    private static final Set<SourceType> ELIGIBLE_SOURCE_TYPES = Set.of(
            SourceType.REQUIREMENT,
            SourceType.PARAMETER_TABLE,
            SourceType.TEST_CASE,
            SourceType.DOUBT
    );

    private final String composerVersion;

    public KnowledgeClaimVectorTextComposer(KnowledgeClaimVectorProperties properties) {
        this.composerVersion = properties.textComposerVersion();
    }

    /** 返回当前文本组合器版本（用于指纹和 manifest）。 */
    public String composerVersion() {
        return composerVersion;
    }

    /**
     * 判断来源类型是否在默认投影范围内。
     * TEST_RESULT（高频变更）、CODE（已有独立集合）、REQUIREMENT_SEMANTIC（独立生命周期）不纳入。
     */
    public boolean isSourceEligible(SourceType sourceType) {
        return sourceType != null && ELIGIBLE_SOURCE_TYPES.contains(sourceType);
    }

    /**
     * 为 Claim 组合确定性检索文本。
     *
     * @param claim          统一 Claim 记录
     * @param businessVersion 业务版本（Scope 字段用，非 Claim 字段）
     * @return 组合文本；空表示不符合入选条件
     */
    public Optional<String> compose(KnowledgeClaimRecord claim, String businessVersion) {
        if (claim == null || !isSourceEligible(claim.sourceType())) {
            return Optional.empty();
        }
        String text = switch (claim.sourceType()) {
            case REQUIREMENT -> composeRequirement(claim);
            case PARAMETER_TABLE -> composeParameter(claim, businessVersion);
            case TEST_CASE -> composeTestCase(claim);
            case DOUBT -> composeDoubt(claim);
            default -> "";
        };
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    /**
     * 计算组合文本的 SHA-256 hex（用于 manifest 输入指纹和 payload textHash）。
     */
    public static String textHash(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    // ===== 按来源类型的固定字段顺序渲染 =====

    private String composeRequirement(KnowledgeClaimRecord claim) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Requirement]\n");
        appendField(sb, "Subject", claim.subject());
        appendField(sb, "Predicate", claim.predicate());
        appendField(sb, "Value", claim.objectValue());
        appendField(sb, "Module", extractModule(claim.factKey()));
        appendField(sb, "Fact key", claim.factKey());
        // 至少需要 Subject + (Value 或 Predicate) 才有检索价值
        if (isBlank(claim.objectValue()) && isBlank(claim.predicate())) {
            return "";
        }
        return sb.toString().trim();
    }

    private String composeParameter(KnowledgeClaimRecord claim, String businessVersion) {
        // §5.1: "Embed name, purpose, type, scope, and business description;
        //         do not create value-only points"
        // 值-only（有 Name 但无 Purpose/Description/Unit/ValueType）不建点
        if (isBlank(claim.predicate())
                && isBlank(claim.objectValue())
                && isBlank(claim.unit())
                && isBlank(claim.valueType())) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[Parameter]\n");
        appendField(sb, "Name", claim.subject());
        appendField(sb, "Purpose", claim.predicate());
        appendField(sb, "Value type", claim.valueType());
        appendField(sb, "Unit", claim.unit());
        appendField(sb, "Value", claim.objectValue());
        appendField(sb, "Scope", "Version " + businessVersion);
        appendField(sb, "Fact key", claim.factKey());
        return sb.toString().trim();
    }

    private String composeTestCase(KnowledgeClaimRecord claim) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Test Case]\n");
        appendField(sb, "Title", claim.subject());
        appendField(sb, "Preconditions", claim.predicate());
        appendField(sb, "Expected result", claim.objectValue());
        appendField(sb, "Module", extractModule(claim.factKey()));
        appendField(sb, "Fact key", claim.factKey());
        // 至少需要 Title + (Expected 或 Preconditions) 才有检索价值
        if (isBlank(claim.objectValue()) && isBlank(claim.predicate())) {
            return "";
        }
        return sb.toString().trim();
    }

    private String composeDoubt(KnowledgeClaimRecord claim) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Doubt]\n");
        appendField(sb, "Question", claim.subject());
        appendField(sb, "Answer", claim.objectValue());
        appendField(sb, "Module", extractModule(claim.factKey()));
        appendField(sb, "Fact key", claim.factKey());
        // 至少需要 Question（subject 一定非空，所以 Doubt 总可投影）
        return sb.toString().trim();
    }

    // ===== 工具方法 =====

    private static void appendField(StringBuilder sb, String label, String value) {
        if (isBlank(value)) return;
        sb.append(label).append(": ").append(value.trim()).append('\n');
    }

    /** 从 factKey 提取模块名：第一段（第一个点之前的部分）。 */
    private static String extractModule(String factKey) {
        if (isBlank(factKey)) return "";
        int dot = factKey.indexOf('.');
        return dot > 0 ? factKey.substring(0, dot).trim() : factKey.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
