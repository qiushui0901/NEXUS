package com.example.requirementrag.code;

import com.example.requirementrag.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaCodeScannerTest {

    @Test
    void extractsClassAndMethodChunks() throws Exception {
        Path root = Files.createTempDirectory("code-scan-");
        Path source = root.resolve("src/main/java/com/acme/hero/HeroService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.acme.hero;

                public class HeroService {
                    public boolean trainHero(int heroId) {
                        return heroId > 0;
                    }
                }
                """);

        JavaCodeScanner scanner = new JavaCodeScanner();
        JavaCodeScanner.ScanResult result = scanner.scan(new RagProperties.Code(
                "test-project",
                root.toString(),
                "code_chunks",
                List.of("/hero/"),
                List.of("/target/"),
                1_000_000
        ));

        assertThat(result.files()).isEqualTo(1);
        assertThat(result.chunks()).anySatisfy(chunk -> {
            assertThat(chunk.symbolType()).isEqualTo("class");
            assertThat(chunk.symbolName()).isEqualTo("HeroService");
        });
        assertThat(result.chunks()).anySatisfy(chunk -> {
            assertThat(chunk.symbolType()).isEqualTo("method");
            assertThat(chunk.symbolName()).isEqualTo("trainHero");
            assertThat(chunk.text()).contains("return heroId > 0");
        });
    }
    @Test
    void splitsLargeSymbolsIntoEmbeddingSafeChunks() throws Exception {
        Path root = Files.createTempDirectory("code-scan-large-");
        Path source = root.resolve("src/main/java/com/acme/hero/LargeHeroService.java");
        Files.createDirectories(source.getParent());
        String body = "        int value = 1;\n".repeat(900);
        Files.writeString(source, "package com.acme.hero;\npublic class LargeHeroService {\n"
                + "    public void rebuildHero() {\n" + body + "    }\n}\n");

        JavaCodeScanner.ScanResult result = new JavaCodeScanner().scan(new RagProperties.Code(
                "test-project", root.toString(), "code_chunks", List.of("/hero/"),
                List.of("/target/"), 1_000_000));

        List<com.example.requirementrag.model.CodeChunk> classChunks = result.chunks().stream()
                .filter(chunk -> chunk.symbolName().equals("LargeHeroService"))
                .toList();
        assertThat(classChunks).hasSize(1);
        assertThat(classChunks.getFirst().text().length()).isLessThanOrEqualTo(JavaCodeScanner.TYPE_CONTEXT_CHARS);

        List<com.example.requirementrag.model.CodeChunk> methodChunks = result.chunks().stream()
                .filter(chunk -> chunk.symbolName().equals("rebuildHero"))
                .toList();
        assertThat(methodChunks).hasSizeGreaterThan(1);
        assertThat(methodChunks).allSatisfy(chunk ->
                assertThat(chunk.text().length()).isLessThanOrEqualTo(JavaCodeScanner.MAX_CHUNK_CHARS));
    }

}
