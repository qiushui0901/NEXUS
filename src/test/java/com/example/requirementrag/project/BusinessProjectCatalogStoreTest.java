package com.example.requirementrag.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessProjectCatalogStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsOwnedRepositoriesSharedReferencesAliasesAndIdempotentMigration() {
        BusinessProjectCatalogStore store = store();
        String now = Instant.now().toString();
        BusinessProject project = new BusinessProject(
                "immortal", "Immortal", "immortal-game-service",
                "fengshen_requirements", "fengshen", "immortal-game-service",
                "immortal-game-service", "5.1", BusinessProject.Status.ACTIVE, now, now);
        CodeRepository anchor = repository(
                "immortal-game-service", CodeRepository.Kind.PROJECT, "immortal",
                "immortal_game_service_code");
        CodeRepository api = repository(
                "bizgame-immortal-api", CodeRepository.Kind.PROJECT, "immortal",
                "bizgame_immortal_api_code");

        store.applyMigration("immortal-v1", project, List.of(anchor, api),
                List.of("immortal-game-service"));
        store.applyMigration("immortal-v1", project, List.of(anchor, api),
                List.of("immortal-game-service"));

        assertThat(store.projects()).containsExactly(project);
        assertThat(store.ownedRepositories("immortal"))
                .extracting(CodeRepository::id)
                .containsExactly("immortal-game-service", "bizgame-immortal-api");
        assertThat(store.resolveAlias("immortal-game-service")).contains("immortal");

        CodeRepository shared = repository("platform-common", CodeRepository.Kind.SHARED, null,
                "platform_common_code");
        store.createRepository(shared);
        store.addSharedReference("immortal", shared.id());
        store.addSharedReference("immortal", shared.id());

        assertThat(store.referencedSharedRepositories("immortal"))
                .extracting(CodeRepository::id)
                .containsExactly("platform-common");
        store.removeSharedReference("immortal", shared.id());
        assertThat(store.referencedSharedRepositories("immortal")).isEmpty();
    }

    @Test
    void migrationConflictRollsBackInsteadOfMarkingCompleted() {
        BusinessProjectCatalogStore store = store();
        String now = Instant.now().toString();
        BusinessProject occupied = new BusinessProject(
                "immortal", "Wrong", "other-repo", "other_req", "other-doc",
                "other", "other", "1.0", BusinessProject.Status.ACTIVE, now, now);
        store.createProject(occupied);
        BusinessProject expected = new BusinessProject(
                "immortal", "Immortal", "immortal-game-service", "requirements", "fengshen",
                "immortal-game-service", "immortal-game-service", "5.1",
                BusinessProject.Status.ACTIVE, now, now);

        assertThatThrownBy(() -> store.applyMigration("conflict", expected,
                List.of(repository("immortal-game-service", CodeRepository.Kind.PROJECT,
                        "immortal", "code_chunks")), List.of("immortal-game-service")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("业务项目迁移冲突");
        assertThat(store.resolveAlias("immortal-game-service")).isEmpty();
        assertThat(store.repositories()).isEmpty();
    }

    private BusinessProjectCatalogStore store() {
        return new BusinessProjectCatalogStore(
                new BusinessProjectCatalogProperties(tempDir.resolve("catalog.db").toString()));
    }

    private CodeRepository repository(String id, CodeRepository.Kind kind, String projectId,
                                      String collection) {
        String now = Instant.now().toString();
        return new CodeRepository(id, id, kind, projectId, "server", collection,
                tempDir.resolve(id).toString(), "group/" + id, "MAVEN_POM", "pom.xml",
                kind == CodeRepository.Kind.SHARED, true, now, now);
    }
}
