package com.example.requirementrag.versioning;

import com.example.requirementrag.versioning.RequirementSnapshotModels.Snapshot;
import com.example.requirementrag.versioning.VersionModels.VersionManifest;
import com.example.requirementrag.wiki.WikiModels.VersionIndex;
import com.example.requirementrag.wiki.WikiRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.requirementrag.versioning.VersionModels.ManifestStatus.RELEASED;

/** Resolves an effective version archive from formal manifests, Wiki indexes and requirement snapshots. */
@Service
public class VersionManifestResolver {
    private final VersionManifestService manifests;
    private final WikiRepository wikiRepository;
    private final RequirementSnapshotRepository requirementSnapshots;

    public VersionManifestResolver(VersionManifestService manifests, WikiRepository wikiRepository,
                                   RequirementSnapshotRepository requirementSnapshots) {
        this.manifests = manifests;
        this.wikiRepository = wikiRepository;
        this.requirementSnapshots = requirementSnapshots;
    }

    public VersionManifest get(String projectId, String version) {
        String project = VersionPathPolicy.identifier(projectId, "projectId");
        String target = VersionPathPolicy.identifier(version, "version");
        return list(project).stream()
                .filter(manifest -> target.equals(manifest.version()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "版本档案不存在"));
    }

    public List<VersionManifest> list(String projectId) {
        String project = VersionPathPolicy.identifier(projectId, "projectId");
        List<VersionIndex> indexes = wikiRepository.listVersions(project);
        Map<String, Snapshot> snapshotsByBusinessVersion = snapshotsByBusinessVersion(project);
        Map<String, String> versionsByCommit = new LinkedHashMap<>();
        for (VersionIndex index : indexes) {
            if (hasText(index.codeCommit())) versionsByCommit.putIfAbsent(index.codeCommit(), index.version());
        }

        Map<String, VersionManifest> merged = new LinkedHashMap<>();
        for (VersionIndex index : indexes) {
            String baseVersion = hasText(index.baseCodeCommit()) ? versionsByCommit.get(index.baseCodeCommit()) : null;
            merged.put(index.version(), synthesize(project, index, baseVersion,
                    snapshotsByBusinessVersion.get(index.version())));
        }
        for (VersionManifest manifest : manifests.list(project)) {
            merged.put(manifest.version(), enrichRequirementReference(manifest,
                    snapshotsByBusinessVersion.get(manifest.version())));
        }
        List<VersionManifest> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparing(VersionManifest::version, VersionManifestService.versionComparator()).reversed());
        return List.copyOf(result);
    }

    private VersionManifest synthesize(String project, VersionIndex index, String baseVersion, Snapshot snapshot) {
        String generatedAt = hasText(index.generatedAt()) ? index.generatedAt() : Instant.now().toString();
        List<String> notes = new ArrayList<>();
        notes.add("由 Wiki 版本索引生成的只读版本档案");
        if (snapshot != null && !index.version().equals(snapshot.requirementVersion())) {
            notes.add("业务版本映射到需求基线 " + snapshot.requirementVersion());
        }
        return new VersionManifest(1, project, index.version(), baseVersion,
                snapshot == null ? null : snapshot.documentId(),
                snapshot == null ? null : snapshot.requirementVersion(),
                index.baseCodeCommit(), index.codeCommit(), null, index.version(),
                "wiki-" + index.version(), RELEASED, generatedAt, generatedAt, notes);
    }

    private VersionManifest enrichRequirementReference(VersionManifest manifest, Snapshot snapshot) {
        if (hasText(manifest.requirementDocumentId()) && hasText(manifest.requirementVersion())) return manifest;
        if (snapshot == null) return manifest;
        List<String> notes = new ArrayList<>(manifest.notes());
        notes.add("已从需求快照补齐需求版本引用 " + snapshot.requirementVersion());
        return new VersionManifest(manifest.schemaVersion(), manifest.projectId(), manifest.version(), manifest.baseVersion(),
                hasText(manifest.requirementDocumentId()) ? manifest.requirementDocumentId() : snapshot.documentId(),
                hasText(manifest.requirementVersion()) ? manifest.requirementVersion() : snapshot.requirementVersion(),
                manifest.baseCodeCommit(), manifest.codeCommit(), manifest.testSnapshot(), manifest.wikiVersion(),
                manifest.wikiBuildId(), manifest.status(), manifest.createdAt(), manifest.updatedAt(), notes);
    }

    private Map<String, Snapshot> snapshotsByBusinessVersion(String project) {
        Map<String, Snapshot> result = new LinkedHashMap<>();
        for (Snapshot snapshot : requirementSnapshots.list(project)) {
            result.putIfAbsent(snapshot.requirementVersion(), snapshot);
            for (String alias : snapshot.aliases()) result.putIfAbsent(alias, snapshot);
        }
        return result;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
