package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricAlignmentStore;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.BusinessConcept;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityMention;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityResolution;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.MatchMethod;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.MentionStatus;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.ResolutionCandidate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 实体解析器（dev md §7.2 解析链）：规范化别名精确 → 已确认别名 → 成员名 → 代码符号 → LLM 受限选择
 * → 仍未命中返回多候选并标记 NEEDS_REVIEW。绝不伪造 entityId；LLM 不可用时规则链完整可用。
 */
@Service
public class EntityResolverService {

    private final CodeCentricAlignmentStore alignmentStore;
    private final EntityExtractionProperties properties;
    private final EntityLlmAssistant llm;

    public EntityResolverService(CodeCentricAlignmentStore alignmentStore,
                                 EntityExtractionProperties properties,
                                 EntityLlmAssistant llm) {
        this.alignmentStore = alignmentStore;
        this.properties = properties;
        this.llm = llm;
    }

    /** 解析问题：先按已确认别名命中（RESOLVED）；未命中走成员名/代码符号候选 + LLM 受限选择。 */
    public EntityResolution resolve(String projectId, String text) {
        List<String> warnings = new ArrayList<>();
        List<EntityMention> resolved = new ArrayList<>(aliasMentions(projectId, text));

        if (!resolved.isEmpty()) {
            return new EntityResolution(resolved, List.of(), false, warnings);
        }

        // 第 3-5 步：成员名（Claim subject / 代码符号名）候选
        List<ResolutionCandidate> candidates = memberCandidates(projectId, text);
        if (candidates.isEmpty()) {
            warnings.add("ENTITY_UNRESOLVED");
            return new EntityResolution(List.of(), List.of(), false, warnings);
        }
        if (candidates.size() == 1) {
            ResolutionCandidate candidate = candidates.get(0);
            return new EntityResolution(
                    List.of(new EntityMention(text, candidate.entityId(), candidate.canonicalName(),
                            candidate.matchMethod(), candidate.confidence(), MentionStatus.RESOLVED)),
                    List.of(), false, warnings);
        }

        // 第 7 步：LLM 受限选择；失败或歧义 → NEEDS_REVIEW 多候选
        Optional<String> selected = llm.selectEntity(text,
                candidates.stream().map(ResolutionCandidate::entityId).toList(),
                new java.util.LinkedHashSet<>(candidates.stream()
                        .map(ResolutionCandidate::entityId).toList()));
        if (selected.isPresent()) {
            ResolutionCandidate picked = candidates.stream()
                    .filter(c -> c.entityId().equals(selected.get())).findFirst().orElse(null);
            if (picked != null) {
                return new EntityResolution(
                        List.of(new EntityMention(text, picked.entityId(), picked.canonicalName(),
                                MatchMethod.LLM_SELECTED, picked.confidence(), MentionStatus.RESOLVED)),
                        List.of(), true, warnings);
            }
        }

        warnings.add("ENTITY_NEEDS_REVIEW");
        return new EntityResolution(List.of(), candidates, false, warnings);
    }

    /** 解析已由问题分析阶段确定的 mentions（规则命中或 LLM 受限补召回），直接返回已解析集合。
     * 仍未解析的 mention（歧义候选）不能静默丢弃：返回多候选并标记 NEEDS_REVIEW。 */
    public EntityResolution resolve(String projectId, String text, List<EntityMention> preResolved) {
        List<EntityMention> resolved = new ArrayList<>();
        List<EntityMention> unresolved = new ArrayList<>();
        for (EntityMention mention : preResolved) {
            if (mention.entityId() != null) {
                resolved.add(mention);
            } else {
                unresolved.add(mention);
            }
        }
        if (resolved.isEmpty()) {
            return resolve(projectId, text);
        }
        if (!unresolved.isEmpty()) {
            return new EntityResolution(resolved, List.of(), false,
                    List.of("ENTITY_NEEDS_REVIEW"));
        }
        return new EntityResolution(resolved, List.of(), false, List.of());
    }

    /** 已确认别名命中（解析链第 1-2 步，member 名已在 Phase 1 别名化）。 */
    private List<EntityMention> aliasMentions(String projectId, String text) {
        return alignmentStore.findConfirmedAliasesMentionedIn(projectId, text, properties.maxAliasScan())
                .stream().map(hit -> new EntityMention(
                        hit.alias(), hit.conceptId(), hit.displayName(),
                        MatchMethod.CONFIRMED_ALIAS, 1.0, MentionStatus.RESOLVED))
                .toList();
    }

    /** 成员名/代码符号名候选（第 3-5 步），映射回概念展示信息。 */
    private List<ResolutionCandidate> memberCandidates(String projectId, String text) {
        List<String> conceptIds = alignmentStore.findConceptIdsByMemberName(projectId, text, 20);
        if (conceptIds.isEmpty()) {
            return List.of();
        }
        Map<String, BusinessConcept> byId = new LinkedHashMap<>();
        for (BusinessConcept concept : alignmentStore.findConcepts(projectId)) {
            byId.put(concept.conceptId(), concept);
        }
        List<ResolutionCandidate> candidates = new ArrayList<>();
        for (String conceptId : conceptIds) {
            BusinessConcept concept = byId.get(conceptId);
            if (concept == null) continue;
            candidates.add(new ResolutionCandidate(conceptId, concept.displayName(),
                    concept.canonicalKey(), MatchMethod.MEMBER_NAME, 0.7));
        }
        return candidates;
    }
}