package com.example.requirementrag.wiki;

import com.example.requirementrag.config.WikiProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class WikiSeedArtifactConsistencyTest {

    private static final Set<String> SCHEMA_2_PAGE_FIELDS = Set.of(
            "requirementSources",
            "processSteps",
            "codeEntries",
            "dataImpacts",
            "boundaryConditions",
            "acceptanceCriteria",
            "testKnowledge",
            "versionChange",
            "quality"
    );

    @TempDir
    Path temp;

    @Test
    void committedSeedArtifactsMatchTheJavaGenerator() throws Exception {
        Path committedSources = Path.of("data/wiki-sources");
        Path sourceRoot = temp.resolve("sources");
        Path generatedRoot = temp.resolve("wiki");
        Files.createDirectories(sourceRoot);
        List<Path> sourceFiles;
        try (var paths = Files.list(committedSources)) {
            sourceFiles = paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
        assertThat(sourceFiles).isNotEmpty();
        for (Path sourceFile : sourceFiles) {
            Files.copy(sourceFile, sourceRoot.resolve(sourceFile.getFileName()));
        }

        ObjectMapper mapper = new ObjectMapper();
        WikiProperties properties = new WikiProperties(generatedRoot.toString(), sourceRoot.toString());
        WikiRepository repository = new WikiRepository(mapper, properties);
        WikiGenerationService generator = new WikiGenerationService(mapper, properties, repository);

        for (Path sourceFile : sourceFiles) {
            JsonNode source = mapper.readTree(Files.readString(sourceFile, StandardCharsets.UTF_8));
            String projectId = source.path("projectId").asText();
            String version = source.path("version").asText();
            int schemaVersion = source.path("schemaVersion").asInt(1);
            assertThat(projectId).as(sourceFile.toString()).isNotBlank();
            assertThat(version).as(sourceFile.toString()).isNotBlank();

            generator.generate(projectId, version);
            assertArtifactsMatch(mapper, projectId, version, schemaVersion, generatedRoot);
        }

        assertThat(repository.listProjects()).isNotEmpty();
    }

    private void assertArtifactsMatch(ObjectMapper mapper,
                                      String projectId,
                                      String version,
                                      int schemaVersion,
                                      Path generatedRoot) throws Exception {
        Path expectedRoot = Path.of("data/wiki").resolve(projectId).resolve(version);
        Path actualRoot = generatedRoot.resolve(projectId).resolve(version);
        Set<String> expectedFiles = relativeFiles(expectedRoot);
        Set<String> actualFiles = relativeFiles(actualRoot);
        assertThat(actualFiles).containsExactlyInAnyOrderElementsOf(expectedFiles);

        for (String relative : expectedFiles) {
            Path expected = expectedRoot.resolve(relative);
            Path actual = actualRoot.resolve(relative);
            if (relative.endsWith(".json")) {
                JsonNode actualJson = mapper.readTree(Files.readString(actual, StandardCharsets.UTF_8));
                JsonNode expectedJson = mapper.readTree(Files.readString(expected, StandardCharsets.UTF_8));
                if (schemaVersion < 2 && relative.startsWith("pages/")) {
                    removeSchema2Fields(actualJson);
                }
                assertThat(actualJson)
                        .as(projectId + "/" + version + "/" + relative)
                        .isEqualTo(expectedJson);
            } else if (schemaVersion >= 2) {
                assertThat(actual).as(projectId + "/" + version + "/" + relative)
                        .hasSameTextualContentAs(expected, StandardCharsets.UTF_8);
            } else {
                assertLegacyMarkdownRemainsReadable(actual, projectId, version, relative);
            }
        }
    }

    private void removeSchema2Fields(JsonNode page) {
        if (!(page instanceof ObjectNode objectNode)) {
            return;
        }
        SCHEMA_2_PAGE_FIELDS.forEach(objectNode::remove);
    }

    private void assertLegacyMarkdownRemainsReadable(Path actual,
                                                     String projectId,
                                                     String version,
                                                     String relative) throws Exception {
        String markdown = Files.readString(actual, StandardCharsets.UTF_8);
        assertThat(markdown)
                .as(projectId + "/" + version + "/" + relative)
                .contains("---\n\n# ")
                .contains("\n## ")
                .doesNotContain("null");
    }

    private Set<String> relativeFiles(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(Path::toString)
                    .collect(Collectors.toSet());
        }
    }
}
