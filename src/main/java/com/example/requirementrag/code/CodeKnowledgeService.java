package com.example.requirementrag.code;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.CodeGraphResponse;
import com.example.requirementrag.model.CodeIndexResponse;
import com.example.requirementrag.model.SourceSnippet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.Comparator;

/**
 * 代码知识服务：索引 Java 代码、向量检索、读取源码片段。
 * 支持多项目，通过 ProjectRegistry 解析项目配置。
 */
@Service
public class CodeKnowledgeService {
    private static final Logger log = LoggerFactory.getLogger(CodeKnowledgeService.class);

    private final RagProperties properties;
    private final ProjectRegistry projectRegistry;
    private final CodeScanner scanner;
    private final CodeQdrantStore store;
    private final SQLiteSymbolGraphStore graphStore;
    private final CodeSemanticAnnotator annotator;
    private final CodeIndexLockService indexLockService;

    @Autowired
    public CodeKnowledgeService(RagProperties properties, ProjectRegistry projectRegistry,
                                CodeScanner scanner, CodeQdrantStore store,
                                SQLiteSymbolGraphStore graphStore,
                                CodeSemanticAnnotator annotator,
                                CodeIndexLockService indexLockService) {
        this.properties = properties;
        this.projectRegistry = projectRegistry;
        this.scanner = scanner;
        this.store = store;
        this.graphStore = graphStore;
        this.annotator = annotator;
        this.indexLockService = indexLockService;
    }

    /** Compatibility constructor for pre-0.7 unit callers. */
    CodeKnowledgeService(RagProperties properties, ProjectRegistry projectRegistry,
                         JavaCodeScanner scanner, CodeQdrantStore store) {
        this(properties, projectRegistry, legacy(scanner), store, null, null, new CodeIndexLockService());
    }

    /** 扫描默认配置仓库并替换写入 Qdrant。 */
    public CodeIndexResponse index() throws IOException {
        return index(properties.code().projectId());
    }

    /**
     * 扫描指定项目仓库并替换写入 Qdrant。
     * 同一项目的索引任务在项目级锁内串行执行（同步 API / webhook / 后台任务共用此入口），
     * 杜绝旧索引晚完成覆盖新 live 的发布乱序。
     */
    public CodeIndexResponse index(String projectId) throws IOException {
        try {
            return indexLockService.execute(projectId, () -> {
                RagProperties.ProjectConfig project = projectRegistry.require(projectId);
                CodeScanner.ScanResult result = scanner.scan(project.toCodeConfig());
                List<CodeChunk> annotated = annotateChunks(projectId, result.chunks());
                store.publishProject(liveCollection(projectId), result.projectId(), annotated);
                if (graphStore != null) graphStore.replaceSnapshot(result);
                return new CodeIndexResponse(result.projectId(), result.commitSha(), result.files(),
                        annotated.size());
            });
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("代码索引失败", exception);
        }
    }

    /** 代码块语义标注：先加载既有标注缓存，再分层标注（LLM + 静态），annotator 不可用时不阻断索引。 */
    private List<CodeChunk> annotateChunks(String projectId, List<CodeChunk> chunks) {
        if (annotator == null || chunks == null || chunks.isEmpty()) {
            return chunks == null ? List.of() : chunks;
        }
        try {
            String collection = liveCollection(projectId);
            Map<String, CodeQdrantStore.AnnotationEntry> cache = store.fetchAnnotationCache(collection, projectId);
            return annotator.annotateWithCache(chunks, cache);
        } catch (RuntimeException exception) {
            log.warn("代码语义标注跳过（回退为未标注索引）: {}", exception.getMessage());
            return chunks;
        }
    }

    /** 语义搜索代码 chunk。 */
    public List<CodeChunk> search(String query, String projectId, Integer limit) {
        String resolvedProject = projectId == null || projectId.isBlank() ? properties.code().projectId() : projectId;
        int resolvedLimit = Math.min(Math.max(limit == null ? 10 : limit, 1), 50);
        String collection = liveCollection(resolvedProject);
        return store.hybridSearch(collection, query, resolvedProject, resolvedLimit);
    }

    /** 返回代码 RRF 候选与最终精排结果，仅用于有界离线诊断。 */
    public CodeQdrantStore.CodeSearchTrace searchTrace(String query, String projectId, Integer limit) {
        String resolvedProject = projectId == null || projectId.isBlank() ? properties.code().projectId() : projectId;
        int resolvedLimit = Math.min(Math.max(limit == null ? 10 : limit, 1), 50);
        String collection = liveCollection(resolvedProject);
        return store.hybridSearchTrace(collection, query, resolvedProject, resolvedLimit);
    }

    /** 同时检索当前项目与同组对端（不同 side）项目的代码 chunk。 */
    private List<CodeChunk> searchCrossSide(String query, String projectId, int limit) {
        String resolvedProject = projectId == null || projectId.isBlank() ? properties.code().projectId() : projectId;
        List<String> projectIds = new ArrayList<>();
        projectIds.add(resolvedProject);
        try {
            RagProperties.ProjectConfig current = projectRegistry.require(resolvedProject);
            String group = current.group();
            String side = current.side();
            if (group != null && !group.isBlank()) {
                projectRegistry.findByGroup(group).stream()
                        .filter(project -> !resolvedProject.equals(project.id()))
                        .filter(project -> side == null || side.isBlank() || !side.equals(project.side()))
                        .map(RagProperties.ProjectConfig::id)
                        .forEach(projectIds::add);
            }
        }
        catch (IllegalArgumentException exception) {
            log.warn("Cross-side search is using only the resolved project because project metadata is unavailable: {}",
                    resolvedProject, exception);
        }
        return projectIds.stream()
                .flatMap(pid -> safeSearch(query, pid, limit))
                .limit(limit)
                .toList();
    }

    private Stream<CodeChunk> safeSearch(String query, String projectId, int limit) {
        try {
            return search(query, projectId, limit).stream();
        }
        catch (RuntimeException exception) {
            log.warn("Code search failed for project {}; omitting that project from cross-side results",
                    projectId, exception);
            return Stream.empty();
        }
    }

    private Map<String, String> projectSides(List<CodeChunk> hits) {
        Map<String, String> sides = new HashMap<>();
        for (CodeChunk hit : hits) {
            String projectId = hit.projectId();
            if (projectId == null || projectId.isBlank() || sides.containsKey(projectId)) {
                continue;
            }
            projectRegistry.find(projectId)
                    .map(RagProperties.ProjectConfig::side)
                    .ifPresent(side -> sides.put(projectId, side));
        }
        return sides;
    }

    /** 按视图构建前端代码图谱。 */
    public CodeGraphResponse graph(String query, String projectId, String view, Integer limit) {
        return graph(query, projectId, view, limit, false);
    }

    /** 按视图构建前端代码图谱，可选同时检索同组对端项目代码。 */
    public CodeGraphResponse graph(String query, String projectId, String view, Integer limit, boolean crossSide) {
        String resolvedView = resolveView(query, view);
        int resolvedLimit = limit == null ? 16 : limit;
        List<CodeChunk> hits = crossSide
                ? searchCrossSide(query, projectId, resolvedLimit)
                : search(query, projectId, resolvedLimit);
        Map<String, String> projectSides = projectSides(hits);
        GraphBuilder builder = new GraphBuilder(query, resolvedView, hits, crossSide, projectSides);
        return builder.build();
    }

    /** 返回指定项目已写入的代码 chunk 数。 */
    public long count(String projectId) {
        String resolvedProject = projectId == null || projectId.isBlank() ? properties.code().projectId() : projectId;
        String collection = liveCollection(resolvedProject);
        return store.countProject(collection, resolvedProject);
    }

    /** 返回默认项目已写入的代码 chunk 数。 */
    public long count() {
        return count(null);
    }

    /** 读取源码片段，用于前端点击检索结果后展示。 */
    public SourceSnippet source(String projectId, String filePath, Integer startLine, Integer endLine) throws IOException {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("filePath required");
        }
        String repoPath = resolveRepositoryPath(projectId);
        Path root = Path.of(repoPath).toRealPath();
        Path file = root.resolve(filePath).normalize().toRealPath();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("filePath escapes repository root");
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int requestedStart = startLine == null ? 1 : startLine;
        int requestedEnd = endLine == null ? Math.min(lines.size(), requestedStart + 120) : endLine;
        if (requestedStart < 1 || requestedEnd < 1 || requestedStart > requestedEnd) {
            throw new IllegalArgumentException("非法行范围: startLine=" + startLine + ", endLine=" + endLine);
        }
        if (requestedEnd > lines.size()) {
            throw new IllegalArgumentException("行范围超出文件长度: startLine=" + startLine
                    + ", endLine=" + endLine + "（文件共 " + lines.size() + " 行）");
        }
        int start = requestedStart;
        int end = requestedEnd;
        StringBuilder text = new StringBuilder();
        for (int line = start; line <= end; line++) {
            text.append(String.format("%5d  %s%n", line, lines.get(line - 1)));
        }
        return new SourceSnippet(filePath, start, end, text.toString());
    }

    private String resolveCodeCollection(String projectId) {
        try {
            return projectRegistry.resolveCodeCollection(projectId);
        } catch (IllegalArgumentException exception) {
            log.warn("Code collection is not configured for project {}; using the default collection",
                    projectId, exception);
            return properties.code().collection();
        }
    }

    /** 检索侧统一使用 Alias 名（{@code <base>-live}），指向最新发布的物理 collection。 */
    private String liveCollection(String projectId) {
        return resolveCodeCollection(projectId) + "-live";
    }

    /** 将旧版仅支持 Java 的扫描器适配为 CodeScanner 契约，兼容 pre-0.7 单元调用方。 */
    static CodeScanner legacy(JavaCodeScanner scanner) {
        return new CodeScanner() {
            @Override
            public ScanResult scan(RagProperties.Code config) throws IOException {
                JavaCodeScanner.ScanResult result = scanner.scan(config);
                return new ScanResult(result.projectId(), result.commitSha(), result.files(), result.chunks(),
                        List.of(), List.of(), List.of());
            }

            @Override
            public ScanResult scanFiles(RagProperties.Code config, String commitSha, List<String> paths)
                    throws IOException {
                List<CodeChunk> chunks = scanner.scanFiles(config, commitSha, paths);
                return new ScanResult(config.projectId(), commitSha, paths.size(), chunks,
                        List.of(), List.of(), List.of());
            }

            @Override
            public boolean supports(String path) {
                return path != null && path.endsWith(".java");
            }
        };
    }

    private String resolveRepositoryPath(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return properties.code().repositoryPath();
        }
        try {
            RagProperties.ProjectConfig project = projectRegistry.require(projectId);
            String path = project.repositoryPath();
            return (path != null && !path.isBlank()) ? path : properties.code().repositoryPath();
        } catch (IllegalArgumentException exception) {
            log.warn("Repository path is not configured for project {}; using the default repository",
                    projectId, exception);
            return properties.code().repositoryPath();
        }
    }

    /** 视图为空或 auto 时，按查询关键词推断 flow/class/method 视图，否则返回结构视图 structure。 */
    private String resolveView(String query, String view) {
        if (view != null && !view.isBlank() && !"auto".equalsIgnoreCase(view)) {
            return view;
        }
        if (query != null && query.matches(".*(链路|流程|调用|路径|flow|call).*")) {
            return "flow";
        }
        if (query != null && query.matches(".*(类|class|对象|模型).*")) {
            return "class";
        }
        if (query != null && query.matches(".*(函数|方法|method|function|接口).*")) {
            return "method";
        }
        return "structure";
    }

    /** 将检索命中的 chunk 按视图（flow/class/method/structure）组装为前端代码图谱：节点、分层、讲解步骤与命中列表。 */
    private static final class GraphBuilder {
        private final String query;
        private final String view;
        private final List<CodeChunk> hits;
        private final boolean crossSide;
        private final Map<String, String> projectSides;
        private final Map<String, CodeGraphResponse.CodeGraphNode> nodes = new LinkedHashMap<>();
        private final Map<String, CodeGraphResponse.CodeGraphEdge> edges = new LinkedHashMap<>();

        private GraphBuilder(String query, String view, List<CodeChunk> hits, boolean crossSide,
                             Map<String, String> projectSides) {
            this.query = query;
            this.view = view;
            this.hits = hits;
            this.crossSide = crossSide;
            this.projectSides = projectSides == null ? Map.of() : projectSides;
        }

        /** 按视图分发构建：flow 走调用链式连接，class/method 只取对应符号类型，其他走结构视图。 */
        private CodeGraphResponse build() {
            if ("flow".equals(view)) {
                buildFlow();
            }
            else if ("class".equals(view)) {
                hits.stream().filter(hit -> "class".equals(hit.symbolType())).forEach(this::addSymbolNode);
            }
            else if ("method".equals(view) || "function".equals(view)) {
                hits.stream().filter(hit -> "method".equals(hit.symbolType())).forEach(this::addSymbolNode);
            }
            else {
                buildStructure();
            }
            List<CodeGraphResponse.CodeGraphLayer> layers = layers();
            return new CodeGraphResponse(query, view, intent(), summary(), layers, tour(layers), new ArrayList<>(nodes.values()),
                    new ArrayList<>(edges.values()), hits);
        }

        private String intent() {
            return switch (view) {
                case "flow" -> "链路理解：只保留当前查询相关的类/方法，并用相关顺序连接，适合看一条业务怎么跑。";
                case "class" -> "类定位：只展示相关类，适合先找模块边界和主要对象。";
                case "method", "function" -> "方法定位：只展示相关函数/方法，适合找具体实现点。";
                default -> "结构理解：展示文件、类、方法的包含关系，适合从项目结构切入。";
            };
        }

        private String summary() {
            if (hits.isEmpty()) {
                return "未命中代码";
            }
            return "已更新";
        }

        /** 结构视图：每个命中生成一个文件节点和符号节点，并以「文件包含符号」边相连。 */
        private void buildStructure() {
            for (CodeChunk hit : hits) {
                String fileId = fileId(hit.filePath(), hit);
                addNode(fileId, "file", Path.of(hit.filePath()).getFileName().toString(), hit.filePath(), null, null,
                        "包含本次命中的代码片段，可作为理解「" + query + "」的源码入口。", hit);
                String symbolId = addSymbolNode(hit);
                addEdge(fileId, symbolId, "contains", "文件包含：" + role(hit));
            }
        }

        /** 链路视图：按命中顺序把类/方法符号串成 next 边，方法节点补充同文件的类容器边。 */
        private void buildFlow() {
            String previous = null;
            CodeChunk previousHit = null;
            for (CodeChunk hit : hits) {
                if (!"class".equals(hit.symbolType()) && !"method".equals(hit.symbolType())) {
                    continue;
                }
                String current = addSymbolNode(hit);
                if ("method".equals(hit.symbolType())) {
                    classForSameFile(hit);
                }
                if (previous != null && !previous.equals(current)) {
                    addEdge(previous, current, "next", relationLabel(previousHit, hit));
                }
                previous = current;
                previousHit = hit;
            }
        }

        private void classForSameFile(CodeChunk method) {
            hits.stream()
                    .filter(hit -> "class".equals(hit.symbolType()))
                    .filter(hit -> hit.filePath().equals(method.filePath()))
                    .findFirst()
                    .ifPresent(clazz -> addEdge(addSymbolNode(clazz), addSymbolNode(method), "contains",
                            "类承载：" + role(method)));
        }

        private String addSymbolNode(CodeChunk hit) {
            String id = symbolId(hit);
            addNode(id, hit.symbolType(), hit.symbolName(), hit.filePath(), hit.startLine(), hit.endLine(), relevance(hit), hit);
            return id;
        }

        private void addNode(String id, String type, String label, String filePath, Integer startLine, Integer endLine,
                             String relevance, CodeChunk hit) {
            String role = role(type, label, filePath, relevance);
            String projectId = hit == null ? null : hit.projectId();
            String side = projectId == null ? null : projectSides.get(projectId);
            nodes.putIfAbsent(id, new CodeGraphResponse.CodeGraphNode(id, type, label, filePath, startLine, endLine,
                    role, layerId(role, type), relevance, projectId, side));
        }

        private void addEdge(String source, String target, String type, String label) {
            edges.putIfAbsent(source + "->" + target + ":" + type, new CodeGraphResponse.CodeGraphEdge(source, target, type, label));
        }

        private String fileId(String filePath, CodeChunk hit) {
            if (crossSide && hit != null && hit.projectId() != null && !hit.projectId().isBlank()) {
                return "file:" + hit.projectId() + ":" + filePath;
            }
            return "file:" + filePath;
        }

        private String symbolId(CodeChunk hit) {
            String base = hit.symbolType() + ":" + hit.filePath() + ":" + hit.symbolName() + ":" + hit.startLine();
            if (crossSide && hit.projectId() != null && !hit.projectId().isBlank()) {
                return hit.projectId() + ":" + base;
            }
            return base;
        }

        private String relationLabel(CodeChunk from, CodeChunk to) {
            if (from == null || to == null) {
                return "需求相关";
            }
            String left = role(from);
            String right = role(to);
            if (left.equals(right)) {
                return left + "相关";
            }
            return left + " → " + right;
        }

        private String relevance(CodeChunk hit) {
            String role = role(hit);
            String symbol = hit.symbolName();
            return switch (hit.symbolType()) {
                case "class" -> "这个类命中了「" + query + "」的代码语义，主要像是" + role + "的承载对象。";
                case "method" -> "这个方法与「" + query + "」相关，可能负责" + role + "；建议点开源码确认入参、状态判断和副作用。";
                default -> "这个代码片段与「" + query + "」相关，命中符号：" + symbol + "。";
            };
        }

        /** 固定注册 8 个业务分层的名称与描述，节点按所属层分组；层间按固定顺序排序。 */
        private List<CodeGraphResponse.CodeGraphLayer> layers() {
            Map<String, List<String>> nodeIdsByLayer = new LinkedHashMap<>();
            Map<String, String> layerNames = new LinkedHashMap<>();
            Map<String, String> layerDescriptions = new LinkedHashMap<>();

            registerLayer(layerNames, layerDescriptions, "layer:entry", "入口/API", "接口、活动入口、外部调用点；适合先判断需求从哪里进入系统。");
            registerLayer(layerNames, layerDescriptions, "layer:orchestration", "业务编排", "Service/Manager/Handler 等承接主流程的类或方法。");
            registerLayer(layerNames, layerDescriptions, "layer:rules", "规则判断", "校验、状态判断、是否可领取/购买等产品规则落点。");
            registerLayer(layerNames, layerDescriptions, "layer:config", "配置参数", "活动配置、奖励配置、价格参数和开关。");
            registerLayer(layerNames, layerDescriptions, "layer:reward", "奖励/成长数值", "成长、等级、战力、奖励、发放等数值相关逻辑。");
            registerLayer(layerNames, layerDescriptions, "layer:state", "状态变更/持久化", "写状态、保存领取/购买记录、结算和幂等落点。");
            registerLayer(layerNames, layerDescriptions, "layer:view", "展示/通知", "红点、推送、首页展示和推荐展示等用户可见逻辑。");
            registerLayer(layerNames, layerDescriptions, "layer:types", "数据结构", "请求、响应、DTO、结果对象和模块实体。");

            for (CodeGraphResponse.CodeGraphNode node : nodes.values()) {
                String layer = node.layer() == null || node.layer().isBlank() ? "layer:types" : node.layer();
                nodeIdsByLayer.computeIfAbsent(layer, ignored -> new ArrayList<>()).add(node.id());
            }

            return nodeIdsByLayer.entrySet().stream()
                    .map(entry -> new CodeGraphResponse.CodeGraphLayer(
                            entry.getKey(),
                            layerNames.getOrDefault(entry.getKey(), "相关代码"),
                            layerDescriptions.getOrDefault(entry.getKey(), "本层包含当前查询命中的相关代码节点。"),
                            entry.getValue()))
                    .sorted(Comparator.comparingInt(layer -> layerOrder(layer.id())))
                    .toList();
        }

        /** 为非空分层生成讲解步骤：每层一句引导文案，最多带 5 个节点供前端展开。 */
        private List<CodeGraphResponse.CodeGraphTourStep> tour(List<CodeGraphResponse.CodeGraphLayer> layers) {
            List<CodeGraphResponse.CodeGraphTourStep> steps = new ArrayList<>();
            int order = 1;
            for (CodeGraphResponse.CodeGraphLayer layer : layers) {
                if (layer.nodeIds().isEmpty()) {
                    continue;
                }
                steps.add(new CodeGraphResponse.CodeGraphTourStep(order++, "先看：" + layer.name(),
                        switch (layer.id()) {
                            case "layer:entry" -> "先确定「" + query + "」从哪个接口或入口进入，避免一上来陷进细节。";
                            case "layer:orchestration" -> "再看业务编排，弄清主流程把配置、校验、发奖、持久化怎么串起来。";
                            case "layer:rules" -> "这里通常对应产品规则，重点看条件判断和状态分支。";
                            case "layer:config" -> "这里决定运营可配字段、档位、开关和价格如何被代码解释。";
                            case "layer:reward" -> "这里关系到成长数值、奖励发放、等级/战力变化，改动要谨慎。";
                            case "layer:state" -> "最后确认状态写入和幂等，防止重复领取、重复购买或跨期串数据。";
                            case "layer:view" -> "这里影响入口展示、红点和通知，适合和前端联调时看。";
                            default -> "这些类型/结构节点帮助理解接口入参、返回值和领域对象。";
                        },
                        layer.nodeIds().stream().limit(5).toList()));
            }
            return steps;
        }

        private void registerLayer(Map<String, String> names, Map<String, String> descriptions,
                                   String id, String name, String description) {
            names.put(id, name);
            descriptions.put(id, description);
        }

        private int layerOrder(String id) {
            return switch (id) {
                case "layer:entry" -> 0;
                case "layer:orchestration" -> 1;
                case "layer:rules" -> 2;
                case "layer:config" -> 3;
                case "layer:reward" -> 4;
                case "layer:state" -> 5;
                case "layer:view" -> 6;
                case "layer:types" -> 7;
                default -> 99;
            };
        }

        /** 按符号名、路径与命中文本启发式归类代码角色（入口/编排/规则/配置等），用于分层与讲解文案。 */
        private String role(String type, String label, String filePath, String relevance) {
            String text = ((type == null ? "" : type) + " " + (label == null ? "" : label) + " "
                    + (filePath == null ? "" : filePath) + " " + (relevance == null ? "" : relevance)).toLowerCase(Locale.ROOT);
            if ("file".equals(type)) {
                return "文件容器";
            }
            if (containsAny(text, "controller", "moa", "api", "入口", "start", "apply")) {
                return "入口/API";
            }
            if (containsAny(text, "service", "manager", "handler", "业务编排")) {
                return "业务编排";
            }
            if (containsAny(text, "check", "can", "valid", "status", "条件判断")) {
                return "规则判断";
            }
            if (containsAny(text, "config", "cfg", "param", "setting", "配置")) {
                return "配置参数";
            }
            if (containsAny(text, "reward", "bonus", "power", "grow", "upgrade", "level", "tier", "奖励", "成长")) {
                return "奖励/成长数值";
            }
            if (containsAny(text, "save", "update", "add", "delete", "settle", "dao", "redis", "状态变更")) {
                return "状态变更/持久化";
            }
            if (containsAny(text, "send", "notify", "push", "index", "red", "通知", "展示")) {
                return "展示/通知";
            }
            if (containsAny(text, "result", "response", "request", "dto", "vo", "返回结构", "请求")) {
                return "数据结构";
            }
            return "相关实现";
        }

        /** 将角色映射为前端展示分层 ID，文件节点一律归入 types 层。 */
        private String layerId(String role, String type) {
            if ("file".equals(type)) {
                return "layer:types";
            }
            return switch (role) {
                case "入口/API" -> "layer:entry";
                case "业务编排" -> "layer:orchestration";
                case "规则判断" -> "layer:rules";
                case "配置参数" -> "layer:config";
                case "奖励/成长数值" -> "layer:reward";
                case "状态变更/持久化" -> "layer:state";
                case "展示/通知" -> "layer:view";
                default -> "layer:types";
            };
        }

        /** 按符号名与源码文本启发式归类方法/类/文件的具体职责（类走 classRole 分支）。 */
        private String role(CodeChunk hit) {
            String name = hit.symbolName() == null ? "" : hit.symbolName();
            String text = (name + " " + (hit.text() == null ? "" : hit.text())).toLowerCase(Locale.ROOT);
            if ("class".equals(hit.symbolType())) {
                return classRole(name, text);
            }
            if (containsAny(text, "config", "cfg", "param", "setting")) {
                return "配置/参数";
            }
            if (containsAny(text, "check", "can", "status", "valid", "verify")) {
                return "条件判断";
            }
            if (containsAny(text, "cost", "price", "consume")) {
                return "消耗计算";
            }
            if (containsAny(text, "reward", "bonus", "power", "grow", "upgrade", "level", "tier")) {
                return "成长/奖励数值";
            }
            if (containsAny(text, "apply", "accept", "agree", "invite", "collect", "start", "init")) {
                return "入口/操作流程";
            }
            if (containsAny(text, "send", "notify", "push", "index", "red")) {
                return "通知/展示";
            }
            if (containsAny(text, "save", "update", "add", "delete", "settle")) {
                return "状态变更";
            }
            return "业务实现";
        }

        /** 按类名后缀与文本关键词归类类的职责（返回结构/请求参数/业务编排/配置承载等）。 */
        private String classRole(String name, String text) {
            String lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (containsAny(lowerName, "result", "response", "vo", "dto")) {
                return "返回结构";
            }
            if (containsAny(lowerName, "param", "request")) {
                return "请求/配置参数";
            }
            if (containsAny(lowerName, "service", "manager", "handler")) {
                return "业务编排";
            }
            if (containsAny(text, "config", "cfg")) {
                return "配置承载";
            }
            return "模块对象";
        }

        private boolean containsAny(String text, String... words) {
            for (String word : words) {
                if (text.contains(word)) {
                    return true;
                }
            }
            return false;
        }
    }
}
