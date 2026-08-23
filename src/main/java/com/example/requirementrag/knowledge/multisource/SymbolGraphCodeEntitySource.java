package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.code.CodeSymbol;
import com.example.requirementrag.code.SQLiteSymbolGraphStore;
import com.example.requirementrag.knowledge.multisource.KnowledgeGraphBuildService.CodeEntityInput;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 从代码符号图枚举项目符号，并入跨源总实体关系图（CODE 实体）。
 *
 * <p>取最近一次扫描的 commit，导出类/方法/字段等符号；符号名取限定名（缺失时简单名）。
 */
@Component
public class SymbolGraphCodeEntitySource implements KnowledgeGraphBuildService.CodeEntitySource {

    /** 知识项目 ID -> 代码符号项目 ID 映射（当前 immortal 知识库对应 immortal-game-service）。 */
    private static final Map<String, String> CODE_PROJECT_MAPPINGS = Map.of(
            "immortal", "immortal-game-service"
    );

    private final SQLiteSymbolGraphStore symbolGraphStore;

    public SymbolGraphCodeEntitySource(SQLiteSymbolGraphStore symbolGraphStore) {
        this.symbolGraphStore = symbolGraphStore;
    }

    @Override
    public List<CodeEntityInput> load(String projectId, String version) {
        String codeProjectId = CODE_PROJECT_MAPPINGS.getOrDefault(projectId, projectId);
        String commitSha = symbolGraphStore.latestCommit(codeProjectId);
        if (commitSha == null || commitSha.isBlank()) {
            return List.of();
        }
        return symbolGraphStore.allSymbols(codeProjectId, commitSha, 20_000).stream()
                .map(this::toInput)
                .toList();
    }

    private CodeEntityInput toInput(CodeSymbol symbol) {
        String name = symbol.simpleName() != null && !symbol.simpleName().isBlank()
                ? symbol.simpleName() : symbol.qualifiedName();
        String kind = symbol.kind() == null ? "SYMBOL" : symbol.kind().toUpperCase(java.util.Locale.ROOT);
        String summary = symbol.filePath() + ":" + symbol.startLine() + "-" + symbol.endLine();
        String evidenceId = "code:" + symbol.projectId() + ":" + symbol.commitSha() + ":" + symbol.id();
        return new CodeEntityInput(name, kind, summary, evidenceId);
    }
}