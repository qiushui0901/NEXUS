package com.example.requirementrag.integration.gitlab;

import com.example.requirementrag.model.Permission;
import com.example.requirementrag.web.RequiresPermission;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitLabConnectionControllerTest {

    @Test
    void requiresAdminPermissionAndDelegatesConnectionOperations() {
        RequiresPermission permission =
                GitLabConnectionController.class.getAnnotation(RequiresPermission.class);
        assertThat(permission).isNotNull();
        assertThat(permission.value()).isEqualTo(Permission.ADMIN);

        GitLabAccountService service = mock(GitLabAccountService.class);
        GitLabProjectImportService importService = mock(GitLabProjectImportService.class);
        GitLabConnectionController controller =
                new GitLabConnectionController(service, importService);
        GitLabAccountService.CreateConnection create =
                new GitLabAccountService.CreateConnection(
                        "公司 GitLab", "https://gitlab.example.com", "token");
        GitLabAccountService.Reauthorize reauthorize =
                new GitLabAccountService.Reauthorize("new-token");
        GitLabConnection.View view = new GitLabConnection.View(
                "connection-a", "公司 GitLab", "https://gitlab.example.com",
                "gitlab.example.com", "qiushui", "秋水", GitLabConnectionStatus.ACTIVE,
                "now", null, "now", "now");
        GitLabAccountService.ProjectPage page =
                new GitLabAccountService.ProjectPage(List.of(), 0, 50, 0);
        when(service.create(create)).thenReturn(view);
        when(service.list()).thenReturn(List.of(view));
        when(service.require("connection-a")).thenReturn(view);
        when(service.verify("connection-a")).thenReturn(view);
        when(service.reauthorize("connection-a", reauthorize)).thenReturn(view);
        when(service.disable("connection-a")).thenReturn(view);
        when(service.projects("connection-a", 0, 50, "order")).thenReturn(page);
        List<GitLabAccountService.BranchView> branches =
                List.of(new GitLabAccountService.BranchView("main", true, true, false));
        when(service.branches("connection-a", 11)).thenReturn(branches);
        GitLabProjectImportService.BatchImportRequest importRequest =
                new GitLabProjectImportService.BatchImportRequest(List.of());
        GitLabProjectImportService.BatchImportResponse importResponse =
                new GitLabProjectImportService.BatchImportResponse(List.of(), 0, 0);
        when(importService.importProjects("connection-a", importRequest))
                .thenReturn(importResponse);

        assertThat(controller.create(create)).isSameAs(view);
        assertThat(controller.list()).containsExactly(view);
        assertThat(controller.get("connection-a")).isSameAs(view);
        assertThat(controller.verify("connection-a")).isSameAs(view);
        assertThat(controller.reauthorize("connection-a", reauthorize)).isSameAs(view);
        assertThat(controller.disable("connection-a")).isSameAs(view);
        assertThat(controller.projects("connection-a", 0, 50, "order")).isSameAs(page);
        assertThat(controller.branches("connection-a", 11)).isSameAs(branches);
        assertThat(controller.imports("connection-a", importRequest)).isSameAs(importResponse);

        verify(service).create(create);
        verify(service).list();
        verify(service).require("connection-a");
        verify(service).verify("connection-a");
        verify(service).reauthorize("connection-a", reauthorize);
        verify(service).disable("connection-a");
        verify(service).projects("connection-a", 0, 50, "order");
        verify(service).branches("connection-a", 11);
        verify(importService).importProjects("connection-a", importRequest);
    }
}
