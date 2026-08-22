package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ClaimStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Entity;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphSnapshot;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Relation;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SnapshotStatus;
import com.example.requirementrag.requirement.graph.SQLiteRequirementGraphStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * REQUIREMENT 来源适配器：把已发布/已审核需求语义图中 VERIFIED 且可回查 Evidence 的实体与关系投影为统一 Claim。
 * 保留实体/关系自身的 claimStatus 与 sourceEvidenceIds，避免把 REJECTED/CONFLICTED/STALE 当成确认事实。
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
    public List<UnifiedKnowledgeClaim> load(String projectId, String version, String query) {
        GraphSnapshot snapshot = graphStore.listSnapshots(projectId, null, version).stream()
                .filter(item -> item.status() == SnapshotStatus.PUBLISHED
                        || item.status() == SnapshotStatus.VERIFIED)
                .findFirst().orElse(null);
        if (snapshot == null) return List.of();
        Set<UnifiedKnowledgeClaim> claims = new LinkedHashSet<>();
        Map<String, Entity> entitiesById = new LinkedHashMap<>();
        for (Entity entity : graphStore.allEntities(snapshot.id(), 10_000)) {
            entitiesById.put(entity.id(), entity);
            KnowledgeStatus status = toStatus(entity.claimStatus());
            if (status != KnowledgeStatus.VERIFIED) continue;
            claims.add(new UnifiedKnowledgeClaim(
                    entity.id(), projectId, snapshot.requirementVersion(),
                    factKey(projectId, version, entity.displayName(), "entity"),
                    entity.displayName(), "entity", safe(entity.description()),
                    entity.type() == null ? null : entity.type().name(), null,
                    SourceType.REQUIREMENT, Authority.PRIMARY, status,
                    version, null, evidenceLocation(snapshot.id(), entity.id(), entity.sourceEvidenceIds()),
                    entity.displayName()));
        }
        for (Relation relation : graphStore.allRelations(snapshot.id(), 10_000)) {
            KnowledgeStatus status = toStatus(relation.claimStatus());
            if (status != KnowledgeStatus.VERIFIED) continue;
            Entity source = entitiesById.get(relation.sourceEntityId());
            Entity target = entitiesById.get(relation.targetEntityId());
            String sourceName = source == null ? relation.sourceEntityId() : source.canonicalName();
            String targetName = target == null ? relation.targetEntityId() : target.canonicalName();
            claims.add(new UnifiedKnowledgeClaim(
                    relation.id(), projectId, snapshot.requirementVersion(),
                    factKey(projectId, version, sourceName, relation.type().name() + "|" + targetName),
                    sourceName + "->" + targetName,
                    relation.statement(), safe(relation.statement()), "TEXT", null,
                    SourceType.REQUIREMENT, Authority.PRIMARY, status,
                    version, null, evidenceLocation(snapshot.id(), relation.id(), relation.sourceEvidenceIds()),
                    sourceName + "->" + targetName));
        }
        return new ArrayList<>(claims);
    }

    private String evidenceLocation(String snapshotId, String claimId, List<String> sourceEvidenceIds) {
        if (sourceEvidenceIds != null && !sourceEvidenceIds.isEmpty()) {
            return sourceEvidenceIds.get(0);
        }
        return "graph:" + snapshotId + "#" + claimId;
    }

    private KnowledgeStatus toStatus(ClaimStatus status) {
        if (status == null) return KnowledgeStatus.SUPPORTED;
        return switch (status) {
            case VERIFIED -> KnowledgeStatus.VERIFIED;
            case REJECTED -> KnowledgeStatus.REJECTED;
            case CONFLICTED -> KnowledgeStatus.CONFLICTED;
            case STALE -> KnowledgeStatus.STALE;
            case UNAVAILABLE -> KnowledgeStatus.REJECTED;
            default -> KnowledgeStatus.SUPPORTED;
        };
    }

    private String factKey(String projectId, String version, String left, String right) {
        return (projectId + "|" + version + "|" + safe(left) + "|" + safe(right)).toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}