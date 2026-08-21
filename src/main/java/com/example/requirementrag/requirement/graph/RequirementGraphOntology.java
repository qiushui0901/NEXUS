package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.requirement.graph.RequirementGraphModels.EntityType;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.RelationType;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Versioned ontology compatibility matrix: unknown relation combinations are rejected by default. */
public final class RequirementGraphOntology {
    private RequirementGraphOntology() {
    }

    private static final Set<EntityType> REQUIREMENT_LIKE = EnumSet.of(
            EntityType.REQUIREMENT, EntityType.FEATURE, EntityType.RULE, EntityType.PROCESS,
            EntityType.BUSINESS_OBJECT, EntityType.DATA_ENTITY, EntityType.INTERFACE,
            EntityType.CONFIGURATION, EntityType.EXTERNAL_SYSTEM, EntityType.ACCEPTANCE_CRITERION);
    private static final Set<EntityType> PROCESS_ACTOR = EnumSet.of(
            EntityType.REQUIREMENT, EntityType.FEATURE, EntityType.PROCESS, EntityType.ACTOR, EntityType.RULE);
    private static final Set<EntityType> DATA_TARGET = EnumSet.of(
            EntityType.BUSINESS_OBJECT, EntityType.DATA_ENTITY, EntityType.EXTERNAL_SYSTEM,
            EntityType.MODULE, EntityType.INTERFACE);

    private static final Map<RelationType, Rule> RULES = Map.ofEntries(
            Map.entry(RelationType.DEPENDS_ON, new Rule(REQUIREMENT_LIKE, REQUIREMENT_LIKE)),
            Map.entry(RelationType.REFINES, new Rule(
                    EnumSet.of(EntityType.REQUIREMENT, EntityType.FEATURE, EntityType.RULE, EntityType.PROCESS),
                    EnumSet.of(EntityType.REQUIREMENT, EntityType.FEATURE, EntityType.RULE))),
            Map.entry(RelationType.CONFLICTS_WITH, new Rule(
                    EnumSet.of(EntityType.REQUIREMENT, EntityType.FEATURE, EntityType.RULE, EntityType.BUSINESS_OBJECT),
                    EnumSet.of(EntityType.REQUIREMENT, EntityType.FEATURE, EntityType.RULE, EntityType.BUSINESS_OBJECT))),
            Map.entry(RelationType.AFFECTS_MODULE, new Rule(
                    EnumSet.of(EntityType.REQUIREMENT, EntityType.FEATURE, EntityType.RULE, EntityType.PROCESS),
                    EnumSet.of(EntityType.MODULE))),
            Map.entry(RelationType.PERFORMED_BY, new Rule(
                    EnumSet.of(EntityType.REQUIREMENT, EntityType.FEATURE, EntityType.PROCESS, EntityType.RULE),
                    EnumSet.of(EntityType.ACTOR, EntityType.MODULE, EntityType.EXTERNAL_SYSTEM))),
            Map.entry(RelationType.OPERATES_ON, new Rule(PROCESS_ACTOR, DATA_TARGET)),
            Map.entry(RelationType.CHANGES_STATE, new Rule(
                    EnumSet.of(EntityType.REQUIREMENT, EntityType.FEATURE, EntityType.PROCESS, EntityType.ACTOR, EntityType.RULE),
                    EnumSet.of(EntityType.STATE, EntityType.EVENT))),
            Map.entry(RelationType.TRIGGERS_EVENT, new Rule(
                    EnumSet.of(EntityType.REQUIREMENT, EntityType.FEATURE, EntityType.PROCESS, EntityType.ACTOR, EntityType.RULE),
                    EnumSet.of(EntityType.EVENT, EntityType.STATE))),
            Map.entry(RelationType.PRECEDES, new Rule(
                    EnumSet.of(EntityType.REQUIREMENT, EntityType.FEATURE, EntityType.PROCESS, EntityType.RULE, EntityType.EVENT),
                    EnumSet.of(EntityType.REQUIREMENT, EntityType.FEATURE, EntityType.PROCESS, EntityType.RULE, EntityType.EVENT))),
            Map.entry(RelationType.REQUIRES_RULE, new Rule(
                    EnumSet.of(EntityType.REQUIREMENT, EntityType.FEATURE, EntityType.PROCESS, EntityType.ACTOR, EntityType.BUSINESS_OBJECT),
                    EnumSet.of(EntityType.RULE, EntityType.VALUE_OR_PARAMETER, EntityType.CONFIGURATION))),
            Map.entry(RelationType.EXPOSES_INTERFACE, new Rule(
                    EnumSet.of(EntityType.REQUIREMENT, EntityType.FEATURE, EntityType.MODULE, EntityType.INTERFACE),
                    EnumSet.of(EntityType.INTERFACE, EntityType.EXTERNAL_SYSTEM))),
            Map.entry(RelationType.HAS_EXCEPTION, new Rule(
                    EnumSet.of(EntityType.REQUIREMENT, EntityType.FEATURE, EntityType.PROCESS, EntityType.RULE),
                    EnumSet.of(EntityType.EXCEPTION, EntityType.RULE, EntityType.FEATURE))),
            Map.entry(RelationType.HAS_ACCEPTANCE_CRITERION, new Rule(
                    EnumSet.of(EntityType.REQUIREMENT, EntityType.FEATURE),
                    EnumSet.of(EntityType.ACCEPTANCE_CRITERION, EntityType.RULE))),
            Map.entry(RelationType.INTRODUCED_IN_VERSION, new Rule(
                    EnumSet.of(EntityType.REQUIREMENT, EntityType.FEATURE, EntityType.RULE, EntityType.BUSINESS_OBJECT),
                    EnumSet.of(EntityType.VERSION))),
            Map.entry(RelationType.CONTAINS, new Rule(
                    EnumSet.of(EntityType.REQUIREMENT, EntityType.MODULE, EntityType.FEATURE, EntityType.PROCESS, EntityType.DATA_ENTITY),
                    EnumSet.of(EntityType.REQUIREMENT, EntityType.FEATURE, EntityType.MODULE, EntityType.RULE,
                            EntityType.BUSINESS_OBJECT, EntityType.DATA_ENTITY, EntityType.ACCEPTANCE_CRITERION))),
            Map.entry(RelationType.TRANSITIONS_TO, new Rule(
                    EnumSet.of(EntityType.STATE, EntityType.EVENT),
                    EnumSet.of(EntityType.STATE, EntityType.EVENT))),
            Map.entry(RelationType.REQUIRES, new Rule(
                    EnumSet.of(EntityType.REQUIREMENT, EntityType.FEATURE, EntityType.PROCESS, EntityType.ACTOR, EntityType.BUSINESS_OBJECT),
                    EnumSet.of(EntityType.RULE, EntityType.VALUE_OR_PARAMETER, EntityType.CONFIGURATION, EntityType.EXTERNAL_SYSTEM))),
            Map.entry(RelationType.VERIFIED_BY, new Rule(
                    EnumSet.of(EntityType.REQUIREMENT, EntityType.FEATURE, EntityType.RULE),
                    EnumSet.of(EntityType.ACCEPTANCE_CRITERION, EntityType.RULE))),
            Map.entry(RelationType.EXCEPTION_TO, new Rule(
                    EnumSet.of(EntityType.EXCEPTION),
                    EnumSet.of(EntityType.REQUIREMENT, EntityType.FEATURE, EntityType.RULE, EntityType.PROCESS))),
            Map.entry(RelationType.USES, new Rule(
                    EnumSet.of(EntityType.REQUIREMENT, EntityType.FEATURE, EntityType.PROCESS, EntityType.ACTOR,
                            EntityType.MODULE, EntityType.INTERFACE),
                    DATA_TARGET)));

    public static void validate(RelationType relation, EntityType source, EntityType target) {
        if (relation == null || source == null || target == null) {
            throw new RequirementGraphException("GRAPH_SCHEMA_INVALID", "需求语义图关系本体字段不完整");
        }
        Rule rule = RULES.get(relation);
        if (rule == null) {
            throw new RequirementGraphException("GRAPH_SCHEMA_INVALID",
                    "需求语义图关系类型缺少本体约束: " + relation);
        }
        if (!rule.sources().contains(source)) {
            throw new RequirementGraphException("GRAPH_SCHEMA_INVALID",
                    "需求语义图关系源类型不符合本体约束: " + relation + " <- " + source);
        }
        if (!rule.targets().contains(target)) {
            throw new RequirementGraphException("GRAPH_SCHEMA_INVALID",
                    "需求语义图关系目标类型不符合本体约束: " + relation + " -> " + target);
        }
    }

    private record Rule(Set<EntityType> sources, Set<EntityType> targets) {
    }
}