package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.requirement.semantic.RequirementSemanticBuildService;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildRecord;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildRequest;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildResult;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildStatus;
import com.example.requirementrag.requirement.semantic.SQLiteRequirementSemanticStore;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 语义构建 Controller：委托构建服务并强制项目注册与访问控制。 */
class RequirementSemanticBuildControllerTest {
    private final RequirementSemanticBuildService buildService = mock(RequirementSemanticBuildService.class);
    private final SQLiteRequirementSemanticStore store = mock(SQLiteRequirementSemanticStore.class);
    private final ProjectRegistry projectRegistry = mock(ProjectRegistry.class);
    private final ProjectAccessGuard accessGuard = mock(ProjectAccessGuard.class);
    private final HttpServletRequest httpRequest = mock(HttpServletRequest.class);

    private final RequirementSemanticBuildController controller = new RequirementSemanticBuildController(
            buildService, store, projectRegistry, accessGuard);

    @Test
    void buildDelegatesToServiceAndEnforcesProjectAccess() {
        SemanticBuildResult result = new SemanticBuildResult("p1", "doc", "5.1", "rev-1",
                "test-model", "requirement-semantic-v1", "v1",
                1, 0, 1, 0, SemanticBuildStatus.SUCCESS, List.of(), List.of());
        when(buildService.build(any())).thenReturn(result);

        SemanticBuildResult actual = controller.build(
                new SemanticBuildRequest("p1", "doc", "5.1", null), httpRequest);

        assertThat(actual).isEqualTo(result);
        verify(projectRegistry).require("p1");
        verify(accessGuard).requireProjectAccess(httpRequest, "p1");
        verify(buildService).build(any());
    }

    @Test
    void latestBuildReadsMostRecentBuildRecord() {
        SemanticBuildRecord record = new SemanticBuildRecord("build-1", "p1", "doc", "5.1",
                "rev-1", "test-model", "requirement-semantic-v1", "v1",
                SemanticBuildStatus.SUCCESS, 1, 0, 1, 0, List.of(),
                Instant.now(), Instant.now(), true);
        when(store.latestBuild("p1", "doc", "5.1")).thenReturn(Optional.of(record));

        Optional<SemanticBuildRecord> actual = controller.latestBuild("p1", "doc", "5.1", httpRequest);

        assertThat(actual).contains(record);
        verify(projectRegistry).require("p1");
        verify(accessGuard).requireProjectAccess(httpRequest, "p1");
    }
}
