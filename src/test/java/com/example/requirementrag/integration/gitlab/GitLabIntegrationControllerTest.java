package com.example.requirementrag.integration.gitlab;

import com.example.requirementrag.model.Permission;
import com.example.requirementrag.web.RequiresPermission;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitLabIntegrationControllerTest {

    @Test
    void managementApiRequiresAdminPermission() {
        RequiresPermission permission =
                GitLabIntegrationController.class.getAnnotation(RequiresPermission.class);

        assertThat(permission).isNotNull();
        assertThat(permission.value()).isEqualTo(Permission.ADMIN);
    }
}
