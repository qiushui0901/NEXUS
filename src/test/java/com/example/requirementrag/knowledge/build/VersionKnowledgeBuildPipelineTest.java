package com.example.requirementrag.knowledge.build;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.config.WikiProperties;
import com.example.requirementrag.knowledge.build.KnowledgeBuildModels.BuildRequest;
import com.example.requirementrag.knowledge.build.KnowledgeBuildModels.BuildStatus;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalPipeline;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VersionKnowledgeBuildPipelineTest {
    @TempDir
    Path temp;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProjectRegistry projectRegistry = mock(ProjectRegistry.class);
    private final QdrantHybridStore documentStore = mock(QdrantHybridStore.class);
    private final RetrievalPipeline retrievalPipeline = mock(RetrievalPipeline.class);
    private final KnowledgeDraftLifecycleService draftLifecycleService = mock(KnowledgeDraftLifecycleService.class);
    private VersionKnowledgeBuildPipeline pipeline;

    @BeforeEach
    void setUp() {
        RagProperties.ProjectConfig project = new RagProperties.ProjectConfig(
                "game", "Game", "game", "server", "requirements_game", "code_game",
                "/repo", "group/game", null, List.of(), List.of(), 1_000_000);
        when(projectRegistry.require("game")).thenReturn(project);
        when(projectRegistry.resolveRequirementCollection("game")).thenReturn("requirements_game");
        when(retrievalPipeline.execute(any())).thenAnswer(invocation -> {
            var request = (com.example.requirementrag.retrieval.pipeline.RetrievalRequest) invocation.getArgument(0);
            return RagOutcome.of(RagOutcomeStatus.NO_RESULTS,
                    new RetrievalBundle(request.query(), RetrievalProfile.WIKI_BUILD, "game",
                            request.documentId(), request.version(), List.of(), List.of()),
                    "knowledge.test", 1, 0);
        });
        WikiProperties properties = new WikiProperties(temp.resolve("wiki").toString(),
                temp.resolve("sources").toString(), temp.resolve("drafts").toString());
        pipeline = new VersionKnowledgeBuildPipeline(mapper, properties, projectRegistry,
                documentStore, retrievalPipeline, draftLifecycleService);
    }

    @Test
    void buildsOnlyChangedFactsAndDoesNotPublishFormalWiki() throws Exception {
        ChunkRecord unchangedBase = chunk("base", "old.html", "same", "未变化规则", "5.0");
        ChunkRecord changedBase = chunk("old", "changed.html", "old-hash", "旧规则", "5.0");
        ChunkRecord unchangedCurrent = chunk("base", "old.html", "same", "未变化规则", "5.1");
        ChunkRecord changedCurrent = chunk("new", "changed.html", "new-hash", "新规则", "5.1");
        when(documentStore.scrollVersion("requirements_game", "requirements", "5.1"))
                .thenReturn(List.of(unchangedCurrent, changedCurrent));
        when(documentStore.scrollVersion("requirements_game", "requirements", "5.0"))
                .thenReturn(List.of(unchangedBase, changedBase));

        var result = pipeline.build(new BuildRequest("game", "5.1", "5.0", "requirements", "base", "head"));

        assertThat(result.status()).isEqualTo(BuildStatus.DRAFT);
        assertThat(result.features()).isEqualTo(1);
        Path draft = Path.of(result.draftPath());
        assertThat(draft.resolve("build.json")).isRegularFile();
        assertThat(draft.resolve("wiki-source.json")).isRegularFile();
        assertThat(temp.resolve("wiki")).doesNotExist();
        assertThat(temp.resolve("sources")).doesNotExist();
        String json = Files.readString(draft.resolve("build.json"));
        assertThat(json).doesNotContain("embedding", "denseVector", "qdrantPoint", "apiKey");
        JsonNode root = mapper.readTree(json);
        assertThat(root.get("features").get(0).get("changeType").asText()).isEqualTo("MODIFIED");
        JsonNode wikiSource = mapper.readTree(Files.readString(draft.resolve("wiki-source.json")));
        assertThat(wikiSource.get("schemaVersion").asInt()).isEqualTo(2);
        assertThat(wikiSource.get("pages")).hasSize(2);
        assertThat(wikiSource.get("pages").get(0).get("pageType").asText()).isEqualTo("OVERVIEW");
        JsonNode featurePage = null;
        for (JsonNode page : wikiSource.get("pages")) {
            if (page.get("pageType").asText().equals("FEATURE")) featurePage = page;
        }
        assertThat(featurePage).isNotNull();
        assertThat(featurePage.get("requirementSources")).hasSize(2);
        assertThat(featurePage.get("testKnowledge").get("executionStatus").asText()).isEqualTo("NOT_AVAILABLE");
        assertThat(featurePage.get("testKnowledge").get("summary").asText()).isEqualTo("没有真实执行快照");
        assertThat(featurePage.get("quality").get("realTestExecution").asBoolean()).isFalse();
        assertThat(featurePage.get("versionChange").get("changeType").asText()).isEqualTo("MODIFIED");
        assertThat(featurePage.get("claims")).isNotEmpty();
        assertThat(featurePage.get("requirementSources").get(0).get("contentHash").asText())
                .isEqualTo("new-hash");
        verify(draftLifecycleService).initializeDraft("game", "5.1", result.buildId(),
                "system", result.generatedAt());
    }

    @Test
    void keepsDifferentFeaturesAsDifferentFeatureIds() throws Exception {
        when(documentStore.scrollVersion("requirements_game", "requirements", "5.1"))
                .thenReturn(List.of(
                        chunk("alpha", "FeatureAlpha.html", "alpha-hash", "Rule alpha", "5.1"),
                        chunk("beta", "FeatureBeta.html", "beta-hash", "Rule beta", "5.1")));

        var result = pipeline.build(new BuildRequest("game", "5.1", null, "requirements", null, "head"));

        JsonNode features = mapper.readTree(Files.readString(Path.of(result.draftPath()).resolve("build.json")))
                .get("features");
        assertThat(features.get(0).get("featureId").asText())
                .isNotEqualTo(features.get(1).get("featureId").asText());
        assertThat(features.toString()).contains("feature-alpha", "feature-beta");
        assertThat(result.missingTests()).isEqualTo(2);
    }

    @Test
    void mergesSameFeatureAcrossFilesIntoOnePage() throws Exception {
        when(documentStore.scrollVersion("requirements_game", "requirements", "5.1"))
                .thenReturn(List.of(
                        chunk("a", "recharge.html", "hash-a", "Rule A", "5.1"),
                        chunk("b", "ui/recharge.html", "hash-b", "Rule B", "5.1")));

        var result = pipeline.build(new BuildRequest("game", "5.1", null, "requirements", null, "head"));

        assertThat(result.features()).isEqualTo(1);
        JsonNode pages = mapper.readTree(Files.readString(Path.of(result.draftPath()).resolve("wiki-source.json")))
                .get("pages");
        JsonNode feature = null;
        for (JsonNode page : pages) {
            if (page.get("pageType").asText().equals("FEATURE")) feature = page;
        }
        assertThat(feature).isNotNull();
        assertThat(feature.get("requirementSources")).hasSize(2);
        assertThat(feature.get("title").asText()).isEqualTo("recharge");
    }

    @Test
    void generatesOverviewAndModulePagesFromCodeEvidence() throws Exception {
        when(documentStore.scrollVersion("requirements_game", "requirements", "5.1"))
                .thenReturn(List.of(chunk("alpha", "FeatureAlpha.html", "alpha-hash", "Rule alpha", "5.1")));
        CodeChunk hit = new CodeChunk("id", "game", "abc123", "src/main/java/com/game/service/AuthService.java",
                "method", "AuthService.login", 10, 20, "code text", "hash");
        org.mockito.Mockito.doReturn(RagOutcome.of(RagOutcomeStatus.SUCCESS,
                        new RetrievalBundle("FeatureAlpha Rule alpha", RetrievalProfile.WIKI_BUILD, "game",
                                "requirements", "5.1", List.of(), List.of(hit)),
                        "knowledge.test", 1, 1))
                .when(retrievalPipeline).execute(any(
                        com.example.requirementrag.retrieval.pipeline.RetrievalRequest.class));

        var result = pipeline.build(new BuildRequest("game", "5.1", null, "requirements", null, "head"));

        JsonNode pages = mapper.readTree(Files.readString(Path.of(result.draftPath()).resolve("wiki-source.json")))
                .get("pages");
        assertThat(pages).hasSize(3);
        assertThat(pages.get(0).get("featureId").asText()).isEqualTo("overview");
        assertThat(pages.get(0).get("pageType").asText()).isEqualTo("OVERVIEW");
        JsonNode module = pages.get(1);
        assertThat(module.get("pageType").asText()).isEqualTo("MODULE");
        assertThat(module.get("featureId").asText()).isEqualTo("module-com");
        assertThat(module.get("codeEntries").get(0).get("filePath").asText())
                .isEqualTo("src/main/java/com/game/service/AuthService.java");
        assertThat(module.get("claims")).isNotEmpty();
    }

    @Test
    void rejectsUnsafePathIdentifiers() {
        assertThatThrownBy(() -> pipeline.build(new BuildRequest(
                "../game", "5.1", null, "requirements", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectId");
    }

    private ChunkRecord chunk(String parentId, String filename, String hash, String text, String version) {
        return new ChunkRecord(parentId + "-child", "requirements", version, filename, parentId,
                text, text, hash, 1, 1);
    }
}
