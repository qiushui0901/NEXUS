package com.example.requirementrag.retrieval.pageindex;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.service.GenerationChatOptions;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PageIndex 章节选择服务（Vectorless 思路）：基于目录树让 LLM 选相关章节。
 * 失败回退空集合，调用方保持原 hybrid 顺序。
 */
@Service
public class PageIndexService {

    private static final Logger log = LoggerFactory.getLogger(PageIndexService.class);

    private final ChatClient chatClient;
    private final RagProperties properties;

    public PageIndexService(ChatClient chatClient, RagProperties properties) {
        this.chatClient = chatClient;
        this.properties = properties;
    }

    /** LLM 返回的选中父块索引。 */
    public record SelectedChapters(@JsonProperty("parentOrders") List<Integer> parentOrders) {}

    /**
     * 根据 query 与目录树选章节。
     *
     * @param query 查询
     * @param tree  目录树
     * @return 选中的 parentOrder 集合，失败或禁用时为空
     */
    public Set<Integer> selectChapters(String query, List<HtmlTreeExtractor.TocNode> tree) {
        if (tree == null || tree.isEmpty() || query == null || query.isBlank()) return Set.of();
        RagProperties.Document doc = properties.retrieval() != null ? properties.retrieval().document() : null;
        if (doc != null && !doc.resolvedPageIndexEnabled()) return Set.of();
        try {
            String treeText = toTreeText(tree);
            String model = properties.llm() != null ? properties.llm().resolvedRoutingModel() : null;
            if (model == null || model.isBlank()) model = properties.llm() != null ? properties.llm().generationModel() : "glm-5.2";
            var builder = GenerationChatOptions.forModel(model);
            SelectedChapters selected = chatClient.prompt()
                    .system("""
                            你是文档导航助手。给定文档目录（每行格式：[parentOrder] 标题 (文件名)）和用户查询，
                            请选出与查询最相关的章节的 parentOrder 列表（1-5个），按相关度降序。
                            只返回 JSON：{"parentOrders":[0,2]}，不要解释。若都不相关返回空列表。
                            """)
                    .user("目录：\n" + treeText + "\n查询：" + query)
                    .options(builder)
                    .call().entity(SelectedChapters.class);
            if (selected == null || selected.parentOrders() == null) return Set.of();
            return new HashSet<>(selected.parentOrders());
        } catch (Exception e) {
            log.warn("PageIndex selectChapters failed: {}", e.toString());
            return Set.of();
        }
    }

    private String toTreeText(List<HtmlTreeExtractor.TocNode> tree) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(tree.size(), 80);
        for (int i = 0; i < limit; i++) {
            HtmlTreeExtractor.TocNode n = tree.get(i);
            sb.append("[").append(n.parentOrder()).append("] ");
            if (n.level() == 1) sb.append("# ");
            else if (n.level() == 2) sb.append("## ");
            else sb.append("### ");
            sb.append(n.title().isBlank() ? "(无标题)" : n.title())
                    .append(" (").append(n.filename()).append(")\n");
        }
        return sb.toString();
    }

    /** 将候选按选中章节优先排序（稳定排序，选中章节保持原相对顺序并前置）。 */
    public <T> List<T> boostByChapters(List<T> candidates, java.util.function.Function<T, Integer> parentOrderFn,
                                       Set<Integer> selected) {
        if (selected == null || selected.isEmpty() || candidates == null || candidates.isEmpty()) return candidates;
        List<T> hit = new ArrayList<>();
        List<T> miss = new ArrayList<>();
        for (T c : candidates) {
            Integer order = parentOrderFn.apply(c);
            if (order != null && selected.contains(order)) hit.add(c);
            else miss.add(c);
        }
        List<T> result = new ArrayList<>(candidates.size());
        result.addAll(hit);
        result.addAll(miss);
        return List.copyOf(result);
    }
}
