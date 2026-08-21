package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.requirement.graph.RequirementGraphModels.ClaimStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.QueryPlan;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SearchMode;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SearchRequest;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic first-release query planner. MIX is never inferred. */
@Component
public final class RequirementGraphQueryPlanner {
    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "of", "to", "for", "and", "or", "in", "on", "is", "are", "what", "which",
            "how", "does", "do", "after", "before", "with", "from", "this", "that",
            "的", "了", "吗", "呢", "是", "和", "与", "在", "对", "把", "被", "会", "有", "哪些", "什么", "如何");
    private static final Pattern TOKEN = Pattern.compile("[\\p{IsHan}]{2,}|[A-Za-z0-9_]{2,}");
    private static final Pattern GLOBAL = Pattern.compile("影响|模块|依赖|关联|哪些系统|跨|impact|affect|module|depend|relation");
    private static final Pattern LOCAL = Pattern.compile("状态|之后|发生|规则|条件|status|after|happen|rule|when");
    private static final Pattern RELATION = Pattern.compile("影响|触发|依赖|要求|包含|转换|affect|trigger|depend|require|contain|transition");

    private final RequirementGraphProperties properties;

    public RequirementGraphQueryPlanner(RequirementGraphProperties properties) {
        this.properties = properties;
    }

    public QueryPlan plan(SearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new RequirementGraphException("GRAPH_INPUT_EMPTY", "需求语义图查询请求不完整");
        }
        SearchMode mode = request.mode() == null ? infer(request.query()) : request.mode();
        List<String> entityKeywords = keywords(request.query(), false);
        List<String> relationKeywords = keywords(request.query(), true);
        List<String> sectionKeywords = entityKeywords.stream().limit(4).toList();
        int hops = Math.min(Math.max(request.maxHops() == null ? properties.maxHops() : request.maxHops(), 0), 4);
        int limit = Math.min(Math.max(request.limit() == null ? 20 : request.limit(), 1), 50);
        Set<ClaimStatus> statuses = request.statuses() == null || request.statuses().isEmpty()
                ? Set.of(ClaimStatus.VERIFIED) : Set.copyOf(request.statuses());
        return new QueryPlan(mode, entityKeywords, relationKeywords, sectionKeywords, hops, limit, limit, limit, statuses);
    }

    private SearchMode infer(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        if (GLOBAL.matcher(normalized).find()) return SearchMode.GLOBAL;
        if (LOCAL.matcher(normalized).find()) return SearchMode.LOCAL;
        return SearchMode.LOCAL;
    }

    private List<String> keywords(String query, boolean relationOnly) {
        Matcher matcher = TOKEN.matcher(query.toLowerCase(Locale.ROOT));
        LinkedHashSet<String> values = new LinkedHashSet<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (STOPWORDS.contains(token)) continue;
            if (relationOnly && !RELATION.matcher(token).find() && token.length() < 3) continue;
            if (relationOnly && !RELATION.matcher(token).find() && token.matches("[a-z0-9_]+")) continue;
            values.add(token);
        }
        if (relationOnly) {
            Matcher relationMatcher = RELATION.matcher(query.toLowerCase(Locale.ROOT));
            while (relationMatcher.find()) values.add(relationMatcher.group());
        }
        if (values.isEmpty() && !relationOnly) values.add(query.trim());
        return List.copyOf(new ArrayList<>(values).subList(0, Math.min(values.size(), 8)));
    }
}
