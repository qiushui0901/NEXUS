package com.example.requirementrag.code;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.CodeIntelligenceResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeIntelligenceServiceTest {

    @Test
    void separatesCertainImpactFromUnresolvedCallsAndSuggestsRegressionEntry() throws Exception {
        SQLiteSymbolGraphStore store = new SQLiteSymbolGraphStore(
                Files.createTempDirectory("nexus-intelligence-").toString());
        CodeSymbol save = symbol("save", "demo.Service.save", "save", "src/Service.java", false);
        CodeSymbol run = symbol("run", "demo.Service.run", "run", "src/Service.java", true);
        store.replaceSnapshot(snapshot(List.of(save, run), List.of(
                call("resolved", run, "save", 12),
                call("dynamic", run, "runtimeDispatch", 13))));

        CodeIntelligenceService service = service(store, mock(GitDiffService.class));
        CodeIntelligenceResponse response = service.graph("demo", "save", "inbound", 99, 999);

        assertThat(response.availability()).isEqualTo("AVAILABLE");
        assertThat(response.certainImpact()).extracting(CodeSymbol::qualifiedName)
                .containsExactly("demo.Service.run");
        assertThat(response.inferredImpact()).isEmpty();
        assertThat(response.unresolvedCalls()).extracting(CodeRelation::targetName)
                .containsExactly("runtimeDispatch");
        assertThat(response.regressionSuggestions()).singleElement()
                .asString().contains("demo.Service.run").contains("src/Service.java");
    }

    @Test
    void commitImpactUsesTargetSnapshotAndDegradesToFileChangesWhenMissing() throws Exception {
        SQLiteSymbolGraphStore store = new SQLiteSymbolGraphStore(
                Files.createTempDirectory("nexus-commit-impact-").toString());
        CodeSymbol changed = symbol("changed", "demo.Service.save", "save", "src/Service.java", false);
        store.replaceSnapshot(snapshot(List.of(changed), List.of()));

        GitDiffService diffs = mock(GitDiffService.class);
        GitDiffService.GitDiffResult diff = new GitDiffService.GitDiffResult(
                GitDiffService.Availability.AVAILABLE, 1, 0, 1, 0, 0,
                1, 0, 0, List.of(new GitDiffService.GitFileChange(
                GitDiffService.ChangeType.MODIFIED, "src/Service.java", "src/Service.java")));
        when(diffs.diff("demo", "aaaaaaa", "abc")).thenReturn(diff);
        when(diffs.diff("demo", "aaaaaaa", "bbbbbbb")).thenReturn(diff);
        CodeIntelligenceService service = service(store, diffs);

        CodeIntelligenceResponse available = service.impactCommits(
                "demo", "aaaaaaa", "abc", 2, 50);
        assertThat(available.availability()).isEqualTo("AVAILABLE");
        assertThat(available.roots()).extracting(CodeSymbol::simpleName).containsExactly("save");

        CodeIntelligenceResponse degraded = service.impactCommits(
                "demo", "aaaaaaa", "bbbbbbb", 2, 50);
        assertThat(degraded.availability()).isEqualTo("NOT_AVAILABLE");
        assertThat(degraded.changedFiles()).containsExactly("src/Service.java");
        assertThat(degraded.warnings()).singleElement().asString().contains("Target commit graph");
    }

    private CodeIntelligenceService service(SQLiteSymbolGraphStore store, GitDiffService diffs) {
        RagProperties properties = mock(RagProperties.class);
        when(properties.code()).thenReturn(new RagProperties.Code(
                "demo", ".", "code", List.of(), List.of(), 1_000_000));
        return new CodeIntelligenceService(store, properties, diffs);
    }

    private CodeScanner.ScanResult snapshot(List<CodeSymbol> symbols, List<CodeCall> calls) {
        return new CodeScanner.ScanResult("demo", "abc", 1, List.of(), symbols, calls, List.of());
    }

    private CodeSymbol symbol(String id, String qualified, String simple, String path, boolean entry) {
        return new CodeSymbol(id, "demo", "abc", "java", "method", qualified, simple,
                path, 1, 20, entry, false);
    }

    private CodeCall call(String id, CodeSymbol caller, String target, int line) {
        return new CodeCall(id, "demo", "abc", "java", caller.id(), caller.qualifiedName(),
                target, caller.filePath(), line);
    }
}
