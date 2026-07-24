package com.example.requirementrag.web;

import com.example.requirementrag.code.GitDiffService.GitDiffResult;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.versioning.VersionComparisonService;
import com.example.requirementrag.versioning.VersionManifestService;
import com.example.requirementrag.versioning.VersionModels.ManifestStatus;
import com.example.requirementrag.versioning.VersionModels.RequirementDiff;
import com.example.requirementrag.versioning.VersionModels.TestDiff;
import com.example.requirementrag.versioning.VersionModels.VersionComparisonReport;
import com.example.requirementrag.versioning.VersionModels.VersionManifest;
import com.example.requirementrag.versioning.VersionModels.WikiDiff;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VersionControllerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        VersionManifestService manifests = mock(VersionManifestService.class);
        VersionComparisonService comparisons = mock(VersionComparisonService.class);
        VersionManifest saved = manifest("5.1");
        when(manifests.save(any())).thenReturn(saved);
        when(manifests.list("game")).thenReturn(List.of(saved));
        when(manifests.get("game", "5.1")).thenReturn(saved);
        when(comparisons.compare("game", "5.0", "5.1")).thenReturn(new VersionComparisonReport(
                "game", "5.0", "5.1", "2026-07-24T00:00:00Z", RequirementDiff.unavailable(),
                GitDiffResult.unavailable(), TestDiff.unavailable(), WikiDiff.unavailable(), List.of()));
        VersionController controller = new VersionController(manifests, comparisons,
                mock(ProjectRegistry.class), mock(ProjectAccessGuard.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void savesListsReadsAndComparesVersions() throws Exception {
        mvc.perform(put("/api/versions/manifests").contentType(MediaType.APPLICATION_JSON).content("""
                {"projectId":"game","version":"5.1","requirementDocumentId":"requirements",
                 "requirementVersion":"5.1","codeCommit":"bbbbbbb","status":"DRAFT"}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value("5.1"));
        mvc.perform(get("/api/versions/manifests").param("projectId", "game"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].version").value("5.1"));
        mvc.perform(get("/api/versions/manifests/5.1").param("projectId", "game"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.projectId").value("game"));
        mvc.perform(get("/api/versions/compare").param("projectId", "game")
                        .param("fromVersion", "5.0").param("toVersion", "5.1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fromVersion").value("5.0"));
    }

    @Test
    void rejectsInvalidManifestBody() throws Exception {
        mvc.perform(put("/api/versions/manifests").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"5.1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void declaresWriteAndReadPermissions() throws Exception {
        assertThat(VersionController.class.getMethod("save", VersionManifest.class, HttpServletRequest.class)
                .getAnnotation(RequiresPermission.class).value()).isEqualTo(Permission.WRITE);
        assertThat(VersionController.class.getMethod("compare", String.class, String.class, String.class,
                HttpServletRequest.class).getAnnotation(RequiresPermission.class).value())
                .isEqualTo(Permission.PUBLIC_READ);
    }

    private VersionManifest manifest(String version) {
        return new VersionManifest(1, "game", version, "5.0", "requirements", version,
                "aaaaaaa", "bbbbbbb", null, version, null, ManifestStatus.DRAFT,
                "2026-07-24T00:00:00Z", "2026-07-24T00:00:00Z", List.of());
    }
}
