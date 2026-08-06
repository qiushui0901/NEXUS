package com.example.requirementrag.web;

import com.example.requirementrag.code.IncrementalCodeIndexService;
import com.example.requirementrag.config.ProjectRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookControllerTest {

    private static final String SECRET = "test-webhook-secret";

    @Mock
    private ProjectRegistry projectRegistry;

    @Mock
    private IncrementalCodeIndexService incrementalCodeIndexService;

    private WebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new WebhookController(
                projectRegistry, incrementalCodeIndexService, new ObjectMapper(), SECRET);
    }

    @Test
    void acceptsValidHmacSignature() throws Exception {
        byte[] body = """
                {"ref":"refs/heads/main","before":"abc","after":"def",
                 "project":{"path_with_namespace":"group/fengshen-server"}}
                """.strip().getBytes(StandardCharsets.UTF_8);
        when(projectRegistry.resolveProjectIdByGitPath("group/fengshen-server"))
                .thenReturn(Optional.of("fengshen-server"));

        var response = controller.gitlabPush(signatureFor(body), body);

        assertEquals("accepted", response.get("status"));
        assertEquals("fengshen-server", response.get("projectId"));
    }

    @Test
    void rejectsInvalidHmacSignature() {
        byte[] body = "{\"project\":{\"path_with_namespace\":\"group/repo\"}}".getBytes(StandardCharsets.UTF_8);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.gitlabPush("sha256=deadbeef", body));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
    }

    @Test
    void rejectsMissingSignaturePrefix() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.gitlabPush("invalid", body));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
    }

    private String signatureFor(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
