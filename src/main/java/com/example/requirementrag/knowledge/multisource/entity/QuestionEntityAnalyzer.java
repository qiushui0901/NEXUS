package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricAlignmentStore;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricAlignmentStore.AliasHit;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityMention;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityName;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityQueryPlan;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.MatchMethod;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.MentionStatus;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.QueryIntent;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.QuestionExtractionRaw;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 问题实体提取器（dev md §7）：规则优先从问题提取 mentions / 意图 / 版本条件；
 * LLM 辅助只做受限补召回，LLM 失败时规则结果完整返回。
 */
@Service
public class QuestionEntityAnalyzer {

    private static final Pattern VERSION_PATTERN = Pattern.compile("(?i)(?:版本\\s*)?(\\d+\\.\\d+(?:\\.\\d+)?)");
    private static final List<String> NUMERIC_MARKERS = List.of("多少", "上限", "下限", "阈值", "范围",
            "秒", "分钟", "毫秒", "小时", "百分比", "%", "长度", "大小", "值");
    private static final List<String> IMPLEMENTATION_MARKERS = List.of("代码", "实现", "实际支持", "支持到",
            "是否实现", "现网", "当前代码", "实现方式");
    private static final List<String> CURRENT_MARKERS = List.of("现在", "当前", "目前", "现阶段");
    private static final List<String> HISTORY_MARKERS = List.of("历史", "之前", "以前", "演进", "变化", "曾经");
    private static final List<String> VALIDATION_MARKERS = List.of("测试", "验证", "通过了吗", "单测", "用例");
    private static final List<String> CONSISTENCY_MARKERS = List.of("一致", "冲突", "对比", "偏差", "匹配");

    private final CodeCentricAlignmentStore alignmentStore;
    private final EntityExtractionProperties properties;
    private final EntityLlmAssistant llm;

    public QuestionEntityAnalyzer(CodeCentricAlignmentStore alignmentStore,
                                  EntityExtractionProperties properties,
                                  EntityLlmAssistant llm) {
        this.alignmentStore = alignmentStore;
        this.properties = properties;
        this.llm = llm;
    }

    /** 规则优先分析问题：别名命中 → mentions；意图/版本/标志为规则推导。
     * LLM 辅助只做补召回：LLM 提议的实体名必须能解析到真实概念才成为 mention，否则丢弃并告警。 */
    public EntityQueryPlan analyze(String projectId, String query) {
        List<String> warnings = new ArrayList<>();
        List<EntityMention> mentions = new ArrayList<>(ruleMentions(projectId, query));
        Set<String> mentionedNorm = new LinkedHashSet<>();
        for (EntityMention mention : mentions) {
            mentionedNorm.add(normalize(mention.text()));
        }

        if (properties.allowLlmAssist()) {
            Optional<QuestionExtractionRaw> raw = llm.analyzeQuestion(projectId, query);
            if (raw.isPresent()) {
                for (EntityName name : raw.get().entities()) {
                    String normalized = normalize(name.name());
                    if (normalized.isBlank() || mentionedNorm.contains(normalized)) {
                        continue;
                    }
                    mentionedNorm.add(normalized);
                    List<String> ids = alignmentStore.findConceptIdsByAlias(projectId, name.name());
                    if (ids.size() == 1) {
                        mentions.add(new EntityMention(name.name(), ids.get(0), null,
                                MatchMethod.LLM_SELECTED, name.confidence(), MentionStatus.RESOLVED));
                    } else if (ids.isEmpty()) {
                        warnings.add("ENTITY_UNMAPPED:" + name.name());
                    } else {
                        mentions.add(new EntityMention(name.name(), null, null,
                                MatchMethod.UNRESOLVED, name.confidence(), MentionStatus.CANDIDATE));
                    }
                }
            } else if (mentions.isEmpty()) {
                // 规则已命中时 LLM 辅助缺失只是增强不可用，不构成告警；规则一无所获才提示
                warnings.add("ENTITY_LLM_UNAVAILABLE");
            }
        }

        boolean asksNumericValue = containsAny(query, NUMERIC_MARKERS);
        boolean asksImplementation = containsAny(query, IMPLEMENTATION_MARKERS);
        boolean asksCurrentState = containsAny(query, CURRENT_MARKERS);
        boolean hasHistory = containsAny(query, HISTORY_MARKERS);
        List<String> versions = extractVersions(query);

        QueryIntent intent = asksCurrentState ? QueryIntent.CURRENT_STATE
                : asksNumericValue ? QueryIntent.NUMERIC_VALUE
                : asksImplementation ? QueryIntent.IMPLEMENTATION
                : containsAny(query, VALIDATION_MARKERS) ? QueryIntent.VALIDATION
                : containsAny(query, CONSISTENCY_MARKERS) ? QueryIntent.CONSISTENCY
                : hasHistory ? QueryIntent.HISTORY
                : QueryIntent.GENERAL;

        boolean includeHistory = (hasHistory || versions.isEmpty()) && !asksCurrentState;

        return new EntityQueryPlan(projectId, query, mentions, intent, versions,
                includeHistory, asksCurrentState, asksImplementation, asksNumericValue);
    }

    /** 规则 mentions：已确认别名在问题文本中的命中（覆盖成员名——Phase1 已把 subject 别名化）。 */
    private List<EntityMention> ruleMentions(String projectId, String query) {
        List<AliasHit> hits = alignmentStore.findConfirmedAliasesMentionedIn(
                projectId, query, properties.maxAliasScan());
        List<EntityMention> mentions = new ArrayList<>();
        for (AliasHit hit : hits) {
            mentions.add(new EntityMention(
                    hit.alias(), hit.conceptId(), hit.displayName(),
                    MatchMethod.CONFIRMED_ALIAS, 1.0, MentionStatus.RESOLVED));
        }
        return mentions;
    }

    private static boolean containsAny(String text, List<String> markers) {
        if (text == null || text.isBlank()) return false;
        for (String marker : markers) {
            if (text.contains(marker)) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\s|｜:：（）()\\[\\]【】、，,。.;；/\\\\_\\-]+", "");
    }

    static List<String> extractVersions(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> versions = new ArrayList<>();
        Matcher matcher = VERSION_PATTERN.matcher(text);
        while (matcher.find() && versions.size() < 16) {
            versions.add(matcher.group(1));
        }
        return versions;
    }
}