package com.example.requirementrag;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "logging.structured.format.console=",
        "management.tracing.sampling.probability=0",
        "app.rag.knowledge.bootstrap-enabled=false"
})
class RequirementRagApplicationTest {

    @Test
    void contextLoads() {
    }
}
