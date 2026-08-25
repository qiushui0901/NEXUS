package com.example.requirementrag.requirement.semantic;

import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationInput;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationOutcome;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationResult;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequirementSemanticAnnotationServiceTest {

    @Test
    void classifiesTimeoutExceptions() {
        assertThat(RequirementSemanticAnnotationService.classify(
                new RuntimeException("Request timed out after 5000ms")))
                .isEqualTo(SemanticErrorCode.MODEL_TIMEOUT);
        assertThat(RequirementSemanticAnnotationService.classify(
                new RuntimeException("upstream deadline exceeded")))
                .isEqualTo(SemanticErrorCode.MODEL_TIMEOUT);
    }

    @Test
    void classifiesRateLimitExceptions() {
        assertThat(RequirementSemanticAnnotationService.classify(
                new RuntimeException("HTTP 429 Too Many Requests")))
                .isEqualTo(SemanticErrorCode.MODEL_RATE_LIMITED);
        assertThat(RequirementSemanticAnnotationService.classify(
                new RuntimeException("quota exceeded")))
                .isEqualTo(SemanticErrorCode.MODEL_RATE_LIMITED);
    }

    @Test
    void classifiesJsonParseFailures() {
        JsonProcessingException jackson = MismatchedInputException.from(
                null, Object.class, "unrecognized token");
        assertThat(RequirementSemanticAnnotationService.classify(
                new RuntimeException("entity conversion failed", jackson)))
                .isEqualTo(SemanticErrorCode.JSON_PARSE_FAILED);
    }

    @Test
    void classifiesUnknownFailuresAsModelUnavailable() {
        assertThat(RequirementSemanticAnnotationService.classify(
                new RuntimeException("connection refused")))
                .isEqualTo(SemanticErrorCode.MODEL_UNAVAILABLE);
    }

    @Test
    void classifiesNullPointerAsSchemaInvalidNotModelUnavailable() {
        assertThat(RequirementSemanticAnnotationService.classify(
                new NullPointerException("Cannot invoke ... because raw is null")))
                .isEqualTo(SemanticErrorCode.SCHEMA_INVALID);
    }

    @Test
    void nullArrayMembersNeverFallThroughAsModelUnavailable() {
        // JSON 契约中的 null 成员（如 conditions:[null]）在绑定层抛异常（Jackson 受检异常，
        // 生产路径会被 Spring AI 包装为 RuntimeException）；无论以何种形式冒出，
        // 都不能被误判为 MODEL_UNAVAILABLE。
        com.fasterxml.jackson.databind.ObjectMapper objectMapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            objectMapper.readValue(
                    "{\"conditions\":[null],\"entities\":[],\"events\":[],\"numericFacts\":[],"
                            + "\"claims\":[],\"questionExpansions\":[],\"uncertainties\":[],"
                            + "\"missingContext\":[],\"selfContained\":true}",
                    SemanticAnnotationResult.class);
            throw new AssertionError("conditions:[null] 必须在绑定层被拒绝");
        } catch (Exception exception) {
            RuntimeException runtime = exception instanceof RuntimeException runtimeException
                    ? runtimeException : new RuntimeException(exception);
            SemanticErrorCode code = RequirementSemanticAnnotationService.classify(runtime);
            assertThat(code).isIn(SemanticErrorCode.SCHEMA_INVALID, SemanticErrorCode.JSON_PARSE_FAILED);
        }
    }

    @Test
    void mapsStableExceptionCodesToErrorCodes() {
        assertThat(RequirementSemanticAnnotationService.errorCode("SEMANTIC_SCHEMA_INVALID"))
                .isEqualTo(SemanticErrorCode.SCHEMA_INVALID);
        assertThat(RequirementSemanticAnnotationService.errorCode("SEMANTIC_EVIDENCE_UNAVAILABLE"))
                .isEqualTo(SemanticErrorCode.EVIDENCE_UNAVAILABLE);
        assertThat(RequirementSemanticAnnotationService.errorCode("SEMANTIC_NUMERIC_INVALID"))
                .isEqualTo(SemanticErrorCode.NUMERIC_INVALID);
        assertThat(RequirementSemanticAnnotationService.errorCode(null))
                .isEqualTo(SemanticErrorCode.SCHEMA_INVALID);
        assertThat(RequirementSemanticAnnotationService.errorCode("SEMANTIC_NOT_A_CODE"))
                .isEqualTo(SemanticErrorCode.SCHEMA_INVALID);
    }

    @Test
    void retryAttemptsAreCappedByRemainingModelCalls() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(any(String.class))).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.entity(SemanticAnnotationResult.class))
                .thenThrow(new RuntimeException("connection refused"));

        // maxRetries=2 → 单窗口最多 3 次调用；但剩余预算优先约束总次数。
        RequirementSemanticProperties properties = new RequirementSemanticProperties(
                true, false, false, false, "", "test-model", "requirement-semantic-v1", "v1",
                12_000, 30, 30, 30, 30, 20, 30, 2, 1_000, 1_800, 1_000_000, 400, true);
        RequirementSemanticAnnotationService service = new RequirementSemanticAnnotationService(
                chatClient, null, properties,
                new RequirementSemanticPromptService(properties),
                new RequirementSemanticAnnotationValidator(properties));
        String raw = "玩家达到30级后开放成长基金。";
        SemanticAnnotationInput input = new SemanticAnnotationInput(
                "p1", "doc", "5.1", "file.md|parent-1|0", "parent-1", null,
                0, 0, raw.length(), "file.md", 0, "", "", raw, "hash");

        SemanticAnnotationOutcome single = service.annotate(input, 1);
        assertThat(single.modelCalls()).isEqualTo(1);
        assertThat(single.attempts()).isEqualTo(1);
        assertThat(single.errorCode()).isEqualTo(SemanticErrorCode.MODEL_UNAVAILABLE);

        SemanticAnnotationOutcome full = service.annotate(input, 10);
        assertThat(full.modelCalls()).isEqualTo(3);
        assertThat(full.attempts()).isEqualTo(3);
        verify(chatClient, times(4)).prompt();
    }
}
