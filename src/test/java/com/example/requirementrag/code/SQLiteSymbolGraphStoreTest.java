package com.example.requirementrag.code;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SQLiteSymbolGraphStoreTest {

    @Test
    void resolvesSameFileCallsAndKeepsUnresolvedCallsVisible() throws Exception {
        SQLiteSymbolGraphStore store = new SQLiteSymbolGraphStore(
                Files.createTempDirectory("nexus-graph-").toString());
        CodeSymbol caller = symbol("caller", "demo.Caller.run", "run", 10, 20);
        CodeSymbol callee = symbol("callee", "demo.Caller.save", "save", 2, 5);
        CodeCall resolved = new CodeCall("call-1", "demo", "abc", "java", caller.id(),
                caller.qualifiedName(), "save", "src/Caller.java", 12);
        CodeCall missing = new CodeCall("call-2", "demo", "abc", "java", caller.id(),
                caller.qualifiedName(), "dynamicTarget", "src/Caller.java", 13);

        store.replaceSnapshot(new CodeScanner.ScanResult("demo", "abc", 1, List.of(),
                List.of(caller, callee), List.of(resolved, missing), List.of()));

        assertThat(store.latestCommit("demo")).isEqualTo("abc");
        assertThat(store.relations("demo", "abc", caller.id(), false, 10))
                .anySatisfy(relation -> {
                    assertThat(relation.calleeSymbolId()).isEqualTo(callee.id());
                    assertThat(relation.resolution()).isEqualTo(CodeRelation.Resolution.SAME_FILE);
                });
        assertThat(store.unresolved("demo", "abc", 10))
                .extracting(CodeRelation::targetName).containsExactly("dynamicTarget");
    }

    @Test
    void isolatesProjectsAndCommitsAndRollsBackFailedReplacement() throws Exception {
        SQLiteSymbolGraphStore store = new SQLiteSymbolGraphStore(
                Files.createTempDirectory("nexus-graph-isolation-").toString());
        CodeSymbol original = symbol("original", "demo.Original.run", "run", 1, 5);
        store.replaceSnapshot(new CodeScanner.ScanResult("demo", "abc", 1, List.of(),
                List.of(original), List.of(), List.of()));
        CodeSymbol otherProject = new CodeSymbol("other", "other", "abc", "python", "function",
                "other.run", "run", "src/other.py", 1, 2, false, false);
        store.replaceSnapshot(new CodeScanner.ScanResult("other", "abc", 1, List.of(),
                List.of(otherProject), List.of(), List.of()));

        assertThat(store.findSymbols("demo", "abc", "run", 10))
                .extracting(CodeSymbol::id).containsExactly("original");
        assertThat(store.findSymbols("other", "abc", "run", 10))
                .extracting(CodeSymbol::id).containsExactly("other");

        CodeSymbol duplicate = symbol("duplicate", "demo.Duplicate.run", "run", 6, 9);
        assertThatThrownBy(() -> store.replaceSnapshot(new CodeScanner.ScanResult(
                "demo", "abc", 1, List.of(), List.of(duplicate, duplicate), List.of(), List.of())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.findSymbols("demo", "abc", "run", 10))
                .extracting(CodeSymbol::id).containsExactly("original");
    }

    @Test
    void replacementRemovesDeletedPathAndStoresRenamedPath() throws Exception {
        SQLiteSymbolGraphStore store = new SQLiteSymbolGraphStore(
                Files.createTempDirectory("nexus-graph-replace-").toString());
        CodeSymbol oldSymbol = symbol("old", "demo.Old.run", "run", 1, 5);
        store.replaceSnapshot(new CodeScanner.ScanResult("demo", "abc", 1, List.of(),
                List.of(oldSymbol), List.of(), List.of()));

        CodeSymbol renamed = new CodeSymbol("renamed", "demo", "abc", "java", "method",
                "demo.New.run", "run", "src/New.java", 1, 5, false, false);
        store.replaceSnapshot(new CodeScanner.ScanResult("demo", "abc", 1, List.of(),
                List.of(renamed), List.of(), List.of()));

        assertThat(store.symbolsByFiles("demo", "abc", List.of("src/Caller.java"), 10)).isEmpty();
        assertThat(store.symbolsByFiles("demo", "abc", List.of("src/New.java"), 10))
                .extracting(CodeSymbol::id).containsExactly("renamed");
    }

    private CodeSymbol symbol(String id, String qualified, String simple, int start, int end) {
        return new CodeSymbol(id, "demo", "abc", "java", "method", qualified, simple,
                "src/Caller.java", start, end, false, false);
    }
}
