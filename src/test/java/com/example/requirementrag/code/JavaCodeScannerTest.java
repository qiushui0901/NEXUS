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
}
