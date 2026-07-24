package com.example.requirementrag.wiki;

import com.example.requirementrag.config.WikiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class WikiSeedArtifactConsistencyTest {
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
            sourceFiles = paths.filter(path -> path.getFileName().toString().startsWith("immortal-game-service-v"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
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
            var source = mapper.readTree(Files.readString(sourceFile, StandardCharsets.UTF_8));
            String projectId = source.path("projectId").asText();
            String version = source.path("version").asText();
            assertThat(projectId).as(sourceFile.toString()).isNotBlank();
            assertThat(version).as(sourceFile.toString()).isNotBlank();

            generator.generate(projectId, version);
            assertArtifactsMatch(mapper, projectId, version, generatedRoot);
        }

        assertThat(repository.listVersions("immortal-game-service"))
                .extracting(WikiModels.VersionIndex::version)
                .containsSubsequence("5.1", "5.0.2", "5.0.1", "5.0.0", "4.1.6", "0.1");
    }

    private void assertArtifactsMatch(ObjectMapper mapper, String projectId, String version, Path generatedRoot) throws Exception {
        Path expectedRoot = Path.of("data/wiki").resolve(projectId).resolve(version);
        Path actualRoot = generatedRoot.resolve(projectId).resolve(version);
        Set<String> expectedFiles = relativeFiles(expectedRoot);
        Set<String> actualFiles = relativeFiles(actualRoot);
        assertThat(actualFiles).containsExactlyInAnyOrderElementsOf(expectedFiles);

        for (String relative : expectedFiles) {
            Path expected = expectedRoot.resolve(relative);
            Path actual = actualRoot.resolve(relative);
            if (relative.endsWith(".json")) {
                assertThat(mapper.readTree(Files.readString(actual, StandardCharsets.UTF_8)))
                        .as(projectId + "/" + version + "/" + relative)
                        .isEqualTo(mapper.readTree(Files.readString(expected, StandardCharsets.UTF_8)));
            } else {
                assertThat(actual).as(projectId + "/" + version + "/" + relative)
                        .hasSameTextualContentAs(expected, StandardCharsets.UTF_8);
            }
        }
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
