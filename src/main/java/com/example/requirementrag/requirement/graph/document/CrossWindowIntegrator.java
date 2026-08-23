package com.example.requirementrag.requirement.graph.document;

import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.CrossWindowRelation;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.EvidenceBundle;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.EvidenceItem;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.LogicalUnit;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.SourceAnchor;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.SupportMode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 跨窗口关系补全（Phase 3）：候选生成 ≠ 发布。
 *
 * <p>当前用确定性规则实现：逻辑单元内出现 REQ 编号引用即生成候选；
 * 只有两端都有锚点且证据可回查时才以 COMPOSITE_SUPPORTED 发布，
 * 缺任一端落到 UNAVAILABLE，不进入“已证实”集合。
 */
@Component
public class CrossWindowIntegrator {

    private static final Pattern REQ_ID = Pattern.compile("REQ-\\d+", Pattern.CASE_INSENSITIVE);

    private final EvidenceComposer evidenceComposer = new EvidenceComposer();

    public IntegrationResult integrate(List<LogicalUnit> units, List<SourceAnchor> anchors) {
        Map<String, SourceAnchor> anchorsById = new LinkedHashMap<>();
        for (SourceAnchor anchor : anchors) anchorsById.put(anchor.id(), anchor);

        Map<String, LogicalUnit> unitByRequirement = new LinkedHashMap<>();
        for (LogicalUnit unit : units) {
            String own = ownRequirement(unit.text());
            if (own != null) unitByRequirement.put(own, unit);
        }

        List<CrossWindowRelation> relations = new ArrayList<>();
        List<EvidenceBundle> bundles = new ArrayList<>();
        int unavailable = 0;
        for (LogicalUnit unit : units) {
            String own = ownRequirement(unit.text());
            if (own == null) continue;
            for (String reference : references(unit.text(), own)) {
                LogicalUnit targetUnit = unitByRequirement.get(reference);
                String relationId = "cw:" + sha256(own + "|" + reference + "|REFERENCES").substring(0, 24);
                String sourceAnchorId = unit.sourceAnchorIds().isEmpty() ? null : unit.sourceAnchorIds().get(0);
                String targetAnchorId = targetUnit == null || targetUnit.sourceAnchorIds().isEmpty()
                        ? null : targetUnit.sourceAnchorIds().get(0);
                SourceAnchor sourceAnchor = sourceAnchorId == null ? null : anchorsById.get(sourceAnchorId);
                SourceAnchor targetAnchor = targetAnchorId == null ? null : anchorsById.get(targetAnchorId);

                List<EvidenceItem> items = new ArrayList<>();
                if (sourceAnchor != null) {
                    items.add(evidenceComposer.item(sourceAnchor.id(), null, sourceAnchor.originalText(),
                            sourceAnchor.startOffset(), sourceAnchor.endOffset(), "RELATION_ASSERTION", "RULE"));
                }
                if (targetAnchor != null) {
                    items.add(evidenceComposer.item(targetAnchor.id(), null, targetAnchor.originalText(),
                            targetAnchor.startOffset(), targetAnchor.endOffset(), "SUBJECT_DEFINITION", "RULE"));
                }
                String bundleId = "eb:" + sha256(relationId).substring(0, 24);
                EvidenceBundle bundle;
                if (targetAnchor == null) {
                    // 引用目标不存在：不得作为已证实事实，直接降级 UNAVAILABLE
                    bundle = new EvidenceBundle(bundleId, SupportMode.UNAVAILABLE, List.of());
                } else {
                    bundle = evidenceComposer.compose(bundleId, items);
                }
                bundles.add(bundle);
                if (bundle.supportMode() == SupportMode.UNAVAILABLE) {
                    unavailable++;
                }
                relations.add(new CrossWindowRelation(relationId, own, reference, "REFERENCES",
                        bundle.supportMode().name(), List.of(bundle.id()),
                        bundle.supportMode() == SupportMode.UNAVAILABLE ? "UNRESOLVED" : "RULE_CONFIRMED"));
            }
        }
        return new IntegrationResult(List.copyOf(relations), List.copyOf(bundles), unavailable);
    }

    private String ownRequirement(String text) {
        Matcher matcher = REQ_ID.matcher(text);
        return matcher.find() ? matcher.group().toUpperCase(Locale.ROOT) : null;
    }

    private List<String> references(String text, String own) {
        List<String> refs = new ArrayList<>();
        Matcher matcher = REQ_ID.matcher(text);
        while (matcher.find()) {
            String ref = matcher.group().toUpperCase(Locale.ROOT);
            if (!ref.equals(own) && !refs.contains(ref)) refs.add(ref);
        }
        return refs;
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record IntegrationResult(List<CrossWindowRelation> relations, List<EvidenceBundle> bundles,
                                    int unavailableEvidenceCount) {
        public IntegrationResult {
            relations = relations == null ? List.of() : List.copyOf(relations);
            bundles = bundles == null ? List.of() : List.copyOf(bundles);
        }
    }
}