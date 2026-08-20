package com.example.requirementrag.project;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessProjectControllerContractTest {

    @Test
    void publicRepositoryViewDoesNotExposeServerFilesystemPaths() {
        assertThat(Arrays.stream(BusinessProjectController.RepositoryView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .contains("id", "name", "gitPath", "codeCollection", "enabled")
                .doesNotContain("repositoryPath");
    }
}
