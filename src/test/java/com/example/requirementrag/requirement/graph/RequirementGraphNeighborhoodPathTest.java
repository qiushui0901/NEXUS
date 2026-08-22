package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.requirement.graph.RequirementGraphModels.Entity;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.EntityStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphSnapshot;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Relation;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.RelationStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.RelationType;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SnapshotStatus;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RequirementGraphNeighborhoodPathTest {
    @TempDir Path tempDir;

    @Test
    void traversesBoundedNeighborhoodAndFindsPath() {
        RequirementGraphProperties properties = new RequirementGraphProperties(true, true, true,
                tempDir.resolve("graph.db").toString(), 20, 30, 20_000, 2, 40, "model", "v1");
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(new ObjectMapper(), properties);
        Instant now = Instant.now();
        GraphSnapshot snapshot = new GraphSnapshot("reqgraph:path", "orders", "requirements", "2.0",
                "source", "model", "v1", SnapshotStatus.DRAFT, 3, 2, now, now, null);
        Entity first = entity("entity:first", snapshot.id(), "订单");
        Entity middle = entity("entity:middle", snapshot.id(), "库存");
        Entity last = entity("entity:last", snapshot.id(), "回滚");
        Relation one = relation("relation:one", snapshot.id(), first.id(), middle.id());
        Relation two = relation("relation:two", snapshot.id(), middle.id(), last.id());
        store.saveSnapshot(snapshot);
        store.replaceDraft(snapshot, List.of(first, middle, last), List.of(one, two));
        store.updateStatus(snapshot.id(), SnapshotStatus.PUBLISHED, null);
        RequirementGraphSearchService search = new RequirementGraphSearchService(store,
                mock(QdrantHybridStore.class), mock(com.example.requirementrag.config.ProjectRegistry.class), properties);

        var neighborhood = search.neighborhood(snapshot.id(), first.id(), 2, 10, true);
        var paths = search.paths(snapshot.id(), first.id(), last.id(), 3, 10, true);

        assertThat(neighborhood.entities()).extracting(Entity::id).contains(first.id(), middle.id(), last.id());
        assertThat(neighborhood.relations()).extracting(Relation::id).containsExactly(one.id(), two.id());
        assertThat(paths.paths()).singleElement().satisfies(path -> assertThat(path.hops()).isEqualTo(2));
    }

    private Entity entity(String id, String snapshot, String name) {
        return new Entity(id, snapshot, RequirementGraphModels.EntityType.MODULE, name, name,
                List.of(), "", List.of(), List.of(), List.of(), .9, EntityStatus.EXTRACTED,
                RequirementGraphModels.ClaimStatus.VERIFIED, null, "orders", null, null,
                List.of(), List.of(), "reviewer", Instant.now(), "verified");
    }

    private Relation relation(String id, String snapshot, String source, String target) {
        return new Relation(id, snapshot, source, RelationType.DEPENDS_ON, target, source + "依赖" + target,
                List.of(), .8, RelationStatus.VERIFIED, "reviewer", Instant.now(),
                RequirementGraphModels.ClaimStatus.VERIFIED, "", "", List.of(source + "依赖" + target),
                List.of(), List.of(), "verified");
    }
}
