package com.example.requirementrag.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "logging.structured.format.console=",
        "management.tracing.sampling.probability=0",
        "app.rag.knowledge.bootstrap-enabled=false",
        "app.rag.auth.enabled=true",
        "app.rag.auth.users[0].username=mcp-test",
        "app.rag.auth.users[0].api-key=mcp-test-key",
        "app.rag.auth.users[0].role=DEVELOPER",
        "app.rag.auth.users[0].projects[0]=*"
})
class McpHttpIntegrationTest {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void requiresApiKeyAndDiscoversTenToolsPromptsAndWikiResourceTemplate() throws Exception {
        HttpResponse<String> unauthorized = post(initialize(), null, null);
        assertEquals(401, unauthorized.statusCode());

        HttpResponse<String> initialized = post(initialize(), "mcp-test-key", null);
        assertEquals(200, initialized.statusCode());
        assertTrue(initialized.body().contains("\"protocolVersion\""));
        String sessionId = initialized.headers().firstValue("mcp-session-id").orElseThrow();

        HttpResponse<String> notification = post("""
                {"jsonrpc":"2.0","method":"notifications/initialized","params":{}}
                """, "mcp-test-key", sessionId);
        assertTrue(notification.statusCode() == 200 || notification.statusCode() == 202);

        HttpResponse<String> tools = post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """, "mcp-test-key", sessionId);
        assertEquals(200, tools.statusCode());
                assertTrue(tools.body().contains("nexus_search_requirements"));
        assertTrue(tools.body().contains("nexus_search_code"));
        assertTrue(tools.body().contains("nexus_get_source"));
        assertTrue(tools.body().contains("nexus_development_plan"));
        assertTrue(tools.body().contains("nexus_wiki_page"));
        assertTrue(tools.body().contains("nexus_version_diff"));
        assertTrue(tools.body().contains("nexus_code_graph"));
        assertTrue(tools.body().contains("nexus_impact_analysis"));
        assertTrue(tools.body().contains("nexus_review_doubts"));
        assertTrue(tools.body().contains("nexus_conflict_check"));

        HttpResponse<String> prompts = post("""
                {"jsonrpc":"2.0","id":5,"method":"prompts/list","params":{}}
                """, "mcp-test-key", sessionId);
        assertEquals(200, prompts.statusCode());
        assertTrue(prompts.body().contains("nexus_implement_requirement"));
        assertTrue(prompts.body().contains("nexus_review_requirement"));
        assertTrue(prompts.body().contains("nexus_assess_change_impact"));

        HttpResponse<String> resources = post("""
                {"jsonrpc":"2.0","id":4,"method":"resources/templates/list","params":{}}
                """, "mcp-test-key", sessionId);
        assertEquals(200, resources.statusCode());
        assertTrue(resources.body().contains("nexus://wiki/{projectId}/{version}/{featureId}"));

        HttpResponse<String> source = post("""
                {
                  "jsonrpc":"2.0",
                  "id":3,
                  "method":"tools/call",
                  "params":{
                    "name":"nexus_get_source",
                    "arguments":{"filePath":"pom.xml","startLine":1,"endLine":5}
                  }
                }
                """, "mcp-test-key", sessionId);
        assertEquals(200, source.statusCode());
        assertTrue(source.body().contains("resolved"));
        assertTrue(source.body().contains("evidence"));
        assertTrue(source.body().contains("code:"));
        assertTrue(source.body().contains("truncated"));
    }

    private HttpResponse<String> post(String body, String apiKey, String sessionId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (apiKey != null) {
            request.header("X-API-Key", apiKey);
        }
        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String initialize() {
        return """
                {
                  "jsonrpc":"2.0",
                  "id":1,
                  "method":"initialize",
                  "params":{
                    "protocolVersion":"2025-06-18",
                    "capabilities":{},
                    "clientInfo":{"name":"nexus-test","version":"1.0"}
                  }
                }
                """;
    }
}
