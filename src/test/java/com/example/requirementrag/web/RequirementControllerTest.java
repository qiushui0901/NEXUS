package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.knowledge.management.KnowledgeIngestionTracker;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.SourceType;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.TriggerType;
import com.example.requirementrag.model.IngestResponse;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.project.BusinessProjectCatalogService;
import com.example.requirementrag.service.DoubtExportService;
import com.example.requirementrag.service.RequirementIngestionService;
import com.example.requirementrag.service.ReviewFacadeService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequirementControllerTest {

    @Test
    void multipartUploadUsesUploadTrackingContextAndCompletesRun() throws Exception {
        RequirementIngestionService ingestion = mock(RequirementIngestionService.class);
        ReviewFacadeService review = mock(ReviewFacadeService.class);
        DoubtExportService export = mock(DoubtExportService.class);
        ProjectRegistry registry = mock(ProjectRegistry.class);
        ProjectAccessGuard accessGuard = mock(ProjectAccessGuard.class);
        KnowledgeIngestionTracker tracker = mock(KnowledgeIngestionTracker.class);
        ObjectProvider<KnowledgeIngestionTracker> trackerProvider = mock(ObjectProvider.class);
        ObjectProvider<BusinessProjectCatalogService> businessProvider = mock(ObjectProvider.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        KnowledgeIngestionTracker.Context context =
                new KnowledgeIngestionTracker.Context("orders:requirement", "run-1", "2.0", true);
        when(trackerProvider.getIfAvailable()).thenReturn(tracker);
        when(businessProvider.getIfAvailable()).thenReturn(null);
        when(registry.resolveRequirementCollection("orders")).thenReturn("requirements_orders");
        when(tracker.start("orders", "orders", "requirements_orders", "2.0",
                TriggerType.MANUAL, SourceType.UPLOAD)).thenReturn(context);
        when(ingestion.ingest(eq("requirements_orders"), any(), eq("2.0"), eq("orders-doc"), eq(context)))
                .thenReturn(new IngestResponse("orders-doc", "2.0", 4, java.util.List.of()));

        RequirementController controller = new RequirementController(
                ingestion, review, export, registry, accessGuard, trackerProvider, businessProvider);

        IngestResponse response = controller.ingest(
                new MockMultipartFile("file", "requirements.md", "text/markdown", "rules".getBytes()),
                "2.0", "orders-doc", "orders", request);

        assertThat(response.chunks()).isEqualTo(4);
        verify(accessGuard).requireProjectAccess(request, "orders");
        verify(tracker).start("orders", "orders", "requirements_orders", "2.0",
                TriggerType.MANUAL, SourceType.UPLOAD);
        verify(ingestion).ingest(eq("requirements_orders"), any(), eq("2.0"), eq("orders-doc"), eq(context));
        verify(tracker).complete(context, 4);
    }
}
