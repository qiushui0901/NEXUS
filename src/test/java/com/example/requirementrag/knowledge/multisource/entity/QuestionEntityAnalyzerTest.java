package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.knowledge.multisource.alignment.BusinessConceptService;
import com.example.requirementrag.knowledge.multisource.alignment.VersionContextService;
import com.example.requirementrag.knowledge.multisource.alignment.AlignmentTestSupport;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.LoadedCode;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityMention;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityName;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityQueryPlan;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.MatchMethod;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.MentionStatus;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.QueryIntent;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.QuestionExtractionRaw;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuestionEntityAnalyzerTest {
    @TempDir Path tempDir;

    private EntityExtractionProperties props(boolean allowLlm) {
        return new EntityExtractionProperties(true, "test-model", 8, 50_000, 200, 50, 100, 100, 0.7, allowLlm, 1);
    }

    private QuestionEntityAnalyzer analyzer(AlignmentTestSupport.Stores stores, boolean allowLlm) {
        EntityExtractionProperties properties = props(allowLlm);
        EntityLlmAssistant llm = new EntityLlmAssistant(null, null, properties,
                new EntityExtractionValidator(properties));
        return new QuestionEntityAnalyzer(stores.alignment(), properties, llm);
    }

    private AlignmentTestSupport.Stores seededStores(String subject) {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        AlignmentTestSupport.seedParameter(stores, "5.1", subject, "100", "combat");
        BusinessConceptService service = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty()),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty())));
        service.build("immortal", "5.1");
        return stores;
    }

    @Test
    void resolvesKnownAliasFromQuestionByRule() {
        AlignmentTestSupport.Stores stores = seededStores("攻击力");
        EntityQueryPlan plan = analyzer(stores, false).analyze("immortal", "角色达到100级时攻击力是多少？");

        assertThat(plan.mentions()).isNotEmpty();
        EntityMention mention = plan.mentions().get(0);
        assertThat(mention.text()).isEqualTo("攻击力");
        assertThat(mention.entityId()).isNotBlank();
        assertThat(mention.status()).isEqualTo(MentionStatus.RESOLVED);
        assertThat(plan.asksNumericValue()).isTrue();
        assertThat(plan.intent()).isEqualTo(QueryIntent.NUMERIC_VALUE);
    }

    @Test
    void detectsIntentFlagsForCurrentStateImplementationQuestion() {
        AlignmentTestSupport.Stores stores = seededStores("攻击力");
        EntityQueryPlan plan = analyzer(stores, false)
                .analyze("immortal", "角色达到100级时攻击力是多少？现在代码实际支持到多少？");

        assertThat(plan.asksCurrentState()).isTrue();
        assertThat(plan.asksImplementation()).isTrue();
        assertThat(plan.asksNumericValue()).isTrue();
        assertThat(plan.intent()).isEqualTo(QueryIntent.CURRENT_STATE);
    }

    @Test
    void extractsRequestedVersionsFromQuery() {
        AlignmentTestSupport.Stores stores = seededStores("等级上限");
        EntityQueryPlan plan = analyzer(stores, false)
                .analyze("immortal", "5.1 版本的角色等级上限是多少？");

        assertThat(plan.requestedVersions()).containsExactly("5.1");
    }

    @Test
    void noMentionWhenAliasAbsent() {
        AlignmentTestSupport.Stores stores = seededStores("攻击力");
        EntityQueryPlan plan = analyzer(stores, false)
                .analyze("immortal", "副本掉落的概率是多少？");

        assertThat(plan.mentions()).isEmpty();
    }

    @Test
    void llmAssistEmptyDoesNotBreakWhenRuleMentionsExist() {
        // 回归：allowLlmAssist=true 且规则已命中，LLM 返回空 → 不抛异常、不误报 LLM 不可用
        AlignmentTestSupport.Stores stores = seededStores("攻击力");
        EntityExtractionProperties properties = props(true);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.entity(QuestionExtractionRaw.class))
                .thenThrow(new RuntimeException("connection refused"));
        EntityLlmAssistant llm = new EntityLlmAssistant(chatClient, null, properties,
                new EntityExtractionValidator(properties));
        QuestionEntityAnalyzer analyzer = new QuestionEntityAnalyzer(stores.alignment(), properties, llm);

        EntityQueryPlan plan = analyzer.analyze("immortal", "角色达到100级时攻击力是多少？");

        // LLM 返回空不得破坏规则结果（曾因 Optional.get() 抛 NoSuchElementException）
        assertThat(plan.mentions()).isNotEmpty();
        assertThat(plan.mentions().get(0).status()).isEqualTo(MentionStatus.RESOLVED);
    }

    @Test
    void llmAssistAddsResolvableMentionAndDropsUnresolvable() {
        // 规则未命中（查询不含 "MaxLevel" 别名），LLM 提议可解析的 MaxLevel 与不可解析的虚构概念
        AlignmentTestSupport.Stores stores = seededStores("MaxLevel");
        EntityExtractionProperties properties = props(true);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.entity(QuestionExtractionRaw.class)).thenReturn(new QuestionExtractionRaw(
                List.of(new EntityName("MaxLevel", List.of(), "ATTRIBUTE", 0.95),
                        new EntityName("虚构概念", List.of(), "ATTRIBUTE", 0.6)),
                "NUMERIC_VALUE", List.of()));
        EntityLlmAssistant llm = new EntityLlmAssistant(chatClient, null, properties,
                new EntityExtractionValidator(properties));
        QuestionEntityAnalyzer analyzer = new QuestionEntityAnalyzer(stores.alignment(), properties, llm);

        EntityQueryPlan plan = analyzer.analyze("immortal", "角色 100 级上限是多少？");

        assertThat(plan.mentions()).anyMatch(m -> "MaxLevel".equals(m.text())
                && m.matchMethod() == MatchMethod.LLM_SELECTED
                && m.status() == MentionStatus.RESOLVED);
        assertThat(plan.mentions()).noneMatch(m -> "虚构概念".equals(m.text()));
        assertThat(plan.mentions()).noneMatch(m -> m.entityId() == null);
    }
}