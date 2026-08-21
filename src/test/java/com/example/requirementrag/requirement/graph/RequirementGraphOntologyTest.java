package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.requirement.graph.RequirementGraphModels.EntityType;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.RelationType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequirementGraphOntologyTest {

    @Test
    void allowsConfiguredSourceTargetCombo() {
        assertThatCode(() -> RequirementGraphOntology.validate(
                RelationType.AFFECTS_MODULE, EntityType.FEATURE, EntityType.MODULE))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSourceNotAllowedForRelation() {
        assertThatThrownBy(() -> RequirementGraphOntology.validate(
                RelationType.AFFECTS_MODULE, EntityType.EXCEPTION, EntityType.MODULE))
                .isInstanceOf(RequirementGraphException.class)
                .hasMessageContaining("源类型不符合本体约束");
    }

    @Test
    void rejectsTargetNotAllowedForRelation() {
        assertThatThrownBy(() -> RequirementGraphOntology.validate(
                RelationType.HAS_ACCEPTANCE_CRITERION, EntityType.REQUIREMENT, EntityType.STATE))
                .isInstanceOf(RequirementGraphException.class)
                .hasMessageContaining("目标类型不符合本体约束");
    }

    @Test
    void rejectsNullRelation() {
        assertThatThrownBy(() -> RequirementGraphOntology.validate(
                null, EntityType.REQUIREMENT, EntityType.FEATURE))
                .isInstanceOf(RequirementGraphException.class);
    }
}