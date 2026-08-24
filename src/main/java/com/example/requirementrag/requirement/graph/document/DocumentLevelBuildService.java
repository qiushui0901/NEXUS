package com.example.requirementrag.requirement.graph.document;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.requirement.graph.RequirementGraphWindowPlanner;
import com.example.requirementrag.requirement.graph.RequirementGraphWindowPlanner.PlanOptions;
import com.example.requirementrag.requirement.graph.RequirementGraphWindow;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.BuildFingerprint;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.BuildMetrics;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.DocumentLevelBuildResult;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.DocumentStructureNode;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.EntityMention;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.EvidenceBundle;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.LocalExtraction;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.LocalRelation;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.LogicalUnit;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.SourceAnchor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档级需求抽取构建服务（Phase 0-4）：
 * 结构抽取 → 逻辑单元规划 → 局部实体抽取（规则/LLM）→ 跨窗口候选整合（规则/LLM 验证）
 * → 证据组合 → 指纹保存。
 *
 * <p>LLM 通过 {@code app.rag.document-level.llm-enabled} 开关启用；默认规则实现，任何 LLM 失败均 fail-open。
 */
@Service
public class DocumentLevelBuildService {

    private final RequirementDocumentStructureStore store;
    private final DocumentStructureExtractor extractor;
    private final LogicalUnitPlanner unitPlanner;
    private final CrossWindowIntegrator integrator;
    private final BuildFingerprintFactory fingerprintFactory;
    private final RequirementGraphWindowPlanner windowPlanner;
    private final LocalEntityExtractor localEntityExtractor;
    private final CrossWindowVerifier crossWindowVerifier;

    /** 测试/非 Spring 环境使用规则实现的便捷构造器。 */
    public DocumentLevelBuildService(RequirementDocumentStructureStore store,
                                     DocumentStructureExtractor extractor,
                                     LogicalUnitPlanner unitPlanner,
                                     CrossWindowIntegrator integrator,
                                     BuildFingerprintFactory fingerprintFactory,
                                     RequirementGraphWindowPlanner windowPlanner) {
        this(store, extractor, unitPlanner, integrator, fingerprintFactory, windowPlanner,
                new RuleLocalEntityExtractor(), new RuleCrossWindowVerifier(), false);
    }

    /** Spring 全量构造器：可按配置注入 LLM 抽取器/验证器。 */
    @Autowired
    public DocumentLevelBuildService(RequirementDocumentStructureStore store,
                                     DocumentStructureExtractor extractor,
                                     LogicalUnitPlanner unitPlanner,
                                     CrossWindowIntegrator integrator,
                                     BuildFingerprintFactory fingerprintFactory,
                                     RequirementGraphWindowPlanner windowPlanner,
                                     LocalEntityExtractor localEntityExtractor,
                                     CrossWindowVerifier crossWindowVerifier,
                                     @Value("${app.rag.document-level.llm-enabled:false}") boolean llmEnabled) {
        this.store = store;
        this.extractor = extractor;
        this.unitPlanner = unitPlanner;
        this.integrator = integrator;
        this.fingerprintFactory = fingerprintFactory;
        this.windowPlanner = windowPlanner;
        // llmEnabled=false 时强制使用规则实现，避免显式注入的空实现影响默认行为
        this.localEntityExtractor = llmEnabled && localEntityExtractor != null
                ? localEntityExtractor : new RuleLocalEntityExtractor();
        this.crossWindowVerifier = llmEnabled && crossWindowVerifier != null
                ? crossWindowVerifier : new RuleCrossWindowVerifier();
    }

    public DocumentLevelBuildResult build(String documentId, String requirementVersion,
                                          String documentRevision, String documentText) {
        store.clearDocument(documentId, requirementVersion, documentRevision);

        DocumentStructureExtractor.StructureExtraction extraction =
                extractor.extract(documentId, requirementVersion, documentRevision, documentText);
        List<DocumentStructureNode> structure = extraction.nodes();
        List<SourceAnchor> anchors = extraction.anchors();
        List<LogicalUnit> units = unitPlanner.plan(documentId, documentRevision, extraction);

        Map<String, SourceAnchor> anchorsById = new LinkedHashMap<>();
        for (SourceAnchor anchor : anchors) anchorsById.put(anchor.id(), anchor);

        List<EntityMention> entities = new ArrayList<>();
        List<LocalRelation> localRelations = new ArrayList<>();
        for (LogicalUnit unit : units) {
            List<SourceAnchor> unitAnchors = new ArrayList<>();
            for (String anchorId : unit.sourceAnchorIds()) {
                SourceAnchor anchor = anchorsById.get(anchorId);
                if (anchor != null) unitAnchors.add(anchor);
            }
            LocalExtraction extractionResult = localEntityExtractor.extract(unit, unitAnchors);
            entities.addAll(extractionResult.entities());
            localRelations.addAll(extractionResult.relations());
        }

        CrossWindowIntegrator.IntegrationResult integration =
                integrator.integrate(units, anchors, crossWindowVerifier);

        for (SourceAnchor anchor : anchors) store.saveAnchor(anchor);
        for (DocumentStructureNode node : structure) store.saveStructureNode(node);
        for (LogicalUnit unit : units) store.saveLogicalUnit(unit);
        for (EvidenceBundle bundle : integration.bundles()) {
            store.saveEvidenceBundle(documentId, requirementVersion, bundle);
        }
        BuildFingerprint fingerprint = fingerprintFactory.create(documentRevision);
        store.saveFingerprint(documentId, requirementVersion, fingerprint);

        BuildMetrics metrics = metrics(units);
        return new DocumentLevelBuildResult(documentId, requirementVersion, fingerprint, metrics,
                structure, anchors, units, List.copyOf(entities), List.copyOf(localRelations),
                integration.bundles(), integration.relations());
    }

    private BuildMetrics metrics(List<LogicalUnit> units) {
        PlanOptions options = PlanOptions.safe();
        int windowCount = 0;
        int minWindowChars = Integer.MAX_VALUE;
        int maxWindowChars = 0;
        int abnormal = 0;
        for (LogicalUnit unit : units) {
            if (unit.text().isBlank()) continue;
            ChunkRecord chunk = new ChunkRecord("unit:" + unit.id(), unit.documentId(), "",
                    "", unit.id(), unit.text(), "", "", 0, 0);
            var plan = windowPlanner.plan(chunk, 4000, options);
            List<RequirementGraphWindow> windows = plan.windows();
            windowCount += windows.size();
            for (int i = 0; i < windows.size(); i++) {
                RequirementGraphWindow window = windows.get(i);
                int length = window.endOffset() - window.startOffset();
                if (length < options.minWindowChars()) abnormal++;
                minWindowChars = Math.min(minWindowChars, length);
                maxWindowChars = Math.max(maxWindowChars, length);
                if (i > 0) {
                    int progress = window.startOffset() - windows.get(i - 1).startOffset();
                    if (progress < options.minProgressChars()) abnormal++;
                }
            }
        }
        if (minWindowChars == Integer.MAX_VALUE) minWindowChars = 0;
        return new BuildMetrics(units.size(), windowCount, minWindowChars, maxWindowChars,
                units.size(), 0, 0, 0, 0, abnormal);
    }
}
