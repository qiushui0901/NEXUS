package com.example.requirementrag.code;

import com.example.requirementrag.model.CodeChunk;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 按语言描述符配置的通用 Tree-sitter 节点提取器：识别类型/可调用节点与调用点，产出符号、chunk 与调用记录。 */
final class TreeSitterLanguageAdapter {
    private static final int MAX_CHUNK_CHARS = 6_000;
    private static final int OVERLAP_CHARS = 400;
    private static final Pattern JAVA_PACKAGE = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;");

    private final CodeLanguage language;
    private final Supplier<TSLanguage> languageFactory;
    private final Set<String> typeNodes;
    private final Set<String> callableNodes;
    private final Set<String> callNodes;

    TreeSitterLanguageAdapter(CodeLanguage language, Supplier<TSLanguage> languageFactory,
                              Set<String> typeNodes, Set<String> callableNodes, Set<String> callNodes) {
        this.language = language;
        this.languageFactory = languageFactory;
        this.typeNodes = Set.copyOf(typeNodes);
        this.callableNodes = Set.copyOf(callableNodes);
        this.callNodes = Set.copyOf(callNodes);
    }

    /** 探测语言解析器可用性：设置语法并做一次空解析，失败时抛出 IllegalStateException。 */
    void verifyAvailable() {
        TSParser parser = new TSParser();
        if (!parser.setLanguage(languageFactory.get())) {
            throw new IllegalStateException("Tree-sitter rejected " + language.id() + " grammar");
        }
        parser.parseString(null, "");
    }

    /**
     * 解析单个文件：收集定义与调用点，产出按行排序的符号、带前置文档注释的代码 chunk 与调用记录；
     * 树语法有错时附加 PARTIAL_PARSE 诊断；未提取到任何符号时退化为整个文件级 chunk。
     */
    ParsedCodeFile parse(String projectId, String commitSha, String filePath, String source) {
        TSParser parser = new TSParser();
        if (!parser.setLanguage(languageFactory.get())) {
            throw new IllegalStateException("Tree-sitter rejected " + language.id() + " grammar");
        }
        TSTree tree = parser.parseString(null, source);
        TSNode root = tree.getRootNode();
        List<TSNode> definitions = new ArrayList<>();
        List<TSNode> callSites = new ArrayList<>();
        collect(root, definitions, callSites);

        String namespace = namespace(source);
        List<CodeSymbol> symbols = definitions.stream()
                .map(node -> symbol(projectId, commitSha, filePath, source, namespace, node))
                .filter(symbol -> !symbol.simpleName().isBlank())
                .sorted(Comparator.comparingInt(CodeSymbol::startLine))
                .toList();
        List<CodeChunk> chunks = new ArrayList<>();
        for (CodeSymbol symbol : symbols) {
            TSNode node = definitions.stream()
                    .filter(candidate -> candidate.getStartPoint().getRow() + 1 == symbol.startLine())
                    .findFirst().orElse(null);
            if (node != null) {
                String code = slice(source, node);
                String doc = precedingDocComment(node, source);
                String chunkText = doc.isBlank() ? code : doc + "\n" + code;
                chunks.addAll(chunks(symbol, chunkText));
            }
        }
        if (chunks.isEmpty()) {
            String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
            CodeSymbol file = symbolForRange(projectId, commitSha, filePath, "file", fileName, fileName,
                    1, Math.max(1, source.lines().toList().size()));
            chunks.addAll(chunks(file, source));
        }

        List<CodeCall> calls = new ArrayList<>();
        for (TSNode call : callSites) {
            String target = callTarget(call, source);
            CodeSymbol caller = containingCallable(symbols, call);
            if (!target.isBlank() && caller != null) {
                String seed = caller.id() + '\n' + target + '\n' + (call.getStartPoint().getRow() + 1);
                calls.add(new CodeCall(uuid(seed), projectId, commitSha, language.id(), caller.id(),
                        caller.qualifiedName(), target, filePath, call.getStartPoint().getRow() + 1));
            }
        }
        List<CodeScanDiagnostic> diagnostics = root.hasError()
                ? List.of(new CodeScanDiagnostic(language.id(), filePath, "PARTIAL_PARSE",
                "Tree-sitter reported syntax errors; recovered symbols are partial"))
                : List.of();
        return new ParsedCodeFile(chunks, symbols, calls, diagnostics);
    }

    /** 递归收集类型/可调用定义节点与调用点节点。 */
    private void collect(TSNode node, List<TSNode> definitions, List<TSNode> calls) {
        if (typeNodes.contains(node.getType()) || callableNodes.contains(node.getType())) {
            definitions.add(node);
        }
        if (callNodes.contains(node.getType())) {
            calls.add(node);
        }
        for (int index = 0; index < node.getNamedChildCount(); index++) {
            collect(node.getNamedChild(index), definitions, calls);
        }
    }

    /** 由定义节点构造符号：名称、kind、限定名（命名空间.所有者.名称）与行号范围。 */
    private CodeSymbol symbol(String projectId, String commitSha, String filePath, String source,
                              String namespace, TSNode node) {
        String name = nodeName(node, source);
        String kind = typeNodes.contains(node.getType()) ? typeKind(node.getType()) : callableKind(node.getType());
        String owner = ownerName(node, source);
        String qualified = join(namespace, owner, name);
        return symbolForRange(projectId, commitSha, filePath, kind, qualified, name,
                node.getStartPoint().getRow() + 1, node.getEndPoint().getRow() + 1);
    }

    /** 构造符号并判定入口点/测试符号特征：按名称与路径模式（main/controller/test 等）启发式标记。 */
    private CodeSymbol symbolForRange(String projectId, String commitSha, String filePath, String kind,
                                      String qualified, String name, int start, int end) {
        String seed = projectId + '\n' + commitSha + '\n' + language.id() + '\n' + filePath + '\n'
                + qualified + '\n' + kind + '\n' + start;
        boolean entry = name.matches("(?i)(main|handle|execute|run|start|apply).*")
                || filePath.matches("(?i).*(controller|handler|route|api).*");
        boolean test = filePath.matches("(?i).*(^|/)(test|tests)/.*")
                || name.matches("(?i)(test.*|.*Test)");
        return new CodeSymbol(uuid(seed), projectId, commitSha, language.id(), kind, qualified, name,
                filePath, start, Math.max(start, end), entry, test);
    }

    /** 将符号文本切分为不超过 MAX_CHUNK_CHARS 的 chunk，段间保留 OVERLAP_CHARS 重叠。 */
    private List<CodeChunk> chunks(CodeSymbol symbol, String text) {
        List<CodeChunk> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + MAX_CHUNK_CHARS);
            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end);
                if (newline > start + MAX_CHUNK_CHARS / 2) end = newline + 1;
            }
            String part = text.substring(start, end).strip();
            if (!part.isBlank()) {
                int startLine = symbol.startLine() + lineCount(text, 0, start);
                int endLine = startLine + lineCount(text, start, end);
                String hash = sha256(symbol.id() + '\n' + part);
                result.add(new CodeChunk(uuid(hash), symbol.projectId(), symbol.commitSha(), symbol.filePath(),
                        symbol.kind(), symbol.simpleName(), startLine, Math.max(startLine, endLine),
                        part, hash, language.id()));
            }
            if (end >= text.length()) break;
            start = Math.max(start + 1, end - OVERLAP_CHARS);
        }
        return result;
    }

    /** 取节点声明的名称：优先 name/declarator 字段，回退到节点内最后一个标识符。 */
    private String nodeName(TSNode node, String source) {
        for (String field : List.of("name", "declarator")) {
            TSNode value = node.getChildByFieldName(field);
            if (value != null && !value.isNull()) {
                String text = lastIdentifier(value, source);
                if (!text.isBlank()) return text;
            }
        }
        return lastIdentifier(node, source);
    }

    /** 向上查找最近的类型节点作为符号所有者（用于构造限定名）。 */
    private String ownerName(TSNode node, String source) {
        TSNode parent = node.getParent();
        while (parent != null && !parent.isNull()) {
            if (typeNodes.contains(parent.getType())) return nodeName(parent, source);
            parent = parent.getParent();
        }
        return "";
    }

    /** 提取调用点的目标名：优先 name/function/constructor/type 字段，回退到节点内最后一个标识符。 */
    private String callTarget(TSNode node, String source) {
        for (String field : List.of("name", "function", "constructor", "type")) {
            TSNode value = node.getChildByFieldName(field);
            if (value != null && !value.isNull()) {
                String text = lastIdentifier(value, source);
                if (!text.isBlank()) return text;
            }
        }
        return lastIdentifier(node, source);
    }

    /** 递归取节点内最后一个标识符文本，作为名称的兜底提取方式。 */
    private String lastIdentifier(TSNode node, String source) {
        String type = node.getType();
        if (type.contains("identifier") || type.equals("type_identifier")) return slice(source, node).strip();
        for (int index = node.getNamedChildCount() - 1; index >= 0; index--) {
            String found = lastIdentifier(node.getNamedChild(index), source);
            if (!found.isBlank()) return found;
        }
        return "";
    }

    /** 找包含该调用点的行号范围最小的方法/函数/构造器符号作为调用方。 */
    private CodeSymbol containingCallable(List<CodeSymbol> symbols, TSNode call) {
        int line = call.getStartPoint().getRow() + 1;
        return symbols.stream()
                .filter(symbol -> symbol.kind().equals("method") || symbol.kind().equals("function")
                        || symbol.kind().equals("constructor"))
                .filter(symbol -> symbol.startLine() <= line && symbol.endLine() >= line)
                .min(Comparator.comparingInt(symbol -> symbol.endLine() - symbol.startLine()))
                .orElse(null);
    }

    /** Java/Kotlin 提取 package 声明作为命名空间，其他语言无命名空间。 */
    private String namespace(String source) {
        if (language == CodeLanguage.JAVA || language == CodeLanguage.KOTLIN) {
            Matcher matcher = JAVA_PACKAGE.matcher(source);
            return matcher.find() ? matcher.group(1) : "";
        }
        return "";
    }

    /** 类型节点 kind：interface/enum/class，按节点类型名包含关系判断。 */
    private String typeKind(String nodeType) {
        if (nodeType.contains("interface")) return "interface";
        if (nodeType.contains("enum")) return "enum";
        return "class";
    }

    /** 可调用节点 kind：constructor；Java/Kotlin 记为 method，其余语言记为 function。 */
    private String callableKind(String nodeType) {
        if (nodeType.contains("constructor")) return "constructor";
        return language == CodeLanguage.JAVA || language == CodeLanguage.KOTLIN ? "method" : "function";
    }

    /** 按节点的字节区间（UTF-8）切取源码文本。 */
    private String slice(String source, TSNode node) {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        int start = Math.max(0, Math.min(node.getStartByte(), bytes.length));
        int end = Math.max(start, Math.min(node.getEndByte(), bytes.length));
        return new String(bytes, start, end - start, StandardCharsets.UTF_8);
    }

    /** 返回紧邻符号声明上方的文档注释文本；相隔超过一行空白则不视为该符号的文档。 */
    private String precedingDocComment(TSNode node, String source) {
        TSNode previous = node.getPrevNamedSibling();
        if (previous == null || previous.isNull() || !previous.getType().contains("comment")) {
            return "";
        }
        int commentEndLine = previous.getEndPoint().getRow() + 1;
        int nodeStartLine = node.getStartPoint().getRow() + 1;
        if (nodeStartLine - commentEndLine > 2) {
            return "";
        }
        return slice(source, previous);
    }

    private int lineCount(String value, int start, int end) {
        int count = 0;
        for (int i = start; i < end; i++) if (value.charAt(i) == '\n') count++;
        return count;
    }

    private String join(String... parts) {
        return java.util.Arrays.stream(parts).filter(part -> part != null && !part.isBlank())
                .reduce((left, right) -> left + "." + right).orElse("");
    }

    private String uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
