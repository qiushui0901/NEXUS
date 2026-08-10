package com.example.requirementrag.wiki.module;

import com.example.requirementrag.code.CodeSymbol;
import com.example.requirementrag.code.SQLiteSymbolGraphStore;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.wiki.WikiModels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModuleClaimQualityGateTest {
    @TempDir
    Path temp;

    private final ProjectRegistry projectRegistry = mock(ProjectRegistry.class);
    private final SQLiteSymbolGraphStore graphStore = mock(SQLiteSymbolGraphStore.class);
    private ModuleClaimQualityGate gate;

    @BeforeEach
    void setUp() throws Exception {
        RagProperties.ProjectConfig project = new RagProperties.ProjectConfig(
                "game", "Game", "game", "server", "requirements_game", "code_game",
                temp.toString(), "group/game", null, List.of(), List.of(), 1_000_000);
        when(projectRegistry.require("game")).thenReturn(project);
        when(graphStore.latestCommit("game")).thenReturn("abc123");
        when(graphStore.findSymbols("game", "abc123", "com.game.auth.AuthService", 5))
                .thenReturn(List.of(new CodeSymbol("s1", "game", "abc123", "java", "CLASS",
                        "com.game.auth.AuthService", "AuthService", "src/auth/AuthService.java", 1, 50,
                        false, false)));
        gate = new ModuleClaimQualityGate(projectRegistry, graphStore);
        Path module = temp.resolve("src/auth");
        Files.createDirectories(module);
        StringBuilder source = new StringBuilder();
        for (int line = 1; line <= 50; line++) source.append("line ").append(line).append('\n');
        Files.writeString(module.resolve("AuthService.java"), source.toString());
    }

    private ModuleFactModels.ModuleFactBundle bundle() {
        return new ModuleFactModels.ModuleFactBundle("game", "abc123", "auth", "认证模块", "src/auth",
                List.of("src/auth"), List.of("com.game.auth"),
                List.of(new CodeSymbol("s1", "game", "abc123", "java", "CLASS", "com.game.auth.AuthService",
                        "AuthService", "src/auth/AuthService.java", 1, 50, false, false)),
                List.of(new CodeSymbol("s2", "game", "abc123", "java", "METHOD",
                        "com.game.auth.AuthService.revoke", "revoke", "src/auth/AuthService.java", 10, 20,
                        true, false)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new ModuleFactModels.ModuleEvidence("code:auth:0", "CODE", "game", "5.1", "abc123",
                        "src/auth/AuthService.java", "com.game.auth.AuthService", 1, 50, "s1"),
                        new ModuleFactModels.ModuleEvidence("diagnostic:auth:1", "DIAGNOSTIC", "game", "5.1",
                                "abc123", "src/auth/AuthService.java:30", "UNRESOLVED_DYNAMIC_CALL", 0, 0, "d1")),
                List.of(new ModuleFactModels.ModuleDiagnostic("UNRESOLVED_DYNAMIC_CALL",
                        "调用目标无法静态解析", "src/auth/AuthService.java:30")));
    }

    private WikiModels.PageSource plannedPage() {
        return new ModuleWikiPlanner().plan(bundle(), "5.1", null, "abc123");
    }

    private WikiModels.PageSource rebuilt(ModuleFactModels.ModuleFactBundle override,
                                          List<WikiModels.Evidence> evidence,
                                          List<WikiModels.Claim> claims) {
        WikiModels.PageSource page = new ModuleWikiPlanner().plan(override == null ? bundle() : override,
                "5.1", null, "abc123");
        return new WikiModels.PageSource(page.featureId(), page.title(), page.category(),
                page.introducedVersion(), page.status(), page.aliases(), page.summary(), page.requirementSources(),
                page.productRules(), page.processSteps(), page.codeEntries(), page.codeSymbols(),
                page.dataImpacts(), page.boundaryConditions(), page.acceptanceCriteria(), page.testPoints(),
                page.testKnowledge(), page.versionChange(), page.quality(), page.risks(), page.relations(),
                evidence == null ? page.evidence() : evidence,
                page.pageType(), claims == null ? page.claims() : claims);
    }

    @Test
    void acceptsModulePageWithValidFullClaims() {
        var page = plannedPage();
        gate.validate("game", "5.1", "abc123", List.of(page));
        assertThat(page.claims()).hasSize(7);
        assertThat(page.pageType()).isEqualTo(WikiModels.PageType.MODULE);
        assertThat(page.claims().get(1).support()).isEqualTo(WikiModels.ClaimSupport.FULL);
        assertThat(page.claims().get(1).evidenceIds()).isNotEmpty();
    }

    @Test
    void rejectsModulePageWithoutRealCodeEvidence() {
        var page = plannedPage();
        WikiModels.Evidence onlyDependency = new WikiModels.Evidence("DEPENDENCY", "dep", "game", "5.1",
                "lines=1-2", "dep", "abc123", "src/auth/AuthService.java", "dep", "PENDING_REVIEW");
        var broken = rebuilt(null, List.of(onlyDependency), List.of());
        assertThatThrownBy(() -> gate.validate("game", "5.1", "abc123", List.of(broken)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有真实 CODE 证据");
    }

    @Test
    void rejectsEvidenceFromAnotherCommit() {
        var page = plannedPage();
        WikiModels.Evidence staleCommit = new WikiModels.Evidence("CODE", "com.game.auth.AuthService",
                "game", "5.1", "lines=1-50", "x", "def456", "src/auth/AuthService.java",
                "com.game.auth.AuthService", "PENDING_REVIEW");
        var broken = rebuilt(null, List.of(staleCommit), List.of());
        assertThatThrownBy(() -> gate.validate("game", "5.1", "abc123", List.of(broken)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("跨 commit");
    }

    @Test
    void rejectsMissingCommitOnTargetOrEvidence() {
        var page = plannedPage();
        assertThatThrownBy(() -> gate.validate("game", "5.1", "", List.of(page)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少目标代码提交");
        WikiModels.Evidence noCommit = new WikiModels.Evidence("CODE", "com.game.auth.AuthService",
                "game", "5.1", "lines=1-50", "x", "", "src/auth/AuthService.java",
                "com.game.auth.AuthService", "PENDING_REVIEW");
        var broken = rebuilt(null, List.of(noCommit), List.of());
        assertThatThrownBy(() -> gate.validate("game", "5.1", "abc123", List.of(broken)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少 commit");
    }

    @Test
    void rejectsCodeEvidenceWhoseSymbolDisappearedFromTheGraph() {
        var page = plannedPage();
        when(graphStore.findSymbols("game", "abc123", "com.game.auth.AuthService", 5))
                .thenReturn(List.of());
        assertThatThrownBy(() -> gate.validate("game", "5.1", "abc123", List.of(page)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("符号已不存在");
    }

    @Test
    void rejectsEvidenceIdNamespaceMismatchingEvidenceType() {
        var page = plannedPage();
        var broken = rebuilt(null, null, List.of(new WikiModels.Claim("auth-entry", "entry",
                "类型不一致引用", WikiModels.ClaimSupport.FULL, List.of("route:auth:0"))));
        assertThatThrownBy(() -> gate.validate("game", "5.1", "abc123", List.of(broken)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("前缀与证据类型不一致");
    }

    @Test
    void rejectsEvidenceFileOutsideRepositoryOrMissing() {
        var page = plannedPage();
        WikiModels.Evidence missingFile = new WikiModels.Evidence("CODE", "x", "game", "5.1",
                "lines=1-10", "x", "abc123", "src/does-not-exist.java", "x", "PENDING_REVIEW");
        var broken = rebuilt(null, List.of(missingFile), List.of());
        assertThatThrownBy(() -> gate.validate("game", "5.1", "abc123", List.of(broken)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在或不可读");
    }

    @Test
    void rejectsEvidenceLinesBeyondFileLength() {
        var page = plannedPage();
        WikiModels.Evidence outOfRange = new WikiModels.Evidence("CODE", "x", "game", "5.1",
                "lines=40-99", "x", "abc123", "src/auth/AuthService.java", "x", "PENDING_REVIEW");
        var broken = rebuilt(null, List.of(outOfRange), List.of());
        assertThatThrownBy(() -> gate.validate("game", "5.1", "abc123", List.of(broken)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("行号越界");
    }

    @Test
    void rejectsUnresolvedConflictClaim() {
        var page = plannedPage();
        var broken = rebuilt(null, null, List.of(new WikiModels.Claim("auth-conflict", "risk",
                "来源互相冲突", WikiModels.ClaimSupport.CONFLICT, List.of("code:auth:0"))));
        assertThatThrownBy(() -> gate.validate("game", "5.1", "abc123", List.of(broken)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CONFLICT");
    }

    @Test
    void rejectsFullClaimWithoutEvidenceId() {
        var page = plannedPage();
        var broken = rebuilt(null, null, List.of(new WikiModels.Claim("broken-full", "entry",
                "无证据的 FULL 声明", WikiModels.ClaimSupport.FULL, List.of())));
        assertThatThrownBy(() -> gate.validate("game", "5.1", "abc123", List.of(broken)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FULL Claim 缺少证据引用");
    }

    @Test
    void rejectsClaimReferencingEvidenceOutsideThePage() {
        var page = plannedPage();
        var broken = rebuilt(null, null, List.of(new WikiModels.Claim("broken-ref", "entry",
                "引用越界证据", WikiModels.ClaimSupport.FULL, List.of("code:auth:99"))));
        assertThatThrownBy(() -> gate.validate("game", "5.1", "abc123", List.of(broken)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("引用了不存在的证据");
    }

    @Test
    void rejectsEvidenceFromAnotherProjectOrVersion() {
        var page = plannedPage();
        WikiModels.Evidence foreign = new WikiModels.Evidence("CODE", "com.game.auth.AuthService",
                "other-project", "6.0", "lines=1-2", "x", "abc123", "src/auth/AuthService.java",
                "com.game.auth.AuthService", "PENDING_REVIEW");
        var broken = rebuilt(null, List.of(foreign),
                List.of(new WikiModels.Claim("broken-cross", "entry", "引用跨项目证据",
                        WikiModels.ClaimSupport.FULL, List.of("code:auth:0"))));
        assertThatThrownBy(() -> gate.validate("game", "5.1", "abc123", List.of(broken)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("跨项目");
    }
}
