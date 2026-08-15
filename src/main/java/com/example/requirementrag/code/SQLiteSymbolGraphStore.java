package com.example.requirementrag.code;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 事务性、按项目/commit 快照隔离的静态符号图 SQLite 存储：快照、符号与调用关系。 */
@Component
public class SQLiteSymbolGraphStore {
    private final String jdbcUrl;

    /** 初始化数据目录与表结构；根路径可用配置 app.rag.code-graph-root-path 或环境变量 CODE_GRAPH_ROOT_PATH 指定。 */
    public SQLiteSymbolGraphStore(
            @Value("${app.rag.code-graph-root-path:${CODE_GRAPH_ROOT_PATH:data/code-graph}}") String rootPath) {
        try {
            Path root = Path.of(rootPath).toAbsolutePath().normalize();
            Files.createDirectories(root);
            this.jdbcUrl = "jdbc:sqlite:" + root.resolve("code-graph.db");
            initialize();
        }
        catch (IOException | SQLException exception) {
            throw new IllegalStateException("Unable to initialize code graph store", exception);
        }
    }

    /** 以事务方式替换项目在指定 commit 下的完整快照：先删除旧数据，再写入快照、符号与解析后的调用关系；任一失败则回滚。 */
    public void replaceSnapshot(CodeScanner.ScanResult result) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                deleteSnapshot(connection, result.projectId(), result.commitSha());
                insertSnapshot(connection, result);
                insertSymbols(connection, result.symbols());
                insertRelations(connection, resolve(result));
                connection.commit();
            }
            catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to replace code graph snapshot", exception);
        }
    }

    /** 返回项目最近一次索引的 commit SHA；无快照时返回 null。 */
    public String latestCommit(String projectId) {
        String sql = "select commit_sha from code_graph_snapshot where project_id=? order by indexed_at desc limit 1";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to read graph snapshot", exception);
        }
    }

    /** 按全限定名或简单名查找符号，全限定名精确命中排在前面，结果按文件路径与起始行排序。 */
    public List<CodeSymbol> findSymbols(String projectId, String commitSha, String name, int limit) {
        String sql = """
                select * from code_symbol where project_id=? and commit_sha=?
                and (qualified_name=? or simple_name=?)
                order by case when qualified_name=? then 0 else 1 end, file_path, start_line limit ?
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            statement.setString(2, commitSha);
            statement.setString(3, name);
            statement.setString(4, simpleName(name));
            statement.setString(5, name);
            statement.setInt(6, limit);
            try (ResultSet result = statement.executeQuery()) {
                return symbols(result);
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to find graph symbols", exception);
        }
    }

    /** 返回指定 commit 下位于变更文件列表中的全部符号（commit 影响分析的起点）。 */
    public List<CodeSymbol> symbolsByFiles(String projectId, String commitSha, List<String> files, int limit) {
        if (files.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(files.size(), "?"));
        String sql = "select * from code_symbol where project_id=? and commit_sha=? and file_path in ("
                + placeholders + ") order by file_path,start_line limit ?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            statement.setString(2, commitSha);
            int index = 3;
            for (String file : files) statement.setString(index++, file);
            statement.setInt(index, limit);
            try (ResultSet result = statement.executeQuery()) {
                return symbols(result);
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to find changed symbols", exception);
        }
    }

    /**
     * 精确符号查找：类名与方法名同时匹配（类符号与方法符号同文件，且方法限定名以「类限定名.方法名」结尾，
     * 保证方法确实属于目标类——同一文件存在内部类/多个类时不会把 OuterB.foo 当成 OuterA.foo）。
     * filePath 非空时追加路径过滤（精确或后缀匹配，后缀比较为确定性字符串比较，
     * 不使用 LIKE——避免 `_`/`%` 被当作通配符误命中），多模块同名类/同名方法场景下按查询中的显式路径区分。
     * 同名重载会返回多行（按文件路径与起始行稳定排序），由调用方决定置顶策略。
     */
    public List<CodeSymbol> findExactSymbols(String projectId, String commitSha, String className,
                                             String symbolName, String filePath, int limit) {
        String pathClause = filePath == null || filePath.isBlank()
                ? ""
                : " and (s.file_path=? or substr(s.file_path, -length(?)) = ?)";
        String sql = """
                select s.* from code_symbol s
                join code_symbol c on c.project_id=s.project_id and c.commit_sha=s.commit_sha
                  and c.file_path=s.file_path and c.kind in ('class','interface','enum','record')
                  and s.qualified_name = c.qualified_name || '.' || s.simple_name
                where s.project_id=? and s.commit_sha=? and s.simple_name=? and c.simple_name=?
                  and s.kind not in ('class','interface','enum','record')
                """ + pathClause + """
                order by s.file_path, s.start_line limit ?
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, projectId);
            statement.setString(index++, commitSha);
            statement.setString(index++, symbolName);
            statement.setString(index++, className);
            if (!pathClause.isBlank()) {
                statement.setString(index++, filePath);
                statement.setString(index++, filePath);
                statement.setString(index++, filePath);
            }
            statement.setInt(index, limit);
            try (ResultSet result = statement.executeQuery()) {
                return symbols(result);
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to find exact graph symbols", exception);
        }
    }

    /**
     * 把查询中的（可能不完整的）文件路径解析为符号库中的真实完整路径：
     * 精确匹配或确定性后缀匹配（不使用 LIKE，`_`/`%` 不作为通配符），按文件路径稳定排序。
     * 用于类名限定召回在 Qdrant 侧需要完整 filePath 精确匹配的场景。
     */
    public List<String> resolveFilePaths(String projectId, String commitSha, String filePathQuery, int limit) {
        String sql = """
                select distinct file_path from code_symbol
                where project_id=? and commit_sha=? and (file_path=? or substr(file_path, -length(?)) = ?)
                order by file_path limit ?
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            statement.setString(2, commitSha);
            statement.setString(3, filePathQuery);
            statement.setString(4, filePathQuery);
            statement.setString(5, filePathQuery);
            statement.setInt(6, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<String> paths = new ArrayList<>();
                while (result.next()) {
                    paths.add(result.getString(1));
                }
                return paths;
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to resolve graph file paths", exception);
        }
    }

    /**
     * 返回指定类名的类符号（含接口/枚举）所在文件路径列表，用于类名限定召回。
     * 同名类分布在多个文件时按文件路径稳定排序。
     */
    public List<String> classFilePaths(String projectId, String commitSha, String className, int limit) {
        String sql = """
                select distinct file_path from code_symbol
                where project_id=? and commit_sha=? and simple_name=? and kind in ('class','interface','enum','record')
                order by file_path limit ?
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            statement.setString(2, commitSha);
            statement.setString(3, className);
            statement.setInt(4, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<String> paths = new ArrayList<>();
                while (result.next()) paths.add(result.getString(1));
                return paths;
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to find class file paths", exception);
        }
    }

    /** 返回符号的入向（inbound=true，谁调用它）或出向（inbound=false，它调用谁）调用关系。 */
    public List<CodeRelation> relations(String projectId, String commitSha, String symbolId,
                                        boolean inbound, int limit) {
        String column = inbound ? "callee_symbol_id" : "caller_symbol_id";
        String sql = "select * from code_relation where project_id=? and commit_sha=? and " + column
                + "=? order by line,id limit ?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            statement.setString(2, commitSha);
            statement.setString(3, symbolId);
            statement.setInt(4, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<CodeRelation> relations = new ArrayList<>();
                while (result.next()) relations.add(relation(result));
                return relations;
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to traverse graph", exception);
        }
    }

    /** 返回指定 commit 下未解析（UNRESOLVED）的调用关系，用于暴露索引盲区。 */
    public List<CodeRelation> unresolved(String projectId, String commitSha, int limit) {
        String sql = """
                select * from code_relation where project_id=? and commit_sha=? and resolution='UNRESOLVED'
                order by file_path,line limit ?
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            statement.setString(2, commitSha);
            statement.setInt(3, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<CodeRelation> relations = new ArrayList<>();
                while (result.next()) relations.add(relation(result));
                return relations;
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to read unresolved graph calls", exception);
        }
    }

    /** 按符号 ID 读取符号；不存在时返回 null。 */
    public CodeSymbol symbolById(String projectId, String commitSha, String id) {
        String sql = "select * from code_symbol where project_id=? and commit_sha=? and id=?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            statement.setString(2, commitSha);
            statement.setString(3, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? symbol(result) : null;
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to read graph symbol", exception);
        }
    }

    /**
     * 将扫描得到的调用点解析为调用关系，按置信度依次尝试：
     * 全限定名唯一匹配（EXACT）→ 同文件简单名唯一匹配（SAME_FILE）→ 全库简单名唯一匹配（HEURISTIC）→ 未解析（UNRESOLVED）。
     * 调用关系 ID 由调用点各字段与解析结果哈希生成，保证稳定去重。
     */
    private List<CodeRelation> resolve(CodeScanner.ScanResult result) {
        Map<String, List<CodeSymbol>> qualified = new HashMap<>();
        Map<String, List<CodeSymbol>> simple = new HashMap<>();
        for (CodeSymbol symbol : result.symbols()) {
            qualified.computeIfAbsent(symbol.qualifiedName(), ignored -> new ArrayList<>()).add(symbol);
            simple.computeIfAbsent(symbol.simpleName(), ignored -> new ArrayList<>()).add(symbol);
        }
        Map<String, CodeRelation> relations = new LinkedHashMap<>();
        for (CodeCall call : result.calls()) {
            CodeSymbol match = unique(qualified.get(call.targetName()));
            CodeRelation.Resolution resolution = match == null ? null : CodeRelation.Resolution.EXACT;
            if (match == null) {
                List<CodeSymbol> sameFile = simple.getOrDefault(simpleName(call.targetName()), List.of()).stream()
                        .filter(symbol -> symbol.filePath().equals(call.filePath())).toList();
                match = unique(sameFile);
                if (match != null) resolution = CodeRelation.Resolution.SAME_FILE;
            }
            if (match == null) {
                match = unique(simple.get(simpleName(call.targetName())));
                if (match != null) resolution = CodeRelation.Resolution.HEURISTIC;
            }
            if (resolution == null) resolution = CodeRelation.Resolution.UNRESOLVED;
            String callee = match == null ? null : match.id();
            String seed = String.join("\n", call.projectId(), call.commitSha(), call.callerSymbolId(),
                    String.valueOf(callee), call.targetName(), call.filePath(), String.valueOf(call.line()),
                    resolution.name());
            String relationId = UUID.nameUUIDFromBytes(
                    seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            relations.putIfAbsent(relationId, new CodeRelation(relationId, call.projectId(), call.commitSha(),
                    call.callerSymbolId(), callee, call.targetName(), call.filePath(), call.line(),
                    resolution, resolution.name()));
        }
        return List.copyOf(relations.values());
    }

    /** 列表恰有一个元素时返回该元素，否则返回 null（名称歧义时不猜测归属）。 */
    private CodeSymbol unique(List<CodeSymbol> symbols) {
        return symbols != null && symbols.size() == 1 ? symbols.get(0) : null;
    }

    /** 幂等建表与建索引（存在则跳过），失败时抛出 SQLException 由构造器包装。 */
    private void initialize() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    create table if not exists code_graph_snapshot(
                      project_id text not null, commit_sha text not null, indexed_at text not null,
                      languages text not null, primary key(project_id,commit_sha))
                    """);
            statement.executeUpdate("""
                    create table if not exists code_symbol(
                      id text primary key, project_id text not null, commit_sha text not null, language text not null,
                      kind text not null, qualified_name text not null, simple_name text not null,
                      file_path text not null, start_line integer not null, end_line integer not null,
                      entry_point integer not null, test_symbol integer not null)
                    """);
            statement.executeUpdate("""
                    create table if not exists code_relation(
                      id text primary key, project_id text not null, commit_sha text not null,
                      caller_symbol_id text not null, callee_symbol_id text, target_name text not null,
                      file_path text not null, line integer not null, resolution text not null, evidence text not null)
                    """);
            statement.executeUpdate("create index if not exists idx_symbol_scope_name on code_symbol(project_id,commit_sha,simple_name)");
            statement.executeUpdate("create index if not exists idx_symbol_scope_file on code_symbol(project_id,commit_sha,file_path)");
            statement.executeUpdate("create index if not exists idx_relation_caller on code_relation(project_id,commit_sha,caller_symbol_id)");
            statement.executeUpdate("create index if not exists idx_relation_callee on code_relation(project_id,commit_sha,callee_symbol_id)");
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    /** 删除项目在指定 commit 下的全部调用关系、符号与快照记录（用于替换前清理）。 */
    private void deleteSnapshot(Connection connection, String project, String commit) throws SQLException {
        for (String table : List.of("code_relation", "code_symbol", "code_graph_snapshot")) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "delete from " + table + " where project_id=? and commit_sha=?")) {
                statement.setString(1, project);
                statement.setString(2, commit);
                statement.executeUpdate();
            }
        }
    }

    /** 写入快照元数据，languages 为快照中符号语言的去重排序拼接。 */
    private void insertSnapshot(Connection connection, CodeScanner.ScanResult result) throws SQLException {
        String languages = result.symbols().stream().map(CodeSymbol::language).distinct().sorted()
                .collect(java.util.stream.Collectors.joining(","));
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into code_graph_snapshot(project_id,commit_sha,indexed_at,languages) values(?,?,?,?)")) {
            statement.setString(1, result.projectId());
            statement.setString(2, result.commitSha());
            statement.setString(3, Instant.now().toString());
            statement.setString(4, languages);
            statement.executeUpdate();
        }
    }

    /** 批量写入符号。 */
    private void insertSymbols(Connection connection, List<CodeSymbol> symbols) throws SQLException {
        String sql = "insert into code_symbol values(?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (CodeSymbol symbol : symbols) {
                statement.setString(1, symbol.id());
                statement.setString(2, symbol.projectId());
                statement.setString(3, symbol.commitSha());
                statement.setString(4, symbol.language());
                statement.setString(5, symbol.kind());
                statement.setString(6, symbol.qualifiedName());
                statement.setString(7, symbol.simpleName());
                statement.setString(8, symbol.filePath());
                statement.setInt(9, symbol.startLine());
                statement.setInt(10, symbol.endLine());
                statement.setBoolean(11, symbol.entryPoint());
                statement.setBoolean(12, symbol.testSymbol());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** 批量写入调用关系。 */
    private void insertRelations(Connection connection, List<CodeRelation> relations) throws SQLException {
        String sql = "insert into code_relation values(?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (CodeRelation relation : relations) {
                statement.setString(1, relation.id());
                statement.setString(2, relation.projectId());
                statement.setString(3, relation.commitSha());
                statement.setString(4, relation.callerSymbolId());
                statement.setString(5, relation.calleeSymbolId());
                statement.setString(6, relation.targetName());
                statement.setString(7, relation.filePath());
                statement.setInt(8, relation.line());
                statement.setString(9, relation.resolution().name());
                statement.setString(10, relation.evidence());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private List<CodeSymbol> symbols(ResultSet result) throws SQLException {
        List<CodeSymbol> symbols = new ArrayList<>();
        while (result.next()) symbols.add(symbol(result));
        return symbols;
    }

    private CodeSymbol symbol(ResultSet result) throws SQLException {
        return new CodeSymbol(result.getString("id"), result.getString("project_id"),
                result.getString("commit_sha"), result.getString("language"), result.getString("kind"),
                result.getString("qualified_name"), result.getString("simple_name"), result.getString("file_path"),
                result.getInt("start_line"), result.getInt("end_line"),
                result.getBoolean("entry_point"), result.getBoolean("test_symbol"));
    }

    private CodeRelation relation(ResultSet result) throws SQLException {
        return new CodeRelation(result.getString("id"), result.getString("project_id"),
                result.getString("commit_sha"), result.getString("caller_symbol_id"),
                result.getString("callee_symbol_id"), result.getString("target_name"),
                result.getString("file_path"), result.getInt("line"),
                CodeRelation.Resolution.valueOf(result.getString("resolution")), result.getString("evidence"));
    }

    /** 取简单名：去掉最后的点号/冒号分隔符前缀，便于跨包同名匹配。 */
    private String simpleName(String value) {
        if (value == null) return "";
        int dot = Math.max(value.lastIndexOf('.'), value.lastIndexOf(':'));
        return dot < 0 ? value : value.substring(dot + 1);
    }
}
