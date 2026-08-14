package com.example.requirementrag.code;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodePathFilterTest {

    private static final List<String> DEFAULTS = List.of("/target/", "/.git/", "/node_modules/", "/build/");

    @Test
    void keepsSourcePackagesNamedBuild() {
        assertThat(CodePathFilter.excluded(
                "/immortal-game-service-impl/src/main/java/com/immomo/world/plugin/build/BuildPluginCommon.java",
                DEFAULTS)).isFalse();
        assertThat(CodePathFilter.excluded(
                "/immortal-game-service-impl/src/main/java/com/immomo/world/message/handler/c2s/build/BuildKillRankHandler.java",
                DEFAULTS)).isFalse();
    }

    @Test
    void excludesRootAndModuleLevelBuildOutputDirectories() {
        assertThat(CodePathFilter.excluded("/build/generated/sources/Foo.java", DEFAULTS)).isTrue();
        assertThat(CodePathFilter.excluded("/module-a/build/generated/Foo.java", DEFAULTS)).isTrue();
    }

    @Test
    void excludesRootAndModuleLevelTargetDirectories() {
        assertThat(CodePathFilter.excluded("/target/generated-sources/Foo.java", DEFAULTS)).isTrue();
        assertThat(CodePathFilter.excluded("/module-a/target/generated/Foo.java", DEFAULTS)).isTrue();
    }

    @Test
    void keepsSourcePackagesNamedTarget() {
        assertThat(CodePathFilter.excluded(
                "/src/main/java/com/acme/target/TargetService.java", DEFAULTS)).isFalse();
    }

    @Test
    void excludesSourceTreeContentPatternsBySubstring() {
        List<String> excludes = List.of("/src/main/resources/");
        assertThat(CodePathFilter.excluded("/module-a/src/main/resources/config.properties", excludes)).isTrue();
        assertThat(CodePathFilter.excluded("/src/main/java/com/acme/Foo.java", excludes)).isFalse();
    }

    @Test
    void includesBySubstringAndIncludesEverythingWhenUnconfigured() {
        List<String> includes = List.of("/shiguang-auth/");
        assertThat(CodePathFilter.included("/shiguang-auth/src/main/java/Foo.java", includes)).isTrue();
        assertThat(CodePathFilter.included("/other/src/main/java/Foo.java", includes)).isFalse();
        assertThat(CodePathFilter.included("/anything/Foo.java", List.of())).isTrue();
        assertThat(CodePathFilter.included("/anything/Foo.java", null)).isTrue();
    }

    @Test
    void filePatternsMatchAnywhereInThePath() {
        List<String> excludes = List.of("/简历.md");
        assertThat(CodePathFilter.excluded("/docs/简历.md", excludes)).isTrue();
        assertThat(CodePathFilter.excluded("/docs/产品.md", excludes)).isFalse();
    }

    @Test
    void filePatternsStillApplyInsideSourceTrees() {
        List<String> excludes = List.of("/简历.md", "/Generated.java");
        assertThat(CodePathFilter.excluded("/module-a/src/main/resources/简历.md", excludes)).isTrue();
        assertThat(CodePathFilter.excluded("/src/main/java/com/acme/Generated.java", excludes)).isTrue();
        assertThat(CodePathFilter.excluded("/src/main/java/com/acme/HandWritten.java", excludes)).isFalse();
    }
}
