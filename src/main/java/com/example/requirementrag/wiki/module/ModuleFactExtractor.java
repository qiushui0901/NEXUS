package com.example.requirementrag.wiki.module;

import com.example.requirementrag.code.CodeRelation;
import com.example.requirementrag.code.CodeSymbol;
import com.example.requirementrag.code.SQLiteSymbolGraphStore;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.wiki.module.ModuleFactModels.ModuleDiagnostic;
import com.example.requirementrag.wiki.module.ModuleFactModels.ModuleEvidence;
import com.example.requirementrag.wiki.module.ModuleFactModels.ModuleFactBundle;
import com.example.requirementrag.wiki.module.ModuleFactModels.ModuleFlowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 确定性模块事实抽取：从符号图与仓库文件系统编译 ModuleFactBundle，不依赖模型猜测。 */
@Service
public class ModuleFactExtractor {
    private static final Logger log = LoggerFactory.getLogger(ModuleFactExtractor.class);
    private static final int MAX_FILES = 500;
    private static final int MAX_SYMBOLS = 800;
    private static final int MAX_RELATIONS = 200;
    private static final Pattern ROUTE_ANNOTATION = Pattern.compile(
            "@(?:Get|Post|Put|Delete|Patch|Request|RestController|FeignClient|KafkaListener|RabbitListener)"
                    + "Mapping|@RestController|@Scheduled|@FeignClient|@KafkaListener|@RabbitListener");
    private static final Set<String> DATA_SYMBOL_HINTS = Set.of(
            "dao", "repository", "mapper", "cache", "topic", "producer", "consumer", "storage");

    private final ProjectRegistry projectRegistry;
    private final SQLiteSymbolGraphStore graphStore;

    public ModuleFactExtractor(ProjectRegistry projectRegistry, SQLiteSymbolGraphStore graphStore) {
        this.projectRegistry = projectRegistry;
        this.graphStore = graphStore;
    }

    /** 抽取目标模块的事实包；符号图快照缺失时产出诊断而不是失败。 */
    public ModuleFactBundle extract(String projectId, String modulePath, String version) {
        RagProperties.ProjectConfig project = projectRegistry.require(projectId);
        String commit = graphStore.latestCommit(projectId);
        String resolvedPath = normalize(modulePath);
        String moduleId = moduleIdOf(resolvedPath);
        Path repository = repositoryRoot(project);
        List<ModuleDiagnostic> diagnostics = new ArrayList<>();
        if (commit == null) {
            diagnostics.add(new ModuleDiagnostic("NO_GRAPH_SNAPSHOT",
                    "代码图谱快照不存在；请先执行代码索引", resolvedPath));
        }

        List<Path> files = moduleFiles(project, resolvedPath, diagnostics);
        List<String> relativeFiles = relativeFiles(repository, files);
        List<CodeSymbol> symbols = commit == null ? List.of()
                : graphStore.symbolsByFiles(projectId, commit, relativeFiles, MAX_SYMBOLS);

        List<CodeSymbol> entryPoints = symbols.stream().filter(CodeSymbol::entryPoint).limit(30).toList();
        List<CodeSymbol> tests = symbols.stream().filter(CodeSymbol::testSymbol).limit(30).toList();
        List<CodeSymbol> publicSymbols = symbols.stream()
                .filter(symbol -> !symbol.testSymbol())
                .limit(80)
                .toList();
        List<String> routes = routesIn(files);
        List<String> dataObjects = dataObjects(symbols);
        List<String> configuration = configurationIn(files);
        List<String> packages = packagesOf(symbols, files);
        List<ModuleFlowStep> coreFlows = commit == null ? List.of() : coreFlows(projectId, commit, symbols);
        List<String> callers = commit == null ? List.of()
                : moduleExternal(projectId, commit, symbols, true);
        List<String> callees = commit == null ? List.of()
                : moduleExternal(projectId, commit, symbols, false);
        if (commit != null) {
            unresolvedInModule(projectId, commit, relativeFiles, diagnostics);
        }

        List<ModuleEvidence> evidence = registerEvidence(projectId, version, commit, moduleId, symbols,
                callers, callees, coreFlows, routes, dataObjects, configuration, tests, diagnostics);
        return new ModuleFactBundle(projectId, commit, moduleId, titleOf(moduleId),
                resolvedPath, relativeFiles, packages, publicSymbols, entryPoints,
                callers, callees, coreFlows, routes, dataObjects, configuration, tests,
                evidence, List.copyOf(diagnostics));
    }

    /** 按符号图解析模块内符号的出入向关系，汇总为模块内外调用清单。 */
    private List<String> moduleExternal(String projectId, String commit, List<CodeSymbol> symbols,
                                        boolean inbound) {
        Set<String> inside = new LinkedHashSet<>();
        for (CodeSymbol symbol : symbols) inside.add(symbol.id());
        Map<String, String> external = new LinkedHashMap<>();
        for (CodeSymbol symbol : symbols) {
            for (CodeRelation relation : graphStore.relations(projectId, commit, symbol.id(), inbound,
                    MAX_RELATIONS)) {
                String peerId = inbound ? relation.callerSymbolId() : relation.calleeSymbolId();
                if (inside.contains(peerId)) continue;
                CodeSymbol peer = graphStore.symbolById(projectId, commit, peerId);
                String name = peer == null ? relation.targetName() : peer.qualifiedName();
                if (name == null || name.isBlank()) continue;
                external.putIfAbsent(name, relation.filePath());
                if (external.size() >= 40) break;
            }
        }
        return external.entrySet().stream().limit(40)
                .map(entry -> entry.getKey() + " @ " + entry.getValue())
                .toList();
    }

    /** 提取模块内的核心调用链：模块内符号的出向调用，按被调用符号分组并按行号排序。 */
    private List<ModuleFlowStep> coreFlows(String projectId, String commit, List<CodeSymbol> symbols) {
        Set<String> inside = new LinkedHashSet<>();
        for (CodeSymbol symbol : symbols) inside.add(symbol.id());
        List<ModuleFlowStep> flows = new ArrayList<>();
        for (CodeSymbol symbol : symbols) {
            for (CodeRelation relation : graphStore.relations(projectId, commit, symbol.id(), false,
                    MAX_RELATIONS)) {
                if (inside.contains(relation.calleeSymbolId())) {
                    CodeSymbol callee = graphStore.symbolById(projectId, commit, relation.calleeSymbolId());
                    String calleeName = callee == null ? relation.targetName() : callee.qualifiedName();
                    flows.add(new ModuleFlowStep(symbol.qualifiedName(), calleeName,
                            relation.filePath(), relation.line(), relation.resolution()));
                    if (flows.size() >= 60) return List.copyOf(flows);
                }
            }
        }
        flows.sort(Comparator.comparingInt(ModuleFlowStep::line));
        return List.copyOf(flows);
    }

    /** 将模块事实按页面证据顺序注册为稳定 ID，供各类 Claim 精确引用。 */
    private List<ModuleEvidence> registerEvidence(String projectId, String version, String commit,
                                                  String moduleId, List<CodeSymbol> symbols,
                                                  List<String> callers, List<String> callees,
                                                  List<ModuleFlowStep> flows, List<String> routes,
                                                  List<String> dataObjects, List<String> configuration,
                                                  List<CodeSymbol> tests, List<ModuleDiagnostic> diagnostics) {
        List<ModuleEvidence> evidence = new ArrayList<>();
        for (CodeSymbol symbol : symbols) {
            if (symbol.testSymbol()) continue;
            addEvidence(evidence, "code", moduleId, "CODE", projectId, version, commit,
                    symbol.filePath(), symbol.qualifiedName(), symbol.startLine(), symbol.endLine(), symbol.id());
        }
        for (ModuleFlowStep flow : flows) {
            addEvidence(evidence, "code-graph", moduleId, "CODE_GRAPH", projectId, version, commit,
                    flow.filePath(), flow.caller() + " -> " + flow.callee(), flow.line(), flow.line(),
                    flow.resolution().name());
        }
        for (String dependency : concat(callers, callees)) {
            addEvidence(evidence, "dependency", moduleId, "DEPENDENCY", projectId, version, commit,
                    dependency, dependency, 0, 0, dependency);
        }
        for (String route : routes) {
            addEvidence(evidence, "route", moduleId, "ROUTE", projectId, version, commit,
                    route, route, 0, 0, route);
        }
        for (String dataObject : dataObjects) {
            addEvidence(evidence, "data", moduleId, "DATA", projectId, version, commit,
                    dataObject, dataObject, 0, 0, dataObject);
        }
        for (String config : configuration) {
            addEvidence(evidence, "config", moduleId, "CONFIG", projectId, version, commit,
                    config, config, 0, 0, config);
        }
        for (CodeSymbol test : tests) {
            addEvidence(evidence, "test", moduleId, "TEST_SYMBOL", projectId, version, commit,
                    test.filePath(), test.qualifiedName(), test.startLine(), test.endLine(), test.id());
        }
        for (ModuleDiagnostic diagnostic : diagnostics) {
            addEvidence(evidence, "diagnostic", moduleId, "DIAGNOSTIC", projectId, version, commit,
                    diagnostic.source(), diagnostic.code(), 0, 0, diagnostic.message());
        }
        return List.copyOf(evidence);
    }

    private void addEvidence(List<ModuleEvidence> evidence, String namespace, String moduleId, String type,
                             String projectId, String version, String commit, String source, String symbol,
                             int startLine, int endLine, String fingerprint) {
        int index = evidence.size();
        evidence.add(new ModuleEvidence(namespace + ":" + moduleId + ":" + index, type, projectId, version,
                commit == null ? "" : commit, source, symbol, startLine, endLine, sha256(fingerprint)));
    }

    private List<String> concat(List<String> first, List<String> second) {
        return Stream.concat(first.stream(), second.stream()).distinct().toList();
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** 收集模块目录内带 HTTP/消息/定时注解的文件与注解位置，作为对外入口线索。 */
    private List<String> routesIn(List<Path> files) {
        List<String> routes = new ArrayList<>();
        for (Path file : files) {
            if (!isSourceFile(file)) continue;
            try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
                List<String> matched = lines.map(String::trim)
                        .filter(ROUTE_ANNOTATION.asPredicate())
                        .map(annotation -> file.getFileName() + ": " + annotation)
                        .limit(8)
                        .toList();
                routes.addAll(matched);
                if (routes.size() >= 40) break;
            } catch (IOException exception) {
                log.debug("Module route scan skipped for {}", file, exception);
            }
        }
        return routes.size() > 40 ? routes.subList(0, 40) : routes;
    }

    /** 符号名含 DAO/Repository/Mapper/Cache/Topic/Consumer 等命名的数据与消息对象。 */
    private List<String> dataObjects(List<CodeSymbol> symbols) {
        return symbols.stream()
                .filter(symbol -> !symbol.testSymbol())
                .map(symbol -> symbol.qualifiedName())
                .filter(name -> {
                    String lower = name.toLowerCase(Locale.ROOT);
                    return DATA_SYMBOL_HINTS.stream().anyMatch(lower::contains);
                })
                .distinct()
                .limit(30)
                .toList();
    }

    /** 模块目录内的配置文件与含配置引用的文件。 */
    private List<String> configurationIn(List<Path> files) {
        return files.stream()
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".yml") || name.endsWith(".yaml")
                        || name.endsWith(".properties") || name.endsWith(".env")
                        || name.equals("application.conf"))
                .distinct()
                .limit(20)
                .toList();
    }

    /** 收集模块内的 package / 顶层源码目录。 */
    private List<String> packagesOf(List<CodeSymbol> symbols, List<Path> files) {
        Set<String> packages = new LinkedHashSet<>();
        for (CodeSymbol symbol : symbols) {
            String qualified = symbol.qualifiedName();
            int lastDot = qualified.lastIndexOf('.');
            if (lastDot > 0) packages.add(qualified.substring(0, lastDot));
        }
        for (Path file : files) {
            String relative = file.toString().replace('\\', '/');
            int separator = relative.indexOf('/');
            if (separator > 0) packages.add(relative.substring(0, separator));
        }
        return packages.stream().limit(20).toList();
    }

    /** 汇总模块内未解析调用关系为诊断。 */
    private void unresolvedInModule(String projectId, String commit, List<String> files,
                                    List<ModuleDiagnostic> diagnostics) {
        Set<String> relative = new LinkedHashSet<>(files);
        for (CodeRelation relation : graphStore.unresolved(projectId, commit, 200)) {
            if (relation.filePath() != null && relative.contains(normalize(relation.filePath()))) {
                diagnostics.add(new ModuleDiagnostic("UNRESOLVED_DYNAMIC_CALL",
                        "调用目标无法静态解析: " + relation.targetName(), relation.filePath()
                                + ":" + relation.line()));
                if (diagnostics.size() >= 20) return;
            }
        }
    }

    /** 列出模块目录内源码文件（绝对路径，限 500，忽略构建与隐藏目录）。 */
    private List<Path> moduleFiles(RagProperties.ProjectConfig project, String modulePath,
                                   List<ModuleDiagnostic> diagnostics) {
        Path repository = repositoryRoot(project);
        Path moduleRoot = repository.resolve(modulePath).normalize();
        if (!moduleRoot.startsWith(repository)) {
            throw new IllegalArgumentException("modulePath 越出仓库根目录");
        }
        if (!Files.isDirectory(moduleRoot)) {
            diagnostics.add(new ModuleDiagnostic("MODULE_PATH_NOT_FOUND",
                    "模块目录不存在或仓库不可用: " + modulePath, modulePath));
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(moduleRoot)) {
            for (Path path : paths.sorted().toList()) {
                if (files.size() >= MAX_FILES) break;
                Path relative = repository.relativize(path);
                String name = relative.toString();
                if (name.contains("/target/") || name.contains("/build/")
                        || name.contains("/node_modules/") || name.contains("/.git/")) {
                    continue;
                }
                if (Files.isRegularFile(path)) files.add(path.toAbsolutePath().normalize());
            }
        } catch (IOException exception) {
            diagnostics.add(new ModuleDiagnostic("MODULE_SCAN_FAILED",
                    "模块目录读取失败: " + exception.getMessage(), modulePath));
        }
        return List.copyOf(files);
    }


    /** 仓库相对路径列表（用于符号图按文件查询）。 */
    private List<String> relativeFiles(Path repository, List<Path> files) {
        return files.stream()
                .map(path -> normalize(repository.relativize(path).toString()))
                .filter(path -> isSourceFile(path))
                .limit(MAX_FILES)
                .toList();
    }

    private Path repositoryRoot(RagProperties.ProjectConfig project) {
        String configured = project.repositoryPath() == null || project.repositoryPath().isBlank()
                ? null : project.repositoryPath();
        if (configured == null) {
            throw new IllegalArgumentException("项目代码仓库路径未配置");
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private boolean isSourceFile(Path file) {
        return isSourceFile(file.toString());
    }

    private boolean isSourceFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".py")
                || lower.endsWith(".go") || lower.endsWith(".ts") || lower.endsWith(".tsx")
                || lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".rs")
                || lower.endsWith(".cpp") || lower.endsWith(".c") || lower.endsWith(".h");
    }

    private String moduleIdOf(String modulePath) {
        String normalized = normalize(modulePath);
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return name.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("^-|-$", "");
    }

    private String titleOf(String moduleId) {
        return moduleId.isEmpty() ? "未知模块" : moduleId;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace('\\', '/');
    }
}
