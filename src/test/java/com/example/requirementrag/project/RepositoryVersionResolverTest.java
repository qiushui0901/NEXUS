package com.example.requirementrag.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryVersionResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void readsFullMavenProjectVersionWithoutUsingParentVersion() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>example</groupId><artifactId>parent</artifactId><version>1.0.0</version></parent>
                  <groupId>example</groupId><artifactId>immortal</artifactId><version>5.2.0</version>
                </project>
                """);
        CodeRepository repository = repository();

        RepositoryVersionResolver.ResolvedVersion result =
                new RepositoryVersionResolver().resolve(repository);

        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.rawVersion()).isEqualTo("5.2.0");
        assertThat(result.displayVersion()).isEqualTo("v5.2.0");
        assertThat(result.sourcePath()).isEqualTo("pom.xml");
    }

    @Test
    void reportsMissingVersionSourceWithoutInventingAVersion() {
        RepositoryVersionResolver.ResolvedVersion result =
                new RepositoryVersionResolver().resolve(repository());

        assertThat(result.status()).isEqualTo("UNAVAILABLE");
        assertThat(result.displayVersion()).isNull();
        assertThat(result.warningCode()).isEqualTo("VERSION_SOURCE_NOT_FOUND");
    }

    private CodeRepository repository() {
        String now = Instant.now().toString();
        return new CodeRepository("immortal-game-service", "Immortal Main",
                CodeRepository.Kind.PROJECT, "immortal", "server", "immortal_code",
                tempDir.toString(), "bizgame/immortal-game-service", "MAVEN_POM", "pom.xml",
                false, true, now, now);
    }
}
