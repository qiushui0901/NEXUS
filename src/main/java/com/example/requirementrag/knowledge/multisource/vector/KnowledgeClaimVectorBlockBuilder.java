package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.alignment.GameplayCardModuleResolver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把细粒度 Claim 组织成较少的语义召回块。
 * SQLite 中的 Claim 不会被合并或删除；块只负责 Qdrant 召回，命中后通过 claimIds 展开回事实层。
 */
public class KnowledgeClaimVectorBlockBuilder {

    private final KnowledgeClaimVectorTextComposer textComposer;
    private final KnowledgeClaimVectorSemanticEnhancer semanticEnhancer;
    private final KnowledgeClaimVectorProperties properties;
    private final GameplayCardModuleResolver gameplayCardResolver;

    public KnowledgeClaimVectorBlockBuilder(KnowledgeClaimVectorTextComposer textComposer,
                                             KnowledgeClaimVectorSemanticEnhancer semanticEnhancer,
                                             KnowledgeClaimVectorProperties properties) {
        this(textComposer, semanticEnhancer, properties, new GameplayCardModuleResolver());
    }

    public KnowledgeClaimVectorBlockBuilder(KnowledgeClaimVectorTextComposer textComposer,
                                             KnowledgeClaimVectorSemanticEnhancer semanticEnhancer,
                                             KnowledgeClaimVectorProperties properties,
                                             GameplayCardModuleResolver gameplayCardResolver) {
        this.textComposer = textComposer;
        this.semanticEnhancer = semanticEnhancer;
        this.properties = properties;
        this.gameplayCardResolver = gameplayCardResolver == null
                ? new GameplayCardModuleResolver() : gameplayCardResolver;
    }

    /** 按来源类型和玩法卡模块分组，并按 Qdrant payload 字符容量切分；同一玩法的多页面/多表合并。 */
    public List<SemanticBlock> build(List<KnowledgeClaimRecord> claims, String businessVersion) {
        Map<String, List<KnowledgeClaimRecord>> grouped = new LinkedHashMap<>();
        for (KnowledgeClaimRecord claim : claims == null ? List.<KnowledgeClaimRecord>of() : claims) {
            if (!textComposer.isSourceEligible(claim.sourceType())) continue;
            if (textComposer.compose(claim, businessVersion).isEmpty()) continue;
            String group = groupOf(claim);
            String key = claim.sourceType().name() + "|" + group;
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(claim);
        }

        List<SemanticBlock> blocks = new ArrayList<>();
        for (Map.Entry<String, List<KnowledgeClaimRecord>> entry : grouped.entrySet()) {
            List<KnowledgeClaimRecord> current = new ArrayList<>();
            int segment = 0;
            for (KnowledgeClaimRecord claim : entry.getValue()) {
                String claimText = textComposer.compose(claim, businessVersion).orElse("");
                if (!current.isEmpty() && exceeds(current, claimText, businessVersion)) {
                    blocks.add(block(current, businessVersion, entry.getKey(), segment++));
                    current = new ArrayList<>();
                }
                current.add(claim);
            }
            if (!current.isEmpty()) blocks.add(block(current, businessVersion, entry.getKey(), segment));
        }
        return List.copyOf(blocks);
    }

    /** 返回块的 LLM 增强文本；失败时严格回退确定性文本，增强内容只占用剩余 payload 空间。 */
    public String enhancedText(String projectId, String businessVersion, SemanticBlock block) {
        String deterministic = block.deterministicText();
        if (semanticEnhancer == null) {
            return deterministic;
        }
        return semanticEnhancer.enhance(projectId, businessVersion, block.sourceType(), block.groupName(), deterministic)
                .map(summary -> appendWithinPayloadLimit(deterministic, summary))
                .orElse(deterministic);
    }

    private String appendWithinPayloadLimit(String deterministic, String summary) {
        String marker = "\n[Semantic summary]\n";
        int remaining = properties.blockMaxChars() - deterministic.length() - marker.length();
        if (remaining <= 0 || summary == null || summary.isBlank()) {
            return deterministic;
        }
        String boundedSummary = summary.length() <= remaining
                ? summary : summary.substring(0, remaining);
        return deterministic + marker + boundedSummary;
    }

    private boolean exceeds(List<KnowledgeClaimRecord> current, String next, String businessVersion) {
        KnowledgeClaimRecord first = current.get(0);
        String header = blockHeader(first.sourceType().name(), groupOf(first), businessVersion);
        int chars = header.length() + current.stream()
                .mapToInt(claim -> textComposer.compose(claim, businessVersion).orElse("").length())
                .sum() + Math.max(0, current.size() - 1) * 2;
        return chars + (current.isEmpty() ? 0 : 2) + next.length() > properties.blockMaxChars();
    }

    private SemanticBlock block(List<KnowledgeClaimRecord> claims, String version, String groupKey, int segment) {
        KnowledgeClaimRecord first = claims.get(0);
        String group = groupOf(first);
        String facts = claims.stream()
                .map(claim -> textComposer.compose(claim, version).orElse(""))
                .filter(value -> !value.isBlank())
                .reduce((left, right) -> left + "\n\n" + right).orElse("");
        String text = blockHeader(first.sourceType().name(), group, version) + "\n\n" + facts;
        String blockId = "block:" + sha256(first.projectId() + "|" + version + "|"
                + first.sourceType() + "|" + group + "|" + segment
                + "|" + String.join(",", claims.stream().map(KnowledgeClaimRecord::claimId).toList())).substring(0, 32);
        String updatedAt = claims.stream().map(KnowledgeClaimRecord::updatedAt)
                .filter(value -> value != null && !value.isBlank()).max(String::compareTo).orElse(null);
        return new SemanticBlock(blockId, first.documentVersionId(), first.sourceType().name(), group,
                claims.stream().map(KnowledgeClaimRecord::claimId).toList(), claims, text,
                KnowledgeClaimVectorTextComposer.textHash(text), updatedAt);
    }

    private String groupOf(KnowledgeClaimRecord claim) {
        if (gameplayCardResolver.hasGameplayCardBoundary(claim)) {
            return gameplayCardResolver.resolve(claim);
        }
        String factKey = safe(claim.factKey());
        String[] parts = factKey.split("[|#]", -1);
        if (parts.length >= 3 && !parts[2].isBlank()) return parts[2].trim();
        int dot = factKey.indexOf('.');
        return dot > 0 ? factKey.substring(0, dot).trim() : safe(claim.sourceType().name()).toLowerCase(Locale.ROOT);
    }

    private String blockHeader(String sourceType, String group, String version) {
        return "[Knowledge semantic block]\n"
                + "Canonical module: " + (group == null || group.isBlank() ? "未提供" : group) + "\n"
                + "Business version: " + (version == null || version.isBlank() ? "未提供" : version) + "\n"
                + "Source type: " + (sourceType == null || sourceType.isBlank() ? "未提供" : sourceType);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public record SemanticBlock(String blockId, String documentVersionId, String sourceType,
                                String groupName, List<String> claimIds,
                                List<KnowledgeClaimRecord> claims, String deterministicText,
                                String textHash, String updatedAt) {
        public SemanticBlock {
            claimIds = claimIds == null ? List.of() : List.copyOf(claimIds);
            claims = claims == null ? List.of() : List.copyOf(claims);
        }
    }
}
