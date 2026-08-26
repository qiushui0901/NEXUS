package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeClaimVectorTextComposerTest {

    private final KnowledgeClaimVectorProperties properties = new KnowledgeClaimVectorProperties(
            false, false, false, false,
            "knowledge_claims_live", "knowledge-claim-vector-v1", "knowledge-claim-text-v1",
            200, 3, 32, 3, 2, "data/test.db");
    private final KnowledgeClaimVectorTextComposer composer = new KnowledgeClaimVectorTextComposer(properties);

    // ===== 确定性快照 =====

    @Test
    void requirementTextIsDeterministicAndStable() {
        KnowledgeClaimRecord claim = claim("rc-1", SourceType.REQUIREMENT, Authority.PRIMARY,
                "guild_war.reward.distribution", "Guild war reward", "Distribution condition",
                "Rewards are distributed after settlement to eligible members",
                "STRING", null, "VERIFIED");

        String text = composer.compose(claim, "5.1").orElseThrow();

        assertThat(text).isEqualTo("""
                [Requirement]
                Subject: Guild war reward
                Predicate: Distribution condition
                Value: Rewards are distributed after settlement to eligible members
                Module: guild_war
                Fact key: guild_war.reward.distribution""");

        // 二次调用结果一致
        assertThat(composer.compose(claim, "5.1")).contains(text);
    }

    @Test
    void parameterTextIsDeterministicAndStable() {
        KnowledgeClaimRecord claim = claim("pc-1", SourceType.PARAMETER_TABLE, Authority.SECONDARY,
                "guild_war_reward_limit", "guild_war_reward_limit", "Limits reward claims",
                "100", "INTEGER", "count", "SUPPORTED");

        String text = composer.compose(claim, "5.1").orElseThrow();

        assertThat(text).isEqualTo("""
                [Parameter]
                Name: guild_war_reward_limit
                Purpose: Limits reward claims
                Value type: INTEGER
                Unit: count
                Value: 100
                Scope: Version 5.1
                Fact key: guild_war_reward_limit""");
    }

    @Test
    void testCaseTextIsDeterministicAndStable() {
        KnowledgeClaimRecord claim = claim("tc-1", SourceType.TEST_CASE, Authority.SECONDARY,
                "guild_war.test.settlement", "Guild war reward settlement",
                "The player belongs to a guild",
                "Rewards are distributed according to ranking",
                "STRING", null, "SUPPORTED");

        String text = composer.compose(claim, "5.1").orElseThrow();

        assertThat(text).isEqualTo("""
                [Test Case]
                Title: Guild war reward settlement
                Preconditions: The player belongs to a guild
                Expected result: Rewards are distributed according to ranking
                Module: guild_war
                Fact key: guild_war.test.settlement""");
    }

    @Test
    void doubtTextIsDeterministicAndStable() {
        KnowledgeClaimRecord claim = claim("dc-1", SourceType.DOUBT, Authority.SECONDARY,
                "guild_war.doubt.limit", "What if rewards exceed the limit?",
                "", "The system caps at the maximum.", null, null, "OPEN");

        String text = composer.compose(claim, "5.1").orElseThrow();

        assertThat(text).isEqualTo("""
                [Doubt]
                Question: What if rewards exceed the limit?
                Answer: The system caps at the maximum.
                Module: guild_war
                Fact key: guild_war.doubt.limit""");
    }

    // ===== 入选/排除 =====

    @Test
    void valueOnlyParameterIsExcluded() {
        // 有 Name 但无 Purpose/Value/Unit/ValueType → 值-only → 排除
        KnowledgeClaimRecord claim = claim("pc-empty", SourceType.PARAMETER_TABLE, Authority.SECONDARY,
                "some_param", "some_param", "", "", null, null, "SUPPORTED");

        Optional<String> result = composer.compose(claim, "5.1");

        assertThat(result).isEmpty();
    }

    @Test
    void requirementWithoutValueOrPredicateIsExcluded() {
        KnowledgeClaimRecord claim = claim("rc-empty", SourceType.REQUIREMENT, Authority.PRIMARY,
                "empty.fact", "Subject only", "", "", "STRING", null, "EXTRACTED");

        assertThat(composer.compose(claim, "5.1")).isEmpty();
    }

    @Test
    void testCaseWithoutExpectedOrPreconditionsIsExcluded() {
        KnowledgeClaimRecord claim = claim("tc-empty", SourceType.TEST_CASE, Authority.SECONDARY,
                "empty.test", "Title only", "", "", "STRING", null, "SUPPORTED");

        assertThat(composer.compose(claim, "5.1")).isEmpty();
    }

    @Test
    void excludedSourceTypesReturnEmpty() {
        assertThat(composer.isSourceEligible(SourceType.CODE)).isFalse();
        assertThat(composer.isSourceEligible(SourceType.TEST_RESULT)).isFalse();
        assertThat(composer.isSourceEligible(SourceType.REQUIREMENT_SEMANTIC)).isFalse();
        assertThat(composer.isSourceEligible(SourceType.WIKI)).isFalse();

        KnowledgeClaimRecord codeClaim = claim("code-1", SourceType.CODE, Authority.PRIMARY,
                "code.fact", "SomeCode", "defines", "value", "STRING", null, "VERIFIED");
        assertThat(composer.compose(codeClaim, "5.1")).isEmpty();
    }

    @Test
    void eligibleSourceTypesAccepted() {
        assertThat(composer.isSourceEligible(SourceType.REQUIREMENT)).isTrue();
        assertThat(composer.isSourceEligible(SourceType.PARAMETER_TABLE)).isTrue();
        assertThat(composer.isSourceEligible(SourceType.TEST_CASE)).isTrue();
        assertThat(composer.isSourceEligible(SourceType.DOUBT)).isTrue();
    }

    // ===== 空字段跳过 =====

    @Test
    void blankFieldsAreOmittedNotEmpty() {
        // 有 predicate 但无 objectValue → Value 行不出现
        KnowledgeClaimRecord claim = claim("rc-no-val", SourceType.REQUIREMENT, Authority.PRIMARY,
                "some.fact", "Subject", "Predicate", "", "STRING", null, "VERIFIED");

        String text = composer.compose(claim, "5.1").orElseThrow();

        assertThat(text).contains("Subject: Subject");
        assertThat(text).contains("Predicate: Predicate");
        assertThat(text).doesNotContain("Value:");
        assertThat(text).contains("Module: some");
    }

    @Test
    void moduleExtractedFromFactKeyFirstSegment() {
        // 多段 factKey → module 是第一段
        KnowledgeClaimRecord claim = claim("rc-mod", SourceType.REQUIREMENT, Authority.PRIMARY,
                "economy.shop.discount", "Shop", "offers", "10% off", "STRING", null, "VERIFIED");

        String text = composer.compose(claim, "5.1").orElseThrow();

        assertThat(text).contains("Module: economy");
    }

    @Test
    void moduleWithoutDotIsFullFactKey() {
        KnowledgeClaimRecord claim = claim("rc-nodot", SourceType.REQUIREMENT, Authority.PRIMARY,
                "standalone", "Subject", "Predicate", "Value", "STRING", null, "VERIFIED");

        String text = composer.compose(claim, "5.1").orElseThrow();

        assertThat(text).contains("Module: standalone");
    }

    @Test
    void textHashIsStable() {
        String text = "stable text";
        String hash1 = KnowledgeClaimVectorTextComposer.textHash(text);
        String hash2 = KnowledgeClaimVectorTextComposer.textHash(text);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // SHA-256 hex = 64 chars
    }

    @Test
    void composerVersionExposed() {
        assertThat(composer.composerVersion()).isEqualTo("knowledge-claim-text-v1");
    }

    // ===== 工具 =====

    private KnowledgeClaimRecord claim(String claimId, SourceType sourceType, Authority authority,
                                       String factKey, String subject, String predicate,
                                       String objectValue, String valueType, String unit,
                                       String status) {
        return new KnowledgeClaimRecord(claimId, "immortal", "dv-1", sourceType, authority,
                factKey, subject, predicate, objectValue, valueType, unit, status,
                0.9, null, null, "RULE", "run-1", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
    }
}
