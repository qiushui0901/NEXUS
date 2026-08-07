package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.WikiProperties;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.wiki.WikiGenerationService;
import com.example.requirementrag.wiki.WikiRepository;
import com.example.requirementrag.wiki.WikiStalenessService;
import com.example.requirementrag.wiki.module.ModuleKnowledgeBuildService;
import com.example.requirementrag.wiki.module.ModuleStaleRebuildService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WikiControllerTest {
    @TempDir
    Path temp;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        WikiProperties properties = new WikiProperties(
                temp.resolve("wiki").toString(),
                Path.of("data/wiki-sources").toAbsolutePath().toString());
        WikiRepository repository = new WikiRepository(mapper, properties);
        WikiGenerationService generationService = new WikiGenerationService(mapper, properties, repository);
        generationService.generate("immortal-game-service", "5.1");

        ProjectRegistry projectRegistry = mock(ProjectRegistry.class);
        ProjectAccessGuard accessGuard = mock(ProjectAccessGuard.class);
        when(accessGuard.currentUser(any())).thenReturn(UserContext.defaultAdmin());
        WikiStalenessService stalenessService = mock(WikiStalenessService.class);
        ModuleKnowledgeBuildService moduleBuildService = mock(ModuleKnowledgeBuildService.class);
        ModuleStaleRebuildService moduleRebuildService = mock(ModuleStaleRebuildService.class);
        WikiController controller = new WikiController(repository, generationService, stalenessService,
                moduleBuildService, moduleRebuildService, projectRegistry, accessGuard);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void browsesAndRegeneratesVersionedWikiThroughHttpApis() throws Exception {
        mvc.perform(get("/api/wiki/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].projectId").value("immortal-game-service"));
        mvc.perform(get("/api/wiki/versions").param("projectId", "immortal-game-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value("5.1"));
        mvc.perform(get("/api/wiki/index")
                        .param("projectId", "immortal-game-service")
                        .param("version", "5.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages.length()").value(8));
        mvc.perform(get("/api/wiki/page")
                        .param("projectId", "immortal-game-service")
                        .param("version", "5.1")
                        .param("featureId", "grow-fund"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("成长基金"));
        mvc.perform(post("/api/wiki/generate")
                        .param("projectId", "immortal-game-service")
                        .param("version", "5.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageCount").value(8));
    }

    @Test
    void returnsDeterministicClientErrorsForUnsafeOrMissingArtifacts() throws Exception {
        mvc.perform(get("/api/wiki/page")
                        .param("projectId", "immortal-game-service")
                        .param("version", "5.1")
                        .param("featureId", "../grow-fund"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/wiki/page")
                        .param("projectId", "immortal-game-service")
                        .param("version", "5.1")
                        .param("featureId", "missing"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/wiki/generate")
                        .param("projectId", "immortal-game-service")
                        .param("version", "9.9"))
                .andExpect(status().isNotFound());
    }

    @Test
    void requiresReadPermissionForBrowsingAndWritePermissionForGeneration() throws Exception {
        assertThat(WikiController.class.getMethod("projects", HttpServletRequest.class)
                .getAnnotation(RequiresPermission.class).value()).isEqualTo(Permission.PUBLIC_READ);
        assertThat(WikiController.class.getMethod("generate", String.class, String.class, HttpServletRequest.class)
                .getAnnotation(RequiresPermission.class).value()).isEqualTo(Permission.WRITE);
    }
}
