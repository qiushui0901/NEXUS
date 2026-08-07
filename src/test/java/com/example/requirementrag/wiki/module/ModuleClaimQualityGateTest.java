package com.example.requirementrag.wiki.module;

import com.example.requirementrag.code.CodeRelation;
import com.example.requirementrag.code.CodeSymbol;
import com.example.requirementrag.wiki.WikiModels;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleClaimQualityGateTest {

    private final ModuleClaimQualityGate gate = new ModuleClaimQualityGate();

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
                        "src/auth/AuthService.java", "com.game.auth.AuthService", 1, 50, "s1")),
                List.of(new ModuleFactModels.ModuleDiagnostic("UNRESOLVED_DYNAMIC_CALL",
                        "调用目标无法静态解析", "src/auth/AuthService.java:30")));
    }

    @Test
    void acceptsModulePageWithValidFullClaims() {
        var bundle = bundle();
        WikiModels.PageSource page = new ModuleWikiPlanner().plan(bundle, "5.1", null, "abc123");
        gate.validate("game", "5.1", List.of(page));
        assertThat(page.claims()).hasSize(7);
        assertThat(page.pageType()).isEqualTo(WikiModels.PageType.MODULE);
        assertThat(page.claims().get(1).support()).isEqualTo(WikiModels.ClaimSupport.FULL);
        assertThat(page.claims().get(1).evidenceIds()).isNotEmpty();
    }

    @Test
    void rejectsModulePageWithoutCodeEvidence() {
        var bundle = new ModuleFactModels.ModuleFactBundle("game", "abc123", "auth", "认证模块", "src/auth",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
        WikiModels.PageSource page = new ModuleWikiPlanner().plan(bundle, "5.1", null, "abc123");
        assertThatThrownBy(() -> gate.validate("game", "5.1", List.of(page)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有代码证据");
    }

    @Test
    void rejectsFullClaimWithoutEvidenceId() {
        var bundle = bundle();
        WikiModels.PageSource page = new ModuleWikiPlanner().plan(bundle, "5.1", null, "abc123");
        WikiModels.PageSource broken = new WikiModels.PageSource(page.featureId(), page.title(), page.category(),
                page.introducedVersion(), page.status(), page.aliases(), page.summary(), page.requirementSources(),
                page.productRules(), page.processSteps(), page.codeEntries(), page.codeSymbols(),
                page.dataImpacts(), page.boundaryConditions(), page.acceptanceCriteria(), page.testPoints(),
                page.testKnowledge(), page.versionChange(), page.quality(), page.risks(), page.relations(),
                page.evidence(), page.pageType(),
                List.of(new WikiModels.Claim("broken-full", "entry", "无证据的 FULL 声明",
                        WikiModels.ClaimSupport.FULL, List.of())));
        assertThatThrownBy(() -> gate.validate("game", "5.1", List.of(broken)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FULL Claim 缺少证据引用");
    }

    @Test
    void rejectsClaimReferencingEvidenceOutsideThePage() {
        var bundle = bundle();
        WikiModels.PageSource page = new ModuleWikiPlanner().plan(bundle, "5.1", null, "abc123");
        WikiModels.PageSource broken = new WikiModels.PageSource(page.featureId(), page.title(), page.category(),
                page.introducedVersion(), page.status(), page.aliases(), page.summary(), page.requirementSources(),
                page.productRules(), page.processSteps(), page.codeEntries(), page.codeSymbols(),
                page.dataImpacts(), page.boundaryConditions(), page.acceptanceCriteria(), page.testPoints(),
                page.testKnowledge(), page.versionChange(), page.quality(), page.risks(), page.relations(),
                page.evidence(), page.pageType(),
                List.of(new WikiModels.Claim("broken-ref", "entry", "引用越界证据",
                        WikiModels.ClaimSupport.FULL, List.of("code:auth:99"))));
        assertThatThrownBy(() -> gate.validate("game", "5.1", List.of(broken)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("引用了不存在的证据");
    }

    @Test
    void rejectsEvidenceFromAnotherProjectOrVersion() {
        var bundle = bundle();
        WikiModels.PageSource page = new ModuleWikiPlanner().plan(bundle, "5.1", null, "abc123");
        WikiModels.Evidence foreign = new WikiModels.Evidence("CODE", "x", "other-project", "6.0",
                "lines=1-2", "x", "abc123", "src/A.java", "x", "PENDING_REVIEW");
        WikiModels.PageSource broken = new WikiModels.PageSource(page.featureId(), page.title(), page.category(),
                page.introducedVersion(), page.status(), page.aliases(), page.summary(), page.requirementSources(),
                page.productRules(), page.processSteps(), page.codeEntries(), page.codeSymbols(),
                page.dataImpacts(), page.boundaryConditions(), page.acceptanceCriteria(), page.testPoints(),
                page.testKnowledge(), page.versionChange(), page.quality(), page.risks(), page.relations(),
                List.of(foreign), page.pageType(),
                List.of(new WikiModels.Claim("broken-cross", "entry", "引用跨项目证据",
                        WikiModels.ClaimSupport.FULL, List.of("code:auth:0"))));
        assertThatThrownBy(() -> gate.validate("game", "5.1", List.of(broken)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("跨项目");
    }
}
