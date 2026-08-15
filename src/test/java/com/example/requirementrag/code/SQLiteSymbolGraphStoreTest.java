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

    @Test
    void deduplicatesIdenticalCallsAndKeepsDistinctRelationsWhenCallIdsCollide() throws Exception {
        SQLiteSymbolGraphStore store = new SQLiteSymbolGraphStore(
                Files.createTempDirectory("nexus-graph-duplicate-call-").toString());
        CodeSymbol caller = symbol("caller", "demo.Caller.run", "run", 10, 20);
        CodeSymbol callee = symbol("callee", "demo.Caller.save", "save", 2, 5);
        CodeCall resolved = new CodeCall("same-call-id", "demo", "abc", "java", caller.id(),
                caller.qualifiedName(), "save", "src/Caller.java", 12);
        CodeCall identical = new CodeCall("same-call-id", "demo", "abc", "java", caller.id(),
                caller.qualifiedName(), "save", "src/Caller.java", 12);
        CodeCall distinct = new CodeCall("same-call-id", "demo", "abc", "java", caller.id(),
                caller.qualifiedName(), "dynamicTarget", "src/Caller.java", 13);

        store.replaceSnapshot(new CodeScanner.ScanResult("demo", "abc", 1, List.of(),
                List.of(caller, callee), List.of(resolved, identical, distinct), List.of()));

        assertThat(store.relations("demo", "abc", caller.id(), false, 10))
                .extracting(CodeRelation::targetName)
                .containsExactlyInAnyOrder("save", "dynamicTarget");
    }

    @Test
    void findsExactSymbolsByClassNameAndSymbolNameInTheSameFile() throws Exception {
        SQLiteSymbolGraphStore store = new SQLiteSymbolGraphStore(
                Files.createTempDirectory("nexus-graph-exact-").toString());
        CodeSymbol clazz = new CodeSymbol("cls-1", "demo", "abc", "java", "class",
                "demo.VipService", "VipService", "src/VipService.java", 3, 40, false, false);
        CodeSymbol method = new CodeSymbol("m-1", "demo", "abc", "java", "method",
                "demo.VipService.receiveGift", "receiveGift", "src/VipService.java", 8, 12, false, false);
        CodeSymbol otherClass = new CodeSymbol("cls-2", "demo", "abc", "java", "class",
                "demo.OtherService", "OtherService", "src/OtherService.java", 3, 20, false, false);
        CodeSymbol sameNameOtherFile = new CodeSymbol("m-2", "demo", "abc", "java", "method",
                "demo.OtherService.receiveGift", "receiveGift", "src/OtherService.java", 5, 8, false, false);
        store.replaceSnapshot(new CodeScanner.ScanResult("demo", "abc", 2, List.of(),
                List.of(clazz, method, otherClass, sameNameOtherFile), List.of(), List.of()));

        assertThat(store.findExactSymbols("demo", "abc", "VipService", "receiveGift", null, 10))
                .extracting(CodeSymbol::id).containsExactly("m-1");
        assertThat(store.findExactSymbols("demo", "abc", "VipService", "missing", null, 10)).isEmpty();
        assertThat(store.findExactSymbols("demo", "abc", "Missing", "receiveGift", null, 10)).isEmpty();
    }

    @Test
    void findsExactSymbolsKeepingStableOrderAcrossOverloads() throws Exception {
        SQLiteSymbolGraphStore store = new SQLiteSymbolGraphStore(
                Files.createTempDirectory("nexus-graph-overload-").toString());
        CodeSymbol clazz = new CodeSymbol("cls-1", "demo", "abc", "java", "class",
                "demo.ItemService", "ItemService", "src/ItemService.java", 3, 60, false, false);
        CodeSymbol first = new CodeSymbol("m-1", "demo", "abc", "java", "method",
                "demo.ItemService.canAdd", "canAdd", "src/ItemService.java", 8, 12, false, false);
        CodeSymbol second = new CodeSymbol("m-2", "demo", "abc", "java", "method",
                "demo.ItemService.canAdd", "canAdd", "src/ItemService.java", 30, 34, false, false);
        store.replaceSnapshot(new CodeScanner.ScanResult("demo", "abc", 1, List.of(),
                List.of(clazz, first, second), List.of(), List.of()));

        assertThat(store.findExactSymbols("demo", "abc", "ItemService", "canAdd", null, 10))
                .extracting(CodeSymbol::id).containsExactly("m-1", "m-2");
    }

    @Test
    void requiresMethodToBelongToTheQueriedClassWhenMultipleClassesShareOneFile() throws Exception {
        SQLiteSymbolGraphStore store = new SQLiteSymbolGraphStore(
                Files.createTempDirectory("nexus-graph-multiclass-").toString());
        // OuterA 与 OuterB 同文件；foo 是 OuterB 的方法，查询 OuterA.foo 不得命中
        CodeSymbol outerA = new CodeSymbol("cls-a", "demo", "abc", "java", "class",
                "demo.OuterA", "OuterA", "src/OuterA.java", 3, 20, false, false);
        CodeSymbol outerB = new CodeSymbol("cls-b", "demo", "abc", "java", "class",
                "demo.OuterB", "OuterB", "src/OuterA.java", 22, 40, false, false);
        CodeSymbol fooOfB = new CodeSymbol("m-b", "demo", "abc", "java", "method",
                "demo.OuterB.foo", "foo", "src/OuterA.java", 25, 28, false, false);
        store.replaceSnapshot(new CodeScanner.ScanResult("demo", "abc", 1, List.of(),
                List.of(outerA, outerB, fooOfB), List.of(), List.of()));

        assertThat(store.findExactSymbols("demo", "abc", "OuterA", "foo", null, 10)).isEmpty();
        assertThat(store.findExactSymbols("demo", "abc", "OuterB", "foo", null, 10))
                .extracting(CodeSymbol::id).containsExactly("m-b");
    }

    @Test
    void filtersExactSymbolsByExplicitFilePathForSameNameClassesAcrossModules() throws Exception {
        SQLiteSymbolGraphStore store = new SQLiteSymbolGraphStore(
                Files.createTempDirectory("nexus-graph-pathfilter-").toString());
        CodeSymbol clazzA = new CodeSymbol("cls-a", "demo", "abc", "java", "class",
                "demo.VipService", "VipService", "module-a/src/VipService.java", 3, 20, false, false);
        CodeSymbol methodA = new CodeSymbol("m-a", "demo", "abc", "java", "method",
                "demo.VipService.receiveGift", "receiveGift", "module-a/src/VipService.java", 8, 12, false, false);
        CodeSymbol clazzB = new CodeSymbol("cls-b", "demo", "abc", "java", "class",
                "demo.VipService", "VipService", "module-b/src/VipService.java", 3, 20, false, false);
        CodeSymbol methodB = new CodeSymbol("m-b", "demo", "abc", "java", "method",
                "demo.VipService.receiveGift", "receiveGift", "module-b/src/VipService.java", 8, 12, false, false);
        store.replaceSnapshot(new CodeScanner.ScanResult("demo", "abc", 2, List.of(),
                List.of(clazzA, methodA, clazzB, methodB), List.of(), List.of()));

        // 无路径：两处同名符号都返回（稳定顺序）；带显式路径：只返回对应文件（精确与后缀均可）
        assertThat(store.findExactSymbols("demo", "abc", "VipService", "receiveGift", null, 10))
                .extracting(CodeSymbol::id).containsExactly("m-a", "m-b");
        assertThat(store.findExactSymbols("demo", "abc", "VipService", "receiveGift",
                "module-b/src/VipService.java", 10))
                .extracting(CodeSymbol::id).containsExactly("m-b");
        assertThat(store.findExactSymbols("demo", "abc", "VipService", "receiveGift",
                "src/VipService.java", 10))
                .extracting(CodeSymbol::id).containsExactly("m-a", "m-b");
        assertThat(store.findExactSymbols("demo", "abc", "VipService", "receiveGift",
                "unknown/VipService.java", 10)).isEmpty();
    }

    @Test
    void pathFilterTreatsUnderscoresLiterallyInsteadOfAsLikeWildcards() throws Exception {
        SQLiteSymbolGraphStore store = new SQLiteSymbolGraphStore(
                Files.createTempDirectory("nexus-graph-wildcard-").toString());
        CodeSymbol clazzDash = new CodeSymbol("cls-1", "demo", "abc", "java", "class",
                "demo.VipService", "VipService", "module-a/src/VipService.java", 3, 20, false, false);
        CodeSymbol methodDash = new CodeSymbol("m-1", "demo", "abc", "java", "method",
                "demo.VipService.receiveGift", "receiveGift", "module-a/src/VipService.java", 8, 12, false, false);
        CodeSymbol clazzUnderscore = new CodeSymbol("cls-2", "demo", "abc", "java", "class",
                "demo.VipService", "VipService", "module_a/src/VipService.java", 3, 20, false, false);
        CodeSymbol methodUnderscore = new CodeSymbol("m-2", "demo", "abc", "java", "method",
                "demo.VipService.receiveGift", "receiveGift", "module_a/src/VipService.java", 8, 12, false, false);
        store.replaceSnapshot(new CodeScanner.ScanResult("demo", "abc", 2, List.of(),
                List.of(clazzDash, methodDash, clazzUnderscore, methodUnderscore), List.of(), List.of()));

        // `_` 必须按字面量比较：module_a 的路径不得误命中 module-a
        assertThat(store.findExactSymbols("demo", "abc", "VipService", "receiveGift",
                "module_a/src/VipService.java", 10))
                .extracting(CodeSymbol::id).containsExactly("m-2");
        assertThat(store.resolveFilePaths("demo", "abc", "module_a/src/VipService.java", 10))
                .containsExactly("module_a/src/VipService.java");
        // 后缀解析返回真实完整路径（含 module 前缀）
        assertThat(store.resolveFilePaths("demo", "abc", "src/VipService.java", 10))
                .containsExactly("module-a/src/VipService.java", "module_a/src/VipService.java");
        assertThat(store.resolveFilePaths("demo", "abc", "unknown/VipService.java", 10)).isEmpty();
    }

    @Test
    void listsClassFilePathsForClassScopedRecall() throws Exception {
        SQLiteSymbolGraphStore store = new SQLiteSymbolGraphStore(
                Files.createTempDirectory("nexus-graph-classpaths-").toString());
        CodeSymbol clazz = new CodeSymbol("cls-1", "demo", "abc", "java", "class",
                "demo.FarmService", "FarmService", "src/FarmService.java", 3, 60, false, false);
        CodeSymbol method = new CodeSymbol("m-1", "demo", "abc", "java", "method",
                "demo.FarmService.dig", "dig", "src/FarmService.java", 8, 12, false, false);
        store.replaceSnapshot(new CodeScanner.ScanResult("demo", "abc", 1, List.of(),
                List.of(clazz, method), List.of(), List.of()));

        assertThat(store.classFilePaths("demo", "abc", "FarmService", 10))
                .containsExactly("src/FarmService.java");
        assertThat(store.classFilePaths("demo", "abc", "Missing", 10)).isEmpty();
    }

    private CodeSymbol symbol(String id, String qualified, String simple, int start, int end) {
        return new CodeSymbol(id, "demo", "abc", "java", "method", qualified, simple,
                "src/Caller.java", start, end, false, false);
    }
}
