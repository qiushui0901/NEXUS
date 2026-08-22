package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Entity;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphSnapshot;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Relation;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SnapshotStatus;
import com.example.requirementrag.requirement.graph.SQLiteRequirementGraphStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * REQUIREMENT 来源适配器：把已发布/已审核的需求语义图实体与关系投影为统一 Claim。
 * 这是规范查询能返回“需求事实”的关键接入点。
 */
@Component
public class RequirementGraphCandidateAdapter implements MultiSourceCandidateAdapter {
    private final SQLiteRequirementGraphStore graphStore;

    public RequirementGraphCandidateAdapter(SQLiteRequirementGraphStore graphStore) {
        this.graphStore = graphStore;
    }

    @Override
    public SourceType sourceType() {
        return SourceType.REQUIREMENT;
    }

    @Override
    public List<UnifiedKnowledgeClaim> load(String projectId, String version) {
        GraphSnapshot snapshot = graphStore.listSnapshots(projectId, null, version).stream()
                .filter(item -> item.status() == SnapshotStatus.PUBLISHED
                        || item.status() == SnapshotStatus.VERIFIED)
                .findFirst().orElse(null);
        if (snapshot == null) return List.of();
        Set<UnifiedKnowledgeClaim> claims = new LinkedHashSet<>();
        for (Entity entity : graphStore.allEntities(snapshot.id(), 10_000)) {
            claims.add(new UnifiedKnowledgeClaim(
                    entity.id(), projectId, snapshot.requirementVersion(),
                    factKey(projectId, version, entity.displayName(), "entity"),
                    entity.displayName(), "entity", safe(entity.description()),
                    entity.type() == null ? null : entity.type().name(), null,
                    SourceType.REQUIREMENT, Authority.PRIMARY, KnowledgeStatus.VERIFIED,
                    version, null, "graph:" + snapshot.id() + "#" + entity.id(), entity.displayName()));
        }
        for (Relation relation : graphStore.allRelations(snapshot.id(), 10_000)) {
            claims.add(new UnifiedKnowledgeClaim(
                    relation.id(), projectId, snapshot.requirementVersion(),
                    factKey(projectId, version, relation.statement(), relation.type().name()),
                    relation.type() == null ? relation.id() : relation.type().name(),
                    relation.statement(), safe(relation.statement()), "TEXT", null,
                    SourceType.REQUIREMENT, Authority.PRIMARY, KnowledgeStatus.VERIFIED,
                    version, null, "graph:" + snapshot.id() + "#" + relation.id(),
                    relation.sourceEntityId() + "->" + relation.targetEntityId()));
        }
        return new ArrayList<>(claims);
    }

    private String factKey(String projectId, String version, String left, String right) {
        return (projectId + "|" + version + "|" + safe(left) + "|" + safe(right)).toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}