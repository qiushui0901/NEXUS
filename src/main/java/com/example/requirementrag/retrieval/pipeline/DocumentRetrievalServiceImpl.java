package com.example.requirementrag.retrieval.pipeline;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.retrieval.pageindex.HtmlTreeExtractor;
import com.example.requirementrag.retrieval.pageindex.PageIndexService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 文档检索默认实现：委托现有 {@link QdrantHybridStore} 与 {@link RequirementReranker}，
 * 可选 PageIndex 树导航对 hybrid 结果做章节优先级提升（失败回退原序）。
 */
@Service
public class DocumentRetrievalServiceImpl implements DocumentRetrievalService {

    private final QdrantHybridStore documentStore;
    private final RequirementReranker requirementReranker;
    private final HtmlTreeExtractor treeExtractor;
    private final PageIndexService pageIndexService;
    private final RagProperties properties;

    @org.springframework.beans.factory.annotation.Autowired
    public DocumentRetrievalServiceImpl(QdrantHybridStore documentStore,
                                        RequirementReranker requirementReranker,
                                        HtmlTreeExtractor treeExtractor,
                                        PageIndexService pageIndexService,
                                        RagProperties properties) {
        this.documentStore = documentStore;
        this.requirementReranker = requirementReranker;
        this.treeExtractor = treeExtractor;
        this.pageIndexService = pageIndexService;
        this.properties = properties;
    }

    /** 兼容构造器：测试单测不依赖 PageIndex。 */
    public DocumentRetrievalServiceImpl(QdrantHybridStore documentStore,
                                        RequirementReranker requirementReranker) {
        this(documentStore, requirementReranker, null, null, null);
    }

    @Override
    public List<ChunkRecord> search(String collection, String query, String documentId, String version) {
        List<ChunkRecord> hits = documentStore.hybridSearch(collection, query, documentId, version);
        RagProperties.Document doc = properties != null && properties.retrieval() != null ? properties.retrieval().document() : null;
        if (doc == null || !doc.resolvedPageIndexEnabled() || hits.size() < 2) return hits;
        try {
            if (treeExtractor == null || pageIndexService == null) return hits;
            List<HtmlTreeExtractor.TocNode> tree = treeExtractor.tree(collection, documentId, version);
            if (tree.isEmpty()) return hits;
            Set<Integer> selected = pageIndexService.selectChapters(query, tree);
            if (selected.isEmpty()) return hits;
            return pageIndexService.boostByChapters(hits, ChunkRecord::parentOrder, selected);
        } catch (Exception ignored) {
            return hits;
        }
    }

    @Override
    public List<ChunkRecord> scrollCorpus(String collection, String documentId, String version) {
        return documentStore.scrollVersion(collection, documentId, version);
    }

    @Override
    public RagOutcome<List<ChunkRecord>> rerank(String query, String documentId, String version,
                                                List<ChunkRecord> candidates, int limit) {
        return requirementReranker.rerank(query, documentId, version, candidates, limit);
    }
}
