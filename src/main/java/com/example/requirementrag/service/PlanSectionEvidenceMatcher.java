package com.example.requirementrag.service;

import com.example.requirementrag.model.CodeChunk;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将流式开发环节与本次真实代码检索结果绑定。 */
@Component
public final class PlanSectionEvidenceMatcher {

    private static final Pattern WORD = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{2,}");
    private static final List<Concept> CONCEPTS = List.of(
            new Concept("入口与接口", List.of("入口", "接口", "请求", "详情", "controller", "api", "moa", "request", "detail", "entry")),
            new Concept("配置与档位", List.of("配置", "档位", "价格", "config", "cfg", "setting", "param", "tier", "level", "recharge")),
            new Concept("状态与持久化", List.of("状态", "持久", "缓存", "数据", "state", "status", "redis", "cache", "dao", "repository", "save", "update")),
            new Concept("购买与支付", List.of("购买", "支付", "扣费", "激活", "buy", "purchase", "pay", "recharge", "balance", "order", "activate")),
            new Concept("领取与发奖", List.of("领取", "领奖", "发奖", "奖励", "claim", "receive", "reward", "bonus", "grant", "award", "add")),
            new Concept("入口与红点", List.of("红点", "展示", "福利", "入口", "red", "point", "index", "display", "view", "welfare", "module")),
            new Concept("校验与幂等", List.of("校验", "幂等", "重复", "资格", "check", "valid", "can", "lock", "guard", "idempotent"))
    );

    private final ObjectMapper objectMapper;

    public PlanSectionEvidenceMatcher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 返回复制并增强后的 section payload，不修改模型解析得到的原节点。
     *
     * @param payload 模型的 section 事件负载，可 null 或非对象
     * @param code    本次检索命中的代码块；为空时仅返回深拷贝结果
     * @return 增加 inspectTargets 字段的新 payload 节点
     */
    public ObjectNode enrich(JsonNode payload, List<CodeChunk> code) {
        ObjectNode enriched = payload != null && payload.isObject()
                ? ((ObjectNode) payload).deepCopy()
                : objectMapper.createObjectNode();
        ArrayNode targets = enriched.putArray("inspectTargets");
        if (code == null || code.isEmpty()) {
            return enriched;
        }

        String sectionText = collectText(enriched).toLowerCase(Locale.ROOT);
        Set<String> terms = expandedTerms(sectionText);
        List<ScoredChunk> scored = code.stream()
                .map(chunk -> new ScoredChunk(chunk, score(chunk, terms)))
                .sorted(Comparator.comparingInt(ScoredChunk::score).reversed())
                .toList();
        List<ScoredChunk> strong = scored.stream().filter(item -> item.score() >= 2).limit(4).toList();
        boolean recommended = strong.isEmpty();
        List<ScoredChunk> selected = recommended ? scored.stream().limit(2).toList() : strong;
        for (ScoredChunk item : selected) {
            ObjectNode target = objectMapper.valueToTree(item.chunk());
            target.put("relation", relation(sectionText, item.chunk(), recommended));
            target.put("matchType", recommended ? "recommended" : "exact");
            targets.add(target);
        }
        return enriched;
    }

    private String collectText(JsonNode payload) {
        StringBuilder text = new StringBuilder();
        append(text, payload.path("title"));
        append(text, payload.path("purpose"));
        append(text, payload.path("relatedRules"));
        append(text, payload.path("keyQuestions"));
        append(text, payload.path("changeSuggestions"));
        return text.toString();
    }

    private void append(StringBuilder target, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isArray()) {
            node.forEach(item -> append(target, item));
            return;
        }
        target.append(' ').append(node.asText(""));
    }

    private Set<String> expandedTerms(String sectionText) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = WORD.matcher(sectionText);
        while (matcher.find()) {
            terms.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        for (Concept concept : CONCEPTS) {
            if (concept.matches(sectionText)) {
                terms.addAll(concept.terms());
            }
        }
        return terms;
    }

    private int score(CodeChunk chunk, Set<String> terms) {
        String identity = (safe(chunk.symbolName()) + " " + safe(chunk.filePath())).toLowerCase(Locale.ROOT);
        String body = safe(chunk.text()).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (identity.contains(term)) score += 6;
            else if (body.contains(term)) score += 2;
        }
        return score;
    }

    private String relation(String sectionText, CodeChunk chunk, boolean recommended) {
        String codeText = (safe(chunk.symbolName()) + " " + safe(chunk.filePath()) + " " + safe(chunk.text())).toLowerCase(Locale.ROOT);
        String symbolDesc = formatSymbol(chunk);
        List<String> matchedConcepts = new java.util.ArrayList<>();
        for (Concept concept : CONCEPTS) {
            if (concept.matches(sectionText) && concept.matches(codeText)) {
                matchedConcepts.add(concept.label());
            }
        }
        if (!matchedConcepts.isEmpty()) {
            String concepts = String.join("、", matchedConcepts);
            String prefix = recommended ? "推荐参考" : "相关原因";
            return "%s：%s 中包含%s逻辑，可复用或参考其实现模式。".formatted(prefix, symbolDesc, concepts);
        }
        return "推荐参考：%s 是本次查询中最接近的现有实现，适合先确认其组织方式和可复用边界。".formatted(symbolDesc);
    }

    private String formatSymbol(CodeChunk chunk) {
        String type = switch (safe(chunk.symbolType())) {
            case "class" -> "类";
            case "method" -> "方法";
            case "file" -> "文件";
            default -> "";
        };
        String name = safe(chunk.symbolName());
        String fileName = safe(chunk.filePath());
        if (!fileName.isEmpty()) {
            int lastSlash = fileName.lastIndexOf('/');
            fileName = lastSlash >= 0 ? fileName.substring(lastSlash + 1) : fileName;
        }
        if (!name.isEmpty() && !fileName.isEmpty()) {
            return "%s %s（%s）".formatted(type, name, fileName);
        }
        if (!name.isEmpty()) {
            return type + " " + name;
        }
        return fileName.isEmpty() ? "此代码" : fileName;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    /** 领域概念及其中英文关键词，用于环节文本与代码文本的语义关联。 */
    private record Concept(String label, List<String> terms) {
        boolean matches(String text) {
            return terms.stream().anyMatch(text::contains);
        }
    }

    /** 代码块及其与环节文本的匹配分数。 */
    private record ScoredChunk(CodeChunk chunk, int score) { }
}
