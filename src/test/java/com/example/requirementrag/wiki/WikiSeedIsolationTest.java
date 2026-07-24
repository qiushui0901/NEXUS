package com.example.requirementrag.wiki;

import com.example.requirementrag.wiki.WikiModels.VersionSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WikiSeedIsolationTest {
    @Test
    void keepsGrowFundAndGrowDiscountAsSeparateVersionedFeatures() throws Exception {
        VersionSource source = new ObjectMapper().readValue(
                Files.readAllBytes(Path.of("data/wiki-sources/immortal-game-service-v5.1.json")),
                VersionSource.class);

        var growFund = source.pages().stream().filter(page -> page.featureId().equals("grow-fund")).findFirst().orElseThrow();
        var growDiscount = source.pages().stream().filter(page -> page.featureId().equals("grow-discount")).findFirst().orElseThrow();

        assertThat(growFund.introducedVersion()).isEqualTo("5.1");
        assertThat(growFund.codeSymbols()).allMatch(symbol -> symbol.contains("GrowFund"));
        assertThat(growFund.codeSymbols()).noneMatch(symbol -> symbol.contains("GrowDiscount"));
        assertThat(growDiscount.introducedVersion()).isEqualTo("5.0");
        assertThat(growDiscount.codeSymbols()).allMatch(symbol -> symbol.contains("GrowDiscount"));
        assertThat(growDiscount.codeSymbols()).noneMatch(symbol -> symbol.contains("GrowFundService"));
    }
}
