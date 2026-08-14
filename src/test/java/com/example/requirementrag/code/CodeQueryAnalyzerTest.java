package com.example.requirementrag.code;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodeQueryAnalyzerTest {

    private final CodeQueryAnalyzer analyzer = new CodeQueryAnalyzer();

    @Test
    void parsesAdjacentQualifiedSymbol() {
        CodeQueryAnalyzer.ParsedCodeQuery parsed = analyzer.parse(
                "查找 VipMoaServiceImpl.queryVipShopIndex 的实现位置。");

        assertThat(parsed.kind()).isEqualTo(CodeQueryAnalyzer.QueryKind.EXACT_SYMBOL);
        assertThat(parsed.className()).isEqualTo("VipMoaServiceImpl");
        assertThat(parsed.symbolName()).isEqualTo("queryVipShopIndex");
        assertThat(parsed.qualifiedName()).isEqualTo("VipMoaServiceImpl.queryVipShopIndex");
    }

    @Test
    void parsesBusinessTermQueryWithQualifiedSymbol() {
        CodeQueryAnalyzer.ParsedCodeQuery parsed = analyzer.parse(
                "“VIP”这个业务名词对应的核心代码中，VipMoaServiceImpl.queryVipShopIndex 实现在哪里？");

        assertThat(parsed.kind()).isEqualTo(CodeQueryAnalyzer.QueryKind.EXACT_SYMBOL);
        assertThat(parsed.className()).isEqualTo("VipMoaServiceImpl");
        assertThat(parsed.symbolName()).isEqualTo("queryVipShopIndex");
    }

    @Test
    void parsesQuotedMethodNameWithClassNameContext() {
        CodeQueryAnalyzer.ParsedCodeQuery parsed = analyzer.parse(
                "需求中提到“执行 queryVipShopIndex 对应的业务处理”，在 VipMoaServiceImpl 中由哪个方法实现？");

        assertThat(parsed.kind()).isEqualTo(CodeQueryAnalyzer.QueryKind.EXACT_SYMBOL);
        assertThat(parsed.className()).isEqualTo("VipMoaServiceImpl");
        assertThat(parsed.symbolName()).isEqualTo("queryVipShopIndex");
    }

    @Test
    void parsesClassNameOnlyBehaviorQueryAsClassScoped() {
        CodeQueryAnalyzer.ParsedCodeQuery parsed = analyzer.parse(
                "需求中提到“摇骰子”，在 ActivityMazeService 中由哪个方法实现？");

        assertThat(parsed.kind()).isEqualTo(CodeQueryAnalyzer.QueryKind.CLASS_SCOPED);
        assertThat(parsed.className()).isEqualTo("ActivityMazeService");
        assertThat(parsed.symbolName()).isNull();
    }

    @Test
    void parsesRecalledClassNameBehaviorQueryAsClassScoped() {
        CodeQueryAnalyzer.ParsedCodeQuery parsed = analyzer.parse(
                "farm业务需要执行“玩家进行等级升级”时，应召回 FarmService 的哪个代码符号？");

        assertThat(parsed.kind()).isEqualTo(CodeQueryAnalyzer.QueryKind.CLASS_SCOPED);
        assertThat(parsed.className()).isEqualTo("FarmService");
        assertThat(parsed.symbolName()).isNull();
    }

    @Test
    void plainChineseBehaviorQueryWithoutIdentifiersIsGeneric() {
        CodeQueryAnalyzer.ParsedCodeQuery parsed = analyzer.parse("VIP 业务的升级逻辑有哪些关键步骤？");

        assertThat(parsed.kind()).isEqualTo(CodeQueryAnalyzer.QueryKind.GENERIC);
        assertThat(parsed.hasClassName()).isFalse();
    }

    @Test
    void extractsExplicitJavaFilePath() {
        CodeQueryAnalyzer.ParsedCodeQuery parsed = analyzer.parse(
                "帮我解释 src/main/java/com/immomo/bizgame/game/service/FarmService.java 的实现");

        assertThat(parsed.filePath()).isEqualTo("src/main/java/com/immomo/bizgame/game/service/FarmService.java");
    }

    @Test
    void lowerCaseMethodNameAloneIsNotPinnedAsExactWithoutClass() {
        CodeQueryAnalyzer.ParsedCodeQuery parsed = analyzer.parse("查找 handle 的实现位置。");

        assertThat(parsed.kind()).isEqualTo(CodeQueryAnalyzer.QueryKind.GENERIC);
    }
}
