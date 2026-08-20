package com.example.requirementrag.knowledge;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.KnowledgeEntry;
import com.example.requirementrag.service.TextPreprocessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZipHtmlKnowledgeLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void appliesMaxImagesPerDocumentBeforeCallingVision() throws Exception {
        RagProperties properties = mock(RagProperties.class);
        RagProperties.Knowledge knowledge = new RagProperties.Knowledge(
                false, null, null, "requirements", "v1", null, null, 1);
        when(properties.knowledge()).thenReturn(knowledge);
        when(properties.vision()).thenReturn(new RagProperties.Vision(true, "vision", 1, 1_000L, 2));
        TextPreprocessor preprocessor = new TextPreprocessor();
        RequirementImageCaptioner captioner = mock(RequirementImageCaptioner.class);
        when(captioner.describe(any(), any(), any(), any())).thenReturn("图像说明");
        @SuppressWarnings("unchecked")
        ObjectProvider<RequirementImageCaptioner> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(captioner);
        ZipHtmlKnowledgeLoader loader = new ZipHtmlKnowledgeLoader(properties, preprocessor, provider);
        Path zip = tempDir.resolve("requirements.zip");
        writeZip(zip);

        List<KnowledgeEntry> entries = loader.load(zip, (processed, name) -> { });

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.text()).contains("图像说明");
            assertThat(entry.text()).contains("alt-2");
        });
        verify(captioner, times(1)).describe(any(), any(), any(), any());
    }

    @Test
    void appliesConfiguredConcurrencyAndTimeoutToCaptionTasks() throws Exception {
        RagProperties properties = mock(RagProperties.class);
        RagProperties.Knowledge knowledge = new RagProperties.Knowledge(
                false, null, null, "requirements", "v1", null, null, 1);
        when(properties.knowledge()).thenReturn(knowledge);
        when(properties.vision()).thenReturn(new RagProperties.Vision(true, "vision", 2, 20L, 2));
        RequirementImageCaptioner captioner = mock(RequirementImageCaptioner.class);
        when(captioner.describe(any(), any(), any(), any())).thenAnswer(invocation -> {
            Thread.sleep(200L);
            return "late-caption";
        });
        @SuppressWarnings("unchecked")
        ObjectProvider<RequirementImageCaptioner> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(captioner);
        ZipHtmlKnowledgeLoader loader = new ZipHtmlKnowledgeLoader(properties, new TextPreprocessor(), provider);
        Path zip = tempDir.resolve("timeout.zip");
        writeZip(zip);

        List<KnowledgeEntry> entries = loader.load(zip, (processed, name) -> { });

        assertThat(entries).singleElement().satisfies(entry ->
                assertThat(entry.text()).doesNotContain("late-caption"));
        verify(captioner, times(2)).describe(any(), any(), any(), any());
    }

    private void writeZip(Path zip) throws Exception {
        String html = "<html><body><h1>需求</h1>"
                + "<p><img src=\"img-1.png\" alt=\"alt-1\"><img src=\"img-2.png\" alt=\"alt-2\">"
                + "<img src=\"img-3.png\" alt=\"alt-3\"></p></body></html>";
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            add(output, "v1/requirements.html", html.getBytes(StandardCharsets.UTF_8));
            add(output, "img-1.png", new byte[]{1, 2, 3});
            add(output, "img-2.png", new byte[]{4, 5, 6});
            add(output, "img-3.png", new byte[]{7, 8, 9});
        }
    }

    private void add(ZipOutputStream output, String name, byte[] bytes) throws Exception {
        output.putNextEntry(new ZipEntry(name));
        output.write(bytes);
        output.closeEntry();
    }
}
