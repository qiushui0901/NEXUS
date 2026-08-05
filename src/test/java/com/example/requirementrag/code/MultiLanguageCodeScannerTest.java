package com.example.requirementrag.code;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.CodeChunk;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MultiLanguageCodeScannerTest {

    @Test
    void extractsSymbolsAndCallsFromRequiredLanguagesAndTypescript() throws Exception {
        Path root = Files.createTempDirectory("nexus-multilang-");
        Files.writeString(root.resolve("Hero.java"), """
                package demo;
                class Hero {
                    void save() {}
                    void train() { save(); }
                }
                """);
        Files.writeString(root.resolve("hero.go"), """
                package demo
                func save() {}
                func train() { save() }
                """);
        Files.writeString(root.resolve("hero.py"), """
                def save():
                    pass
                def train():
                    save()
                """);
        Files.writeString(root.resolve("hero.ts"), """
                function save() {}
                function train() { save(); }
                """);

        MultiLanguageCodeScanner scanner = new MultiLanguageCodeScanner(new CodeLanguageRegistry());
        CodeScanner.ScanResult result = scanner.scan(new RagProperties.Code(
                "demo", root.toString(), "code", List.of(), List.of(), 1_000_000));

        Map<String, Long> chunksByLanguage = result.chunks().stream()
                .collect(Collectors.groupingBy(chunk -> chunk.language(), Collectors.counting()));
        assertThat(chunksByLanguage).containsKeys("java", "go", "python", "typescript");
        assertThat(result.symbols()).extracting(CodeSymbol::simpleName)
                .contains("Hero", "train", "save");
        assertThat(result.calls()).extracting(CodeCall::targetName).contains("save");
        assertThat(result.diagnostics()).noneMatch(diagnostic -> diagnostic.code().equals("PARSE_FAILED"));
    }

    @Test
    void prependsAdjacentDocCommentToSymbolChunkText() throws Exception {
        Path root = Files.createTempDirectory("nexus-doccomment-");
        Files.writeString(root.resolve("Hero.java"), """
                package demo;
                class Hero {
                    /**
                     * 同步撤回：五分钟后生效。
                     */
                    void syncRevocation() {}
                    void plain() {}
                }
                """);

        MultiLanguageCodeScanner scanner = new MultiLanguageCodeScanner(new CodeLanguageRegistry());
        CodeScanner.ScanResult result = scanner.scan(new RagProperties.Code(
                "demo", root.toString(), "code", List.of(), List.of(), 1_000_000));

        CodeChunk documented = result.chunks().stream()
                .filter(chunk -> "syncRevocation".equals(chunk.symbolName()))
                .findFirst().orElseThrow();
        assertThat(documented.text()).startsWith("/**")
                .contains("同步撤回：五分钟后生效。")
                .contains("void syncRevocation() {}");
        CodeChunk plain = result.chunks().stream()
                .filter(chunk -> "plain".equals(chunk.symbolName()))
                .findFirst().orElseThrow();
        assertThat(plain.text()).doesNotContain("同步撤回");
    }
}
