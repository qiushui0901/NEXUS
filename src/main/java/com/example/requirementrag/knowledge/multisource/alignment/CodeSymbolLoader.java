package com.example.requirementrag.knowledge.multisource.alignment;

import com.example.requirementrag.code.CodeSymbol;
import com.example.requirementrag.code.SQLiteSymbolGraphStore;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.CodeSymbolView;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.LoadedCode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 代码符号加载器：把知识项目映射到代码符号图项目，读取最近一次 commit 的全部符号。
 *
 * <p>代码内部关系仍由现有代码图谱提供；本加载器只为跨源对齐提供轻量符号视图。
 */
@Component
public class CodeSymbolLoader {

    /** 知识项目 ID -> 代码符号项目 ID 映射（当前 immortal 知识库对应 immortal-game-service）。 */
    static final Map<String, String> CODE_PROJECT_MAPPINGS = Map.of(
            "immortal", "immortal-game-service"
    );

    private final SQLiteSymbolGraphStore symbolGraphStore;

    public CodeSymbolLoader(SQLiteSymbolGraphStore symbolGraphStore) {
        this.symbolGraphStore = symbolGraphStore;
    }

    /** 返回知识项目对应的代码项目 ID；未显式映射时视为同名。 */
    public String codeProjectId(String knowledgeProjectId) {
        return CODE_PROJECT_MAPPINGS.getOrDefault(knowledgeProjectId, knowledgeProjectId);
    }

    /** 加载最近一次索引 commit 下的符号；无代码快照时返回空集合。 */
    public LoadedCode load(String knowledgeProjectId) {
        String codeProjectId = codeProjectId(knowledgeProjectId);
        String commitSha = symbolGraphStore.latestCommit(codeProjectId);
        if (commitSha == null || commitSha.isBlank()) {
            return new LoadedCode(codeProjectId, null, List.of());
        }
        List<CodeSymbol> symbols = symbolGraphStore.allSymbols(codeProjectId, commitSha, 20_000);
        return new LoadedCode(codeProjectId, commitSha,
                symbols.stream().map(this::toView).toList());
    }

    /** 加载并建立按规范化简单名索引的符号集合。 */
    public Map<String, List<CodeSymbolView>> indexBySimpleName(LoadedCode loaded) {
        Map<String, List<CodeSymbolView>> index = new LinkedHashMap<>();
        for (CodeSymbolView symbol : loaded.symbols()) {
            String key = normalize(symbol.simpleName());
            if (key.isBlank()) continue;
            index.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(symbol);
        }
        return index;
    }

    private CodeSymbolView toView(CodeSymbol symbol) {
        return new CodeSymbolView(
                symbol.id(), symbol.projectId(), symbol.commitSha(), symbol.kind(),
                symbol.qualifiedName(), symbol.simpleName(), symbol.filePath(),
                symbol.startLine(), symbol.endLine(), symbol.entryPoint(), symbol.testSymbol());
    }

    /** 规范化名称：小写、去符号，用于确定性匹配（与实体图 normalize 保持一致的方向）。 */
    public static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s|｜:：（）()\\[\\]【】、，,。.;；/\\\\_\\-]+", "");
    }
}