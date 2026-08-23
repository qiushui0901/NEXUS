package com.example.requirementrag.requirement.graph.document;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.requirement.graph.RequirementGraphWindowPlanner;
import com.example.requirementrag.requirement.graph.RequirementGraphWindowPlanner.Plan;
import com.example.requirementrag.requirement.graph.RequirementGraphWindowPlanner.PlanOptions;
import com.example.requirementrag.requirement.graph.RequirementGraphWindow;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.BuildFingerprint;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.BuildMetrics;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.CrossWindowRelation;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.DocumentLevelBuildResult;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.DocumentStructureNode;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.EvidenceBundle;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.LogicalUnit;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.SourceAnchor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文档级需求抽取构建服务（Phase 0-4 垂直切片）：
 * 结构抽取 → 逻辑单元规划 → 跨窗口候选整合 → 证据组合 → 指纹保存。
 *
 * <p>当前为 deterministic/rule-based，后续可将局部实体/关系抽取替换为 LLM，
 * 并让 CrossWindowIntegrator 使用受限候选集做二次验证。
 */
@Service
public class DocumentLevelBuildService {

    private final RequirementDocumentStructureStore store;
    private final DocumentStructureExtractor extractor;
    private final LogicalUnitPlanner unitPlanner;
    private final CrossWindowIntegrator integrator;
    private final BuildFingerprintFactory fingerprintFactory;
    private final RequirementGraphWindowPlanner windowPlanner;

    public DocumentLevelBuildService(RequirementDocumentStructureStore store,
                                     DocumentStructureExtractor extractor,
                                     LogicalUnitPlanner unitPlanner,
                                     CrossWindowIntegrator integrator,
                                     BuildFingerprintFactory fingerprintFactory,
                                     RequirementGraphWindowPlanner windowPlanner) {
        this.store = store;
        this.extractor = extractor;
        this.unitPlanner = unitPlanner;
        this.integrator = integrator;
        this.fingerprintFactory = fingerprintFactory;
        this.windowPlanner = windowPlanner;
    }

    public DocumentLevelBuildResult build(String documentId, String requirementVersion,
                                          String documentRevision, String documentText) {
        store.clearDocument(documentId, requirementVersion, documentRevision);

        DocumentStructureExtractor.StructureExtraction extraction =
                extractor.extract(documentId, requirementVersion, documentRevision, documentText);
        List<DocumentStructureNode> structure = extraction.nodes();
        List<SourceAnchor> anchors = extraction.anchors();
        List<LogicalUnit> units = unitPlanner.plan(documentId, documentRevision, extraction);
        CrossWindowIntegrator.IntegrationResult integration = integrator.integrate(units, anchors);

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
                structure, anchors, units, integration.bundles(), integration.relations());
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
            Plan plan = windowPlanner.plan(chunk, 4000, options);
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