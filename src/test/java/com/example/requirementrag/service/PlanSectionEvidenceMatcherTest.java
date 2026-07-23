package com.example.requirementrag.service;

import com.example.requirementrag.model.CodeChunk;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlanSectionEvidenceMatcherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PlanSectionEvidenceMatcher matcher = new PlanSectionEvidenceMatcher(objectMapper);

    @Test
    void prefersConfigurationCodeForConfigurationSection() {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("title", "配置表设计")
                .put("purpose", "定义成长基金档位配置并按等级查询");
        payload.putArray("relatedRules").add("档位按等级展示");
        List<CodeChunk> code = List.of(
                chunk("config", "ConfigHeroGrowUp", "config/ConfigHeroGrowUp.java", "load config getByLevel reward"),
                chunk("claim", "claimReward", "service/RewardService.java", "claim reward and persist state"));

        ObjectNode enriched = matcher.enrich(payload, code);

        assertThat(enriched.path("inspectTargets").isArray()).isTrue();
        assertThat(enriched.path("inspectTargets").get(0).path("id").asText()).isEqualTo("config");
        assertThat(enriched.path("inspectTargets").get(0).path("relation").asText()).isNotBlank();
        assertThat(enriched.path("inspectTargets").get(0).path("matchType").asText()).isEqualTo("exact");
    }

    @Test
    void prefersClaimCodeForClaimSection() {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("title", "奖励领取")
                .put("purpose", "校验领取资格、发奖并保存领取状态");
        List<CodeChunk> code = List.of(
                chunk("config", "ConfigHeroGrowUp", "config/ConfigHeroGrowUp.java", "static config"),
                chunk("claim", "claimReward", "service/RewardService.java", "check claim reward save received state"));

        ObjectNode enriched = matcher.enrich(payload, code);

        assertThat(enriched.path("inspectTargets").get(0).path("id").asText()).isEqualTo("claim");
    }

    @Test
    void recommendsExistingResultsWhenSectionHasNoStrongMatch() {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("title", "灰度观察")
                .put("purpose", "确认上线后的业务表现");
        List<CodeChunk> code = List.of(
                chunk("one", "AlphaCoordinator", "service/AlphaCoordinator.java", "coordinate alpha"),
                chunk("two", "BetaRepository", "dao/BetaRepository.java", "persist beta"),
                chunk("three", "GammaView", "view/GammaView.java", "render gamma"));

        ObjectNode enriched = matcher.enrich(payload, code);

        assertThat(enriched.path("inspectTargets").size()).isEqualTo(2);
        assertThat(enriched.path("inspectTargets").get(0).path("matchType").asText()).isEqualTo("recommended");
        Set<String> inputIds = Set.of("one", "two", "three");
        assertThat(enriched.path("inspectTargets").valueStream()
                .map(item -> item.path("id").asText()).toList()).allMatch(inputIds::contains);
    }

    @Test
    void returnsEmptyTargetsOnlyWhenCodeResultsAreEmpty() {
        ObjectNode payload = objectMapper.createObjectNode().put("title", "配置表设计");

        ObjectNode enriched = matcher.enrich(payload, List.of());

        assertThat(enriched.path("inspectTargets").isArray()).isTrue();
        assertThat(enriched.path("inspectTargets").isEmpty()).isTrue();
    }

    private CodeChunk chunk(String id, String symbolName, String filePath, String text) {
        return new CodeChunk(id, "game", "sha", filePath, "method", symbolName,
                10, 30, text, "hash-" + id);
    }
}
