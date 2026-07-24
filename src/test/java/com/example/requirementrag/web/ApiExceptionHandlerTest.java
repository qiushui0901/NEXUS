package com.example.requirementrag.web;

import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.service.RagUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTest {

    @Test
    void mapsUnavailableRagToSafeServiceUnavailableProblem() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(get("/test/rag-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.outcome").value("FAILED"))
                .andExpect(jsonPath("$.warnings[0].code").value("DOCUMENT_RETRIEVAL_UNAVAILABLE"))
                .andExpect(content().string(containsString("RAG 核心检索暂时不可用")))
                .andExpect(content().string(not(containsString("secret.internal"))));
    }

    @RestController
    private static class FailingController {
        @GetMapping("/test/rag-unavailable")
        void fail() {
            throw new RagUnavailableException(List.of(new RagWarning(
                    "qdrant.hybrid_search",
                    "DOCUMENT_RETRIEVAL_UNAVAILABLE",
                    "需求文档检索暂时不可用",
                    12)));
        }
    }
}
