package com.example.requirementrag.requirement.graph.document;

import java.util.List;

/**
 * 文档级需求抽取改进方案领域模型（Phase 0-4）：
 * 文档结构、稳定锚点、逻辑单元、多片段证据包与构建指纹。
 *
 * <p>本层是 deterministic/rule-based 的垂直切片：
 * 结构抽取、逻辑单元规划、跨窗口候选生成与证据组合均由规则完成，
 * 为后续 LLM 抽取/二次验证提供可回查、可版本化的骨架。
 */
public final class DocumentLevelModels {
    private DocumentLevelModels() {
    }

    public enum StructureNodeType {
        DOCUMENT,
        SECTION,
        REQUIREMENT,
        LIST,
        TABLE,
        TABLE_ROW,
        FIGURE
    }

    public record DocumentStructureNode(
            String id,
            String documentId,
            String requirementVersion,
            StructureNodeType nodeType,
            String numberPath,
            String title,
            String parentNodeId,
            int order,
            String sourceAnchorId
    ) {
        public DocumentStructureNode {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id 不能为空");
            if (documentId == null || documentId.isBlank()) throw new IllegalArgumentException("documentId 不能为空");
            if (requirementVersion == null || requirementVersion.isBlank()) {
                throw new IllegalArgumentException("requirementVersion 不能为空");
            }
            if (nodeType == null) nodeType = StructureNodeType.SECTION;
            title = title == null ? "" : title;
        }
    }

    public record SourceAnchor(
            String id,
            String documentId,
            String documentRevision,
            String anchorType,
            int startOffset,
            int endOffset,
            String locatorTextHash,
            String originalText
    ) {
        public SourceAnchor {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id 不能为空");
            if (documentId == null || documentId.isBlank()) throw new IllegalArgumentException("documentId 不能为空");
            if (documentRevision == null || documentRevision.isBlank()) {
                throw new IllegalArgumentException("documentRevision 不能为空");
            }
            anchorType = anchorType == null || anchorType.isBlank() ? "RAW_CHAR_RANGE" : anchorType;
            originalText = originalText == null ? "" : originalText;
            locatorTextHash = locatorTextHash == null || locatorTextHash.isBlank()
                    ? String.valueOf(originalText.hashCode()) : locatorTextHash;
        }
    }

    public record LogicalUnit(
            String id,
            String documentId,
            String documentRevision,
            String unitType,
            List<String> structureNodeIds,
            List<String> sourceAnchorIds,
            String text,
            String previousUnitSummary,
            List<String> referencedRequirementIds
    ) {
        public LogicalUnit {
            structureNodeIds = structureNodeIds == null ? List.of() : List.copyOf(structureNodeIds);
            sourceAnchorIds = sourceAnchorIds == null ? List.of() : List.copyOf(sourceAnchorIds);
            referencedRequirementIds = referencedRequirementIds == null ? List.of() : List.copyOf(referencedRequirementIds);
            text = text == null ? "" : text;
            previousUnitSummary = previousUnitSummary == null ? "" : previousUnitSummary;
        }
    }

    public enum SupportMode {
        DIRECT,
        COMPOSITE_SUPPORTED,
        INFERRED,
        UNAVAILABLE
    }

    public record EvidenceItem(
            String sourceAnchorId,
            String windowId,
            String quote,
            int startOffset,
            int endOffset,
            String role,
            String extractionMethod
    ) {
        public EvidenceItem {
            role = role == null || role.isBlank() ? "RELATION_ASSERTION" : role;
            extractionMethod = extractionMethod == null || extractionMethod.isBlank() ? "RULE" : extractionMethod;
            quote = quote == null ? "" : quote;
        }
    }

    public record EvidenceBundle(String id, SupportMode supportMode, List<EvidenceItem> items) {
        public EvidenceBundle {
            items = items == null ? List.of() : List.copyOf(items);
            if (supportMode == null) supportMode = SupportMode.UNAVAILABLE;
        }
    }

    public record BuildFingerprint(
            String sourceRevision,
            String documentParserVersion,
            String chunkingStrategyVersion,
            String windowPlannerVersion,
            String ontologyVersion,
            String promptVersion,
            String modelId,
            String crossWindowIntegrationVersion
    ) {
        public BuildFingerprint {
            documentParserVersion = documentParserVersion == null || documentParserVersion.isBlank() ? "v1" : documentParserVersion;
            chunkingStrategyVersion = chunkingStrategyVersion == null || chunkingStrategyVersion.isBlank() ? "v1" : chunkingStrategyVersion;
            windowPlannerVersion = windowPlannerVersion == null || windowPlannerVersion.isBlank() ? "v1" : windowPlannerVersion;
            ontologyVersion = ontologyVersion == null || ontologyVersion.isBlank() ? "v1" : ontologyVersion;
            promptVersion = promptVersion == null || promptVersion.isBlank() ? "v1" : promptVersion;
            modelId = modelId == null || modelId.isBlank() ? "RULE" : modelId;
            crossWindowIntegrationVersion = crossWindowIntegrationVersion == null || crossWindowIntegrationVersion.isBlank()
                    ? "v1" : crossWindowIntegrationVersion;
        }

        public String fingerprint() {
            String raw = String.join("|",
                    sourceRevision, documentParserVersion, chunkingStrategyVersion, windowPlannerVersion,
                    ontologyVersion, promptVersion, modelId, crossWindowIntegrationVersion);
            try {
                return java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256")
                                .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 32);
            } catch (java.security.NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 unavailable", exception);
            }
        }
    }

    public record CrossWindowRelation(
            String relationId,
            String sourceEntityName,
            String targetEntityName,
            String relationType,
            String supportMode,
            List<String> evidenceBundleIds,
            String status
    ) {
        public CrossWindowRelation {
            evidenceBundleIds = evidenceBundleIds == null ? List.of() : List.copyOf(evidenceBundleIds);
            status = status == null || status.isBlank() ? "RULE_CONFIRMED" : status;
        }
    }

    public record BuildMetrics(
            int parentCount,
            int windowCount,
            int minWindowChars,
            int maxWindowChars,
            int logicalUnitCount,
            int structureNodeCount,
            int anchorCount,
            int crossWindowRelations,
            int unavailableEvidenceCount,
            int abnormalWindowCount
    ) {
    }

    public record DocumentLevelBuildResult(
            String documentId,
            String requirementVersion,
            BuildFingerprint fingerprint,
            BuildMetrics metrics,
            List<DocumentStructureNode> structure,
            List<SourceAnchor> anchors,
            List<LogicalUnit> logicalUnits,
            List<EvidenceBundle> evidenceBundles,
            List<CrossWindowRelation> relations
    ) {
        public DocumentLevelBuildResult {
            structure = structure == null ? List.of() : List.copyOf(structure);
            anchors = anchors == null ? List.of() : List.copyOf(anchors);
            logicalUnits = logicalUnits == null ? List.of() : List.copyOf(logicalUnits);
            evidenceBundles = evidenceBundles == null ? List.of() : List.copyOf(evidenceBundles);
            relations = relations == null ? List.of() : List.copyOf(relations);
        }
    }
}