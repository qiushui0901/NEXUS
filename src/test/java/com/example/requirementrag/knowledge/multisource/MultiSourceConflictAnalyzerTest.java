package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冲突分析：内部冲突按 factKey 分组（同来源多个不同值才算不一致），
 * 跨来源冲突按 subject|predicate 对齐；值比较统一走"数值 + 单位别名"归一化。
 */
class MultiSourceConflictAnalyzerTest {

    private final MultiSourceConflictAnalyzer analyzer = new MultiSourceConflictAnalyzer();

    @Test
    void sameSemanticSourceMultipleValuesProduceInternalConflict() {
        UnifiedKnowledgeClaim a = semantic("c1", "成长基金", "cooldown", "30秒", "growth_fund.cooldown");
        UnifiedKnowledgeClaim b = semantic("c2", "成长基金", "cooldown", "60秒", "growth_fund.cooldown");

        List<String> conflicts = analyzer.analyze(List.of(a, b));

        assertThat(conflicts).anySatisfy(conflict -> {
            assertThat(conflict).contains("VERSION_INTERNAL");
            assertThat(conflict).contains("REQUIREMENT_SEMANTIC");
            assertThat(conflict).contains("30秒");
            assertThat(conflict).contains("60秒");
        });
        // 内部冲突分组按 factKey：惩罚只作用于同 factKey 的候选。
        assertThat(analyzer.conflictGroups(List.of(a, b))).contains("growth_fund.cooldown");
    }

    @Test
    void sameValueDuplicatesDoNotCreateConflict() {
        UnifiedKnowledgeClaim a = semantic("c1", "成长基金", "cooldown", "30秒", "growth_fund.cooldown");
        UnifiedKnowledgeClaim b = semantic("c2", "成长基金", "cooldown", "30秒", "growth_fund.cooldown");

        assertThat(analyzer.analyze(List.of(a, b))).isEmpty();
    }

    @Test
    void differentFactKeysWithSameSubjectPredicateDoNotConflict() {
        // 不同领域事实（不同 factKey）即使 subject|predicate 相同，也不产生内部冲突与惩罚。
        UnifiedKnowledgeClaim a = semantic("c1", "成长基金", "奖励货币", "灵玉", "growth_fund.reward_currency");
        UnifiedKnowledgeClaim b = semantic("c2", "成长基金", "奖励货币", "绑定灵玉", "growth_fund.vip_reward_currency");

        assertThat(analyzer.analyze(List.of(a, b))).isEmpty();
        assertThat(analyzer.conflictGroups(List.of(a, b))).isEmpty();
    }

    @Test
    void conflictPenaltyUsesFactKeyGroups() {
        // 同 factKey 两个不同值 → 冲突；不同 factKey 的同名字段候选不受该冲突惩罚。
        UnifiedKnowledgeClaim a = semantic("c1", "成长基金", "cooldown", "30秒", "growth_fund.cooldown");
        UnifiedKnowledgeClaim b = semantic("c2", "成长基金", "cooldown", "60秒", "growth_fund.cooldown");
        UnifiedKnowledgeClaim other = semantic("c3", "成长基金", "cooldown", "30秒", "growth_fund.vip_cooldown");

        Set<String> groups = analyzer.conflictGroups(List.of(a, b, other));

        assertThat(groups).contains("growth_fund.cooldown");
        assertThat(analyzer.groupKeys(other)).noneMatch(groups::contains);
        assertThat(analyzer.groupKeys(a)).anyMatch(groups::contains);
    }

    @Test
    void numericallyEquivalentValuesDoNotCreateInternalConflict() {
        // "30秒" 与 "30.0秒" 数值等价：内部冲突判定必须做数值+单位归一化，不能按原始字符串比较。
        UnifiedKnowledgeClaim a = semantic("c1", "成长基金", "cooldown", "30秒", "growth_fund.cooldown");
        UnifiedKnowledgeClaim b = semantic("c2", "成长基金", "cooldown", "30.0秒", "growth_fund.cooldown");
        UnifiedKnowledgeClaim c = semantic("c3", "权限撤销", "重试次数", "1,000", "perm.revoked.retry");
        UnifiedKnowledgeClaim d = semantic("c4", "权限撤销", "重试次数", "1000", "perm.revoked.retry");

        assertThat(analyzer.analyze(List.of(a, b))).isEmpty();
        assertThat(analyzer.analyze(List.of(c, d))).isEmpty();
        assertThat(analyzer.conflictGroups(List.of(a, b))).isEmpty();
    }

    @Test
    void unitAliasDifferencesDoNotCreateInternalConflict() {
        // 单位别名归并：30s 与 30秒、5min 与 5分钟 是同一值。
        UnifiedKnowledgeClaim a = semantic("c1", "成长基金", "cooldown", "30s", "growth_fund.cooldown");
        UnifiedKnowledgeClaim b = semantic("c2", "成长基金", "cooldown", "30秒", "growth_fund.cooldown");
        UnifiedKnowledgeClaim c = semantic("c3", "权限撤销", "传播时间", "5min", "perm.revoked.delay");
        UnifiedKnowledgeClaim d = semantic("c4", "权限撤销", "传播时间", "5分钟", "perm.revoked.delay");

        assertThat(analyzer.analyze(List.of(a, b))).isEmpty();
        assertThat(analyzer.analyze(List.of(c, d))).isEmpty();
    }

    @Test
    void differentUnitsStillCreateInternalConflict() {
        // 秒与分钟是不同值：不做跨单位换算，避免掩盖真实冲突。
        UnifiedKnowledgeClaim a = semantic("c1", "成长基金", "cooldown", "30秒", "growth_fund.cooldown");
        UnifiedKnowledgeClaim b = semantic("c2", "成长基金", "cooldown", "30分钟", "growth_fund.cooldown");

        assertThat(analyzer.analyze(List.of(a, b))).anySatisfy(conflict -> {
            assertThat(conflict).contains("VERSION_INTERNAL");
            assertThat(conflict).contains("30秒");
            assertThat(conflict).contains("30分钟");
        });
    }

    @Test
    void requirementAndParameterWithSecondUnitAliasAreEquivalent() {
        // 跨来源比较与内部比较使用同一套归一化："30秒" vs "30s" 不再被误判为冲突。
        UnifiedKnowledgeClaim requirement = claim("r1", SourceType.REQUIREMENT, "成长基金", "cooldown",
                "30秒", null, "req:cooldown");
        UnifiedKnowledgeClaim parameter = claim("p1", SourceType.PARAMETER_TABLE, "成长基金", "cooldown",
                "30s", "s", "P|5.1|成长基金|cooldown");

        assertThat(analyzer.analyze(List.of(requirement, parameter))).isEmpty();
    }

    @Test
    void requirementAndParameterWithMinuteUnitAliasAreEquivalent() {
        UnifiedKnowledgeClaim requirement = claim("r1", SourceType.REQUIREMENT, "权限撤销", "传播时间",
                "5分钟", null, "req:delay");
        UnifiedKnowledgeClaim parameter = claim("p1", SourceType.PARAMETER_TABLE, "权限撤销", "传播时间",
                "5min", "min", "P|5.1|权限撤销|传播时间");

        assertThat(analyzer.analyze(List.of(requirement, parameter))).isEmpty();
    }

    @Test
    void parameterAndTestCaseWithHourUnitAliasAreEquivalent() {
        UnifiedKnowledgeClaim parameter = claim("p1", SourceType.PARAMETER_TABLE, "成长基金", "冷却时间",
                "2小时", "小时", "P|5.1|成长基金|冷却时间");
        UnifiedKnowledgeClaim testCase = claim("t1", SourceType.TEST_CASE, "成长基金", "冷却时间",
                "2h", null, "T|5.1|tc-001");

        assertThat(analyzer.analyze(List.of(parameter, testCase))).isEmpty();
    }

    @Test
    void bareValueWithClaimUnitMatchesEmbeddedUnitValue() {
        // 语义数值事实值分离（value="5" + unit="分钟"）与参数表内嵌单位（"5分钟"）：单位联合归一化后等价。
        UnifiedKnowledgeClaim semantic = claim("s1", SourceType.REQUIREMENT_SEMANTIC, "权限撤销", "传播时间",
                "5", "分钟", "p1|5.1|权限撤销|传播时间");
        UnifiedKnowledgeClaim parameter = claim("p1", SourceType.PARAMETER_TABLE, "权限撤销", "传播时间",
                "5分钟", "分钟", "P|5.1|权限撤销|传播时间");

        assertThat(analyzer.analyze(List.of(semantic, parameter))).isEmpty();
    }

    @Test
    void crossSourceDifferentValuesStillProduceConflict() {
        // factKey 对齐（双方一致）：值不同 → 确定冲突，无 POTENTIAL 前缀。
        UnifiedKnowledgeClaim requirement = claim("r1", SourceType.REQUIREMENT, "成长基金", "cooldown",
                "30秒", null, "p1|5.1|成长基金|cooldown");
        UnifiedKnowledgeClaim parameter = claim("p1", SourceType.PARAMETER_TABLE, "成长基金", "cooldown",
                "30分钟", "分钟", "p1|5.1|成长基金|cooldown");

        List<String> conflicts = analyzer.analyze(List.of(requirement, parameter));

        assertThat(conflicts).anySatisfy(conflict -> {
            assertThat(conflict).contains("REQUIREMENT_PARAMETER");
            assertThat(conflict).doesNotContain("POTENTIAL_CROSS_SOURCE_CONFLICT");
            assertThat(conflict).contains("30秒");
            assertThat(conflict).contains("30分钟");
        });
    }

    @Test
    void unalignedFactKeyCrossSourceConflictMarkedAsPotential() {
        // factKey 不一致（跨源口径未统一）：subject|predicate 配对只是推测，标记 POTENTIAL 而非确定冲突。
        UnifiedKnowledgeClaim requirement = claim("r1", SourceType.REQUIREMENT, "成长基金", "cooldown",
                "30秒", null, "req:cooldown");
        UnifiedKnowledgeClaim parameter = claim("p1", SourceType.PARAMETER_TABLE, "成长基金", "cooldown",
                "60秒", "秒", "P|5.1|成长基金|cooldown");

        List<String> conflicts = analyzer.analyze(List.of(requirement, parameter));

        assertThat(conflicts).singleElement().satisfies(conflict -> {
            assertThat(conflict).startsWith("POTENTIAL_CROSS_SOURCE_CONFLICT:");
            assertThat(conflict).contains("REQUIREMENT_PARAMETER");
        });
    }

    @Test
    void potentialConflictDoesNotBecomeConfirmedConflictStatus() {
        // 参数表是 PRIMARY：若 POTENTIAL 按确定冲突处理会变成 CONFLICTED；POTENTIAL 最多 REVIEW_REQUIRED。
        UnifiedKnowledgeClaim requirement = claim("r1", SourceType.REQUIREMENT, "成长基金", "cooldown",
                "30秒", null, "req:cooldown");
        UnifiedKnowledgeClaim parameter = claim("p1", SourceType.PARAMETER_TABLE, "成长基金", "cooldown",
                "60秒", "秒", "P|5.1|成长基金|cooldown");

        List<String> conflicts = analyzer.analyze(List.of(requirement, parameter));

        assertThat(analyzer.resolveStatus(List.of(requirement, parameter), conflicts))
                .isEqualTo(MultiSourceKnowledgeModels.AnswerStatus.REVIEW_REQUIRED);
    }

    @Test
    void potentialConflictDoesNotApplyHardPenalty() {
        // POTENTIAL 跨源冲突不进入惩罚分组；确定冲突（factKey 对齐 + 双方单值）才进入。
        UnifiedKnowledgeClaim requirement = claim("r1", SourceType.REQUIREMENT, "成长基金", "cooldown",
                "30秒", null, "req:cooldown");
        UnifiedKnowledgeClaim parameter = claim("p1", SourceType.PARAMETER_TABLE, "成长基金", "cooldown",
                "60秒", "秒", "P|5.1|成长基金|cooldown");
        assertThat(analyzer.conflictGroups(List.of(requirement, parameter))).isEmpty();

        // 对照组：factKey 对齐（双方一致）的确定冲突进入惩罚分组。
        UnifiedKnowledgeClaim alignedRequirement = claim("r2", SourceType.REQUIREMENT, "成长基金", "cooldown",
                "30秒", null, "p1|5.1|成长基金|cooldown");
        UnifiedKnowledgeClaim alignedParameter = claim("p2", SourceType.PARAMETER_TABLE, "成长基金", "cooldown",
                "60秒", "秒", "p1|5.1|成长基金|cooldown");
        assertThat(analyzer.conflictGroups(List.of(alignedRequirement, alignedParameter)))
                .contains("成长基金|cooldown");
    }

    @Test
    void crossSourceOutcomeIsIndependentOfClaimOrder() {
        // 参数表存在多个值（30秒/60秒）：不静默取第一条配对，跨源结论与输入顺序无关。
        UnifiedKnowledgeClaim requirement = claim("r1", SourceType.REQUIREMENT, "成长基金", "cooldown",
                "60秒", null, "p1|5.1|成长基金|cooldown");
        UnifiedKnowledgeClaim p30 = claim("p30", SourceType.PARAMETER_TABLE, "成长基金", "cooldown",
                "30秒", "秒", "p1|5.1|成长基金|cooldown");
        UnifiedKnowledgeClaim p60 = claim("p60", SourceType.PARAMETER_TABLE, "成长基金", "cooldown",
                "60秒", "秒", "p1|5.1|成长基金|cooldown");

        List<String> forward = analyzer.analyze(List.of(requirement, p30, p60));
        List<String> reversed = analyzer.analyze(List.of(p60, p30, requirement));

        assertThat(forward).containsExactlyInAnyOrderElementsOf(reversed);
        assertThat(forward).anySatisfy(conflict -> {
            assertThat(conflict).startsWith("POTENTIAL_CROSS_SOURCE_CONFLICT:");
            assertThat(conflict).contains("30秒/60秒");
            assertThat(conflict).contains("需人工复核");
        });
        // 参数表同 factKey 多值同时报内部冲突。
        assertThat(forward).anyMatch(conflict -> conflict.contains("VERSION_INTERNAL"));
    }

    @Test
    void multipleValuesOnEitherSideRemainPotentialEvenWhenFactKeysAlign() {
        // factKey 已对齐但某来源存在多个值：与哪一条配对无法确定，仍标 POTENTIAL 而非确定冲突。
        UnifiedKnowledgeClaim reward = claim("c1", SourceType.REQUIREMENT_SEMANTIC, "成长基金", "奖励货币",
                "灵玉", null, "p1|5.1|成长基金|奖励货币");
        UnifiedKnowledgeClaim vipReward = claim("c2", SourceType.REQUIREMENT_SEMANTIC, "成长基金", "奖励货币",
                "绑定灵玉", null, "p1|5.1|成长基金|奖励货币");
        UnifiedKnowledgeClaim parameter = claim("p1", SourceType.PARAMETER_TABLE, "成长基金", "奖励货币",
                "绑定灵玉", null, "p1|5.1|成长基金|奖励货币");

        List<String> conflicts = analyzer.analyze(List.of(reward, vipReward, parameter));

        assertThat(conflicts).anySatisfy(conflict -> {
            assertThat(conflict).startsWith("POTENTIAL_CROSS_SOURCE_CONFLICT:");
            assertThat(conflict).contains("灵玉/绑定灵玉");
        });
    }

    @Test
    void sameSubjectPredicateDifferentFactKeysNotPresentedAsDefinitive() {
        // Review 场景：两个不同领域事实共享 subject|predicate，参数表实际对应其中一个；
        // 首个语义候选与参数配对可能是错误配对——只能给出 POTENTIAL，不能直接认定确定冲突。
        UnifiedKnowledgeClaim reward = claim("c1", SourceType.REQUIREMENT_SEMANTIC, "成长基金", "奖励货币",
                "灵玉", null, "growth_fund.reward_currency");
        UnifiedKnowledgeClaim vipReward = claim("c2", SourceType.REQUIREMENT_SEMANTIC, "成长基金", "奖励货币",
                "绑定灵玉", null, "growth_fund.vip_reward_currency");
        UnifiedKnowledgeClaim parameter = claim("p1", SourceType.PARAMETER_TABLE, "成长基金", "奖励货币",
                "绑定灵玉", null, "P|5.1|成长基金|奖励货币");

        List<String> conflicts = analyzer.analyze(List.of(reward, vipReward, parameter));

        // 参数表实际与 vip_reward_currency 一致，但按 subject|predicate 配对到首个候选（灵玉）：
        // 值不同且 factKey 未对齐 → POTENTIAL，不升级为确定冲突，也不产生内部冲突（factKey 不同）。
        assertThat(conflicts).singleElement()
                .satisfies(conflict -> assertThat(conflict).startsWith("POTENTIAL_CROSS_SOURCE_CONFLICT:"));
    }

    @Test
    void crossSourceGroupingStillAlignsWhenFactKeysDiffer() {
        // 参数表与测试用例的 factKey 口径不同（P|...|module|param vs T|...|tcId）：
        // 跨来源冲突仍按 subject|predicate 对齐，回归锁定既有行为。
        UnifiedKnowledgeClaim parameter = claim("p1", SourceType.PARAMETER_TABLE, "权限撤销", "传播时间",
                "5分钟", "分钟", "P|1.0|权限撤销|传播时间");
        UnifiedKnowledgeClaim testCase = claim("t1", SourceType.TEST_CASE, "权限撤销", "传播时间",
                "10分钟", "分钟", "T|1.0|tc-001");

        List<String> conflicts = analyzer.analyze(List.of(parameter, testCase));

        assertThat(conflicts).anyMatch(message -> message.contains("PARAMETER_TEST"));
    }

    private UnifiedKnowledgeClaim semantic(String claimId, String subject, String predicate, String value,
                                           String factKey) {
        return claim(claimId, SourceType.REQUIREMENT_SEMANTIC, subject, predicate, value, null, factKey);
    }

    private UnifiedKnowledgeClaim claim(String claimId, SourceType sourceType, String subject,
                                        String predicate, String value, String unit, String factKey) {
        return new UnifiedKnowledgeClaim(claimId, "p1", "5.1",
                factKey, subject, predicate, value, "TEXT", unit,
                sourceType, sourceType == SourceType.PARAMETER_TABLE ? Authority.PRIMARY : Authority.SECONDARY,
                KnowledgeStatus.EXTRACTED, "5.1", null, "semantic:" + claimId, subject);
    }

    @Test
    void confirmedConflictInvolvingPrimaryBecomesConflicted() {
        // Review 场景：语义候选与参数表 factKey 对齐但值不同 → 确定冲突且组内有 PRIMARY → CONFLICTED。
        // 此前按消息子串解析分组键会把描述文本误当分组键，确定冲突永远升不到 CONFLICTED。
        UnifiedKnowledgeClaim semantic = claim("c1", SourceType.REQUIREMENT_SEMANTIC, "成长基金",
                "cooldown", "30秒", null, "p1|5.1|成长基金|cooldown");
        UnifiedKnowledgeClaim parameter = claim("p1", SourceType.PARAMETER_TABLE, "成长基金",
                "cooldown", "30分钟", "分钟", "p1|5.1|成长基金|cooldown");

        List<String> conflicts = analyzer.analyze(List.of(semantic, parameter));

        assertThat(conflicts).anyMatch(message -> !message.startsWith("POTENTIAL_CROSS_SOURCE_CONFLICT:"));
        assertThat(analyzer.resolveStatus(List.of(semantic, parameter), conflicts))
                .isEqualTo(MultiSourceKnowledgeModels.AnswerStatus.CONFLICTED);
    }

    @Test
    void isolatedFailedTestResultDoesNotEscalateToConflicted() {
        // Review 场景：参数表 Claim A 与无关的失败测试结果 B 同处候选集：
        // 冲突（B FAILED）只涉及 B 自己的分组，组内无 PRIMARY → 最多 REVIEW_REQUIRED，
        // 不能因候选集其它位置存在参数表 Claim 把整个查询推成 CONFLICTED。
        UnifiedKnowledgeClaim parameter = claim("p1", SourceType.PARAMETER_TABLE, "成长基金",
                "cooldown", "30秒", null, "p1|5.1|成长基金|cooldown");
        UnifiedKnowledgeClaim failedTest = claim("t1", SourceType.TEST_RESULT, "抽奖奖池",
                "rarity", "FAILED", null, "T|5.1|lottery-rarity");

        List<String> conflicts = analyzer.analyze(List.of(parameter, failedTest));

        assertThat(conflicts).anyMatch(message -> message.contains("TEST_RESULT_EXPECTATION"));
        assertThat(analyzer.resolveStatus(List.of(parameter, failedTest), conflicts))
                .isEqualTo(MultiSourceKnowledgeModels.AnswerStatus.REVIEW_REQUIRED);
    }
}
