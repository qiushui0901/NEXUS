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

/**
 * 有界静态图遍历与保守影响分析服务。
 * 基于 SQLite 符号图谱从起始符号做入/出方向遍历，区分精确（EXACT/SAME_FILE）与启发式（HEURISTIC）解析结果；
 * 也可按 commit 差异以变更文件内的符号为起点分析影响面。
 */
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

    /**
     * 从指定符号出发按方向遍历调用图，返回影响分析结果。
     *
     * @param projectId 项目 ID，为空或空白时使用默认项目
     * @param symbol    起始符号名（全限定名或简单名均可）
     * @param direction "inbound" 为入向（谁调用它），其余值按出向（它调用谁）处理
     * @param depth     遍历最大深度，钳制在 1-5
     * @param limit     符号/关系数量上限，钳制在 1-200
     * @return 影响分析响应；图谱快照缺失或符号不存在时返回 NOT_AVAILABLE
     */
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

    /** 分析指定符号的入向影响面（哪些符号依赖它），等价于 direction=inbound 的 {@link #graph}。 */
    public CodeIntelligenceResponse impactSymbol(String projectId, String symbol, Integer depth, Integer limit) {
        return graph(projectId, symbol, "inbound", depth, limit);
    }

    /**
     * 按 commit 差异分析影响面：取 fromCommit..toCommit 变更文件中的符号作为起点做入向遍历。
     * 目标 commit 无图谱快照时降级为仅返回文件级变更列表的 NOT_AVAILABLE 响应。
     *
     * @param projectId  项目 ID，为空或空白时使用默认项目
     * @param fromCommit 起始 commit SHA，必填
     * @param toCommit   目标 commit SHA，必填
     * @param depth      遍历最大深度，钳制在 1-5
     * @param limit      符号/关系数量上限，钳制在 1-200
     * @return 影响分析响应；commit 差异计算失败时抛出 IllegalStateException
     */
    public CodeIntelligenceResponse impactCommitsInRepository(String repositoryId, String repositoryPath,
                                                               String fromCommit, String toCommit,
                                                               Integer depth, Integer limit) {
        return impactCommitsResolved(repositoryId, repositoryPath, fromCommit, toCommit, depth, limit);
    }

    public CodeIntelligenceResponse impactCommits(String projectId, String fromCommit, String toCommit,
                                                  Integer depth, Integer limit) {
        return impactCommitsResolved(resolveProject(projectId), null, fromCommit, toCommit, depth, limit);
    }

    private CodeIntelligenceResponse impactCommitsResolved(String project, String repositoryPath,
                                                           String fromCommit, String toCommit,
                                                           Integer depth, Integer limit) {
        if (fromCommit == null || fromCommit.isBlank() || toCommit == null || toCommit.isBlank()) {
            throw new IllegalArgumentException("fromCommit and toCommit are required");
        }
        GitDiffService.GitDiffResult diff;
        try {
            diff = repositoryPath == null || repositoryPath.isBlank()
                    ? gitDiffService.diff(project, fromCommit, toCommit)
                    : gitDiffService.diffInRepository(project, repositoryPath, fromCommit, toCommit);
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

    /**
     * BFS 遍历调用图：EXACT/SAME_FILE 关系命中计入确定符号，HEURISTIC 计入推断符号。
     * 关系数达到 limit 或深度达到 maxDepth 时截断，并附上最多 50 条未解析调用供分析。
     */
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

    /** 组装 AVAILABLE 响应，并为入口点/测试符号生成回归检查建议（最多 20 条）。 */
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

    /** 组装 NOT_AVAILABLE 响应并附带警告信息（如未索引、符号不存在、目标 commit 无快照）。 */
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
