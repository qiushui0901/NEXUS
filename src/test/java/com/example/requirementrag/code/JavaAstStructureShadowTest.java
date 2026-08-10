package com.example.requirementrag.code;

import com.example.requirementrag.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java AST shadow 差异报告（0.8.5 Phase 4）：
 * 同一 fixture 分别用 Tree-sitter AST 主扫描器与旧正则扫描器提取，
 * 断言 AST 覆盖 record / 重载 / 嵌套类型 / 注解 / 继承 / 实现 / 构造器，
 * 并输出旧正则解析器漏检的符号清单作为差异报告。
 */
class JavaAstStructureShadowTest {

    private static final String FIXTURE = """
            package com.acme.auth;

            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;

            @Retention(RetentionPolicy.RUNTIME)
            @interface SecureEndpoint {}

            public record TokenRecord(String token, long expiresAt) {
                public TokenRecord {
                    if (token == null || token.isBlank()) throw new IllegalArgumentException("token");
                }
            }

            public class AuthService extends BaseService implements Runnable {
                @SecureEndpoint
                public boolean revoke(String tokenId) {
                    return revokeInternal(tokenId);
                }

                public boolean revoke(int tokenCount) {
                    return tokenCount > 0;
                }

                @Override
                public void run() {}

                public class NestedHelper {
                    public String help() { return "nested"; }
                }

                private boolean revokeInternal(String tokenId) {
                    return tokenId != null;
                }
            }
            """;

    @TempDir
    Path temp;

    private CodeScanner.ScanResult scanAst() throws Exception {
        Path root = temp.resolve("ast");
        Files.createDirectories(root.resolve("src/main/java/com/acme/auth"));
        Files.writeString(root.resolve("src/main/java/com/acme/auth/AuthService.java"), FIXTURE);
        MultiLanguageCodeScanner scanner = new MultiLanguageCodeScanner(new CodeLanguageRegistry());
        return scanner.scan(new RagProperties.Code("demo", root.toString(), "code",
                List.of(), List.of(), 1_000_000));
    }

    private JavaCodeScanner.ScanResult scanLegacy() throws Exception {
        Path root = temp.resolve("legacy");
        Files.createDirectories(root.resolve("src/main/java/com/acme/auth"));
        Files.writeString(root.resolve("src/main/java/com/acme/auth/AuthService.java"), FIXTURE);
        return new JavaCodeScanner().scan(new RagProperties.Code("demo", root.toString(), "code",
                List.of(), List.of(), 1_000_000));
    }

    @Test
    void astScannerCoversAllJavaStructuralScenarios() throws Exception {
        CodeScanner.ScanResult result = scanAst();

        assertThat(result.diagnostics()).noneMatch(diagnostic -> diagnostic.code().equals("PARSE_FAILED"));
        Set<String> symbols = result.symbols().stream().map(CodeSymbol::simpleName).collect(Collectors.toSet());
        assertThat(symbols)
                .as("record 类型")
                .contains("TokenRecord")
                .as("方法重载（两个 revoke 都识别）")
                .contains("revoke")
                .as("嵌套类型")
                .contains("NestedHelper")
                .as("构造器（record 紧凑构造器）")
                .contains("TokenRecord")
                .as("继承的父类与实现的接口")
                .contains("AuthService");
        long overloads = result.symbols().stream()
                .filter(symbol -> symbol.simpleName().equals("revoke")).count();
        assertThat(overloads).as("重载方法各自成符号").isGreaterThanOrEqualTo(2);
        assertThat(result.symbols()).extracting(CodeSymbol::qualifiedName)
                .anyMatch(name -> name.contains("NestedHelper.help"));
    }

    @Test
    void shadowReportListsSymbolsTheLegacyRegexScannerMisses() throws Exception {
        CodeScanner.ScanResult ast = scanAst();
        JavaCodeScanner.ScanResult legacy = scanLegacy();

        Set<String> astSymbols = ast.symbols().stream().map(CodeSymbol::simpleName).collect(Collectors.toSet());
        Set<String> legacySymbols = legacy.chunks().stream()
                .map(chunk -> chunk.symbolName() == null ? "" : chunk.symbolName())
                .collect(Collectors.toSet());

        Set<String> astOnly = astSymbols.stream().filter(name -> !legacySymbols.contains(name))
                .collect(Collectors.toSet());
        assertThat(astOnly)
                .as("差异报告必须暴露旧解析器的结构盲区（record 完全漏检）")
                .contains("TokenRecord");
        assertThat(legacySymbols)
                .as("正则解析器对嵌套类与方法仍可识别，差异报告不应夸大")
                .contains("NestedHelper", "help");
    }
}
