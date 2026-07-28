package com.example.requirementrag.code;

import com.example.requirementrag.code.CodeRelation.Resolution;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.CodeIntelligenceResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded static graph traversal and conservative impact analysis. */
@Service
public class CodeIntelligenceService {
    private static final int MAX_DEPTH = 5;
    private static final int MAX_LIMIT = 200;
    private final SQLiteSymbolGraphStore store;
    private final RagProperties properties;
    private final GitDiffService gitDiffService;

    public CodeIntelligenceService(SQLiteSymbolGraphStore store, RagProperties properties,
                                   GitDiffService gitDiffService) {
        this.store = store;
        this.properties = properties;
        this.gitDiffService = gitDiffService;
    }

    public CodeIntelligenceResponse graph(String projectId, String symbol, String direction,
                                          Integer depth, Integer limit) {
        String project = resolveProject(projectId);
        String commit = store.latestCommit(project);
        if (commit == null) return unavailable(project, List.of(), "No code graph snapshot; run code index first");
        int boundedLimit = boundLimit(limit);
        List<CodeSymbol> roots = store.findSymbols(project, commit, symbol, boundedLimit);
        if (roots.isEmpty()) return unavailable(project, List.of(), "Symbol not found in latest graph snapshot");
        boolean inbound = !"outbound".equalsIgnoreCase(direction);
        Traversal traversal = traverse(project, commit, roots, inbound, boundDepth(depth), boundedLimit);
        return response(project, commit, roots, traversal, List.of(), List.of());
    }

    public CodeIntelligenceResponse impactSymbol(String projectId, String symbol, Integer depth, Integer limit) {
        return graph(projectId, symbol, "inbound", depth, limit);
    }

    public CodeIntelligenceResponse impactCommits(String projectId, String fromCommit, String toCommit,
                                                  Integer depth, Integer limit) {
        String project = resolveProject(projectId);
        if (fromCommit == null || fromCommit.isBlank() || toCommit == null || toCommit.isBlank()) {
            throw new IllegalArgumentException("fromCommit and toCommit are required");
        }
        GitDiffService.GitDiffResult diff;
        try {
            diff = gitDiffService.diff(project, fromCommit, toCommit);
        }
        catch (java.io.IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Unable to calculate commit impact", exception);
        }
        String snapshot = store.latestCommit(project);
        List<String> changed = diff.changedPaths();
        if (snapshot == null || !snapshot.equals(toCommit)) {
            return unavailable(project, changed, "Target commit graph is unavailable; returning file-level changes");
        }
        int boundedLimit = boundLimit(limit);
        List<CodeSymbol> roots = store.symbolsByFiles(project, toCommit, changed, boundedLimit);
        Traversal traversal = traverse(project, toCommit, roots, true, boundDepth(depth), boundedLimit);
        return response(project, toCommit, roots, traversal, changed, List.of());
    }

    private Traversal traverse(String project, String commit, List<CodeSymbol> roots, boolean inbound,
                               int maxDepth, int limit) {
        Map<String, CodeSymbol> certain = new LinkedHashMap<>();
        Map<String, CodeSymbol> inferred = new LinkedHashMap<>();
        List<CodeRelation> relations = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<NodeDepth> queue = new ArrayDeque<>();
        roots.forEach(root -> {
            seen.add(root.id());
            queue.add(new NodeDepth(root, 0));
        });
        boolean truncated = false;
        while (!queue.isEmpty()) {
            NodeDepth current = queue.removeFirst();
            if (current.depth() >= maxDepth) continue;
            for (CodeRelation relation : store.relations(project, commit, current.symbol().id(), inbound, limit)) {
                if (relations.size() >= limit) {
                    truncated = true;
                    break;
                }
                relations.add(relation);
                String nextId = inbound ? relation.callerSymbolId() : relation.calleeSymbolId();
                if (nextId == null) continue;
                CodeSymbol next = store.symbolById(project, commit, nextId);
                if (next == null) continue;
                if (relation.resolution() == Resolution.EXACT || relation.resolution() == Resolution.SAME_FILE) {
                    certain.putIfAbsent(next.id(), next);
                }
                else if (relation.resolution() == Resolution.HEURISTIC) {
                    inferred.putIfAbsent(next.id(), next);
                }
                if (seen.add(next.id())) queue.addLast(new NodeDepth(next, current.depth() + 1));
            }
            if (truncated) break;
        }
        return new Traversal(List.copyOf(certain.values()), List.copyOf(inferred.values()), relations,
                store.unresolved(project, commit, Math.min(limit, 50)), truncated);
    }

    private CodeIntelligenceResponse response(String project, String commit, List<CodeSymbol> roots,
                                              Traversal traversal, List<String> changed, List<String> warnings) {
        List<String> suggestions = traversal.certain().stream()
                .filter(symbol -> symbol.entryPoint() || symbol.testSymbol())
                .map(symbol -> (symbol.testSymbol() ? "Retest " : "Regression-check ")
                        + symbol.qualifiedName() + " at " + symbol.filePath() + ":" + symbol.startLine())
                .limit(20).toList();
        return new CodeIntelligenceResponse("AVAILABLE", project, commit, roots, traversal.certain(),
                traversal.inferred(), traversal.relations(), traversal.unresolved(), changed, suggestions,
                warnings, traversal.truncated());
    }

    private CodeIntelligenceResponse unavailable(String project, List<String> changed, String warning) {
        return new CodeIntelligenceResponse("NOT_AVAILABLE", project, null, List.of(), List.of(), List.of(),
                List.of(), List.of(), changed, List.of(), List.of(warning), false);
    }

    private String resolveProject(String projectId) {
        return projectId == null || projectId.isBlank() ? properties.code().projectId() : projectId;
    }

    private int boundDepth(Integer depth) {
        return Math.min(Math.max(depth == null ? 2 : depth, 1), MAX_DEPTH);
    }

    private int boundLimit(Integer limit) {
        return Math.min(Math.max(limit == null ? 50 : limit, 1), MAX_LIMIT);
    }

    private record NodeDepth(CodeSymbol symbol, int depth) {
    }

    private record Traversal(List<CodeSymbol> certain, List<CodeSymbol> inferred,
                             List<CodeRelation> relations, List<CodeRelation> unresolved, boolean truncated) {
    }
}
