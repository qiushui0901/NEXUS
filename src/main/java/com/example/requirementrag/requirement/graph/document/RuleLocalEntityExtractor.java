package com.example.requirementrag.requirement.graph.document;

import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.EntityMention;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.LocalExtraction;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.LocalRelation;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.LogicalUnit;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.SourceAnchor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 规则局部抽取（默认）：从逻辑单元文本识别 REQ 编号实体与单元内引用关系。 */
@Component
@ConditionalOnProperty(name = "app.rag.document-level.llm-enabled", havingValue = "false", matchIfMissing = true)
public class RuleLocalEntityExtractor implements LocalEntityExtractor {

    private static final Pattern REQ_ID = Pattern.compile("REQ-\\d+", Pattern.CASE_INSENSITIVE);

    @Override
    public LocalExtraction extract(LogicalUnit unit, List<SourceAnchor> unitAnchors) {
        List<EntityMention> entities = new ArrayList<>();
        List<LocalRelation> relations = new ArrayList<>();
        String anchorId = unit.sourceAnchorIds().isEmpty() ? null : unit.sourceAnchorIds().get(0);
        String own = null;
        Matcher matcher = REQ_ID.matcher(unit.text());
        while (matcher.find()) {
            String req = matcher.group().toUpperCase(Locale.ROOT);
            if (own == null) {
                own = req;
                entities.add(new EntityMention(req, "REQUIREMENT", anchorId, unit.id()));
            } else {
                entities.add(new EntityMention(req, "REQUIREMENT", anchorId, unit.id()));
                String relationId = "lr:" + sha256(unit.id() + "|" + own + "|" + req).substring(0, 24);
                relations.add(new LocalRelation(relationId, own, req, "REFERENCES", anchorId, anchorId));
            }
        }
        return new LocalExtraction(List.copyOf(entities), List.copyOf(relations));
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}