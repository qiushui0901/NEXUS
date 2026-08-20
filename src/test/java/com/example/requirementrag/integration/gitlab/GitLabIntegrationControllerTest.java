package com.example.requirementrag.integration.gitlab;

import com.example.requirementrag.model.Permission;
import com.example.requirementrag.web.RequiresPermission;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitLabIntegrationControllerTest {

    @Test
    void managementApiRequiresAdminPermission() {
        RequiresPermission permission =
                GitLabIntegrationController.class.getAnnotation(RequiresPermission.class);

        assertThat(permission).isNotNull();
        assertThat(permission.value()).isEqualTo(Permission.ADMIN);
    }

    @Test
    void delegatesProjectReEnable() {
        GitLabSyncService service = mock(GitLabSyncService.class);
        GitLabIntegrationController controller = new GitLabIntegrationController(service);
        GitLabManagedProject.View view = mock(GitLabManagedProject.View.class);
        when(service.enable("project-a")).thenReturn(view);

        assertThat(controller.enable("project-a")).isSameAs(view);
        verify(service).enable("project-a");
    }
}
