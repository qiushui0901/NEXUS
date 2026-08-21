package com.example.requirementrag.requirement.graph;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.Map;

/** Versioned requirement-graph build, review, retrieval, privacy and budget settings. */
@ConfigurationProperties("app.rag.requirement-graph")
public record RequirementGraphProperties(
        boolean enabled,
        boolean extractionEnabled,
        boolean retrievalEnabled,
        String databasePath,
        int maxEntitiesPerChunk,
        int maxRelationsPerChunk,
        int maxInputChars,
        int maxHops,
        int candidateLimit,
        String extractionModel,
        String extractionPromptVersion,
        String ontologyVersion,
        int schemaVersion,
        int windowOverlapChars,
        int maxWindows,
        int maxModelCalls,
        int maxRetries,
        int maxWallClockSeconds,
        int maxEstimatedTokens,
        int maxConcurrentWorkers,
        int maxGraphRows,
        boolean allowPartialBuild,
        boolean shadowBuild,
        boolean shadowQuery,
        boolean hybridRetrievalEnabled,
        boolean requirePublishedForSearch,
        boolean externalTransmissionAllowed,
        String dataClassification,
        String allowedProvider,
        boolean privacyPolicyRequired,
        Map<String, ProjectPolicy> projectPolicies
) {
    @ConstructorBinding
    public RequirementGraphProperties {
        databasePath = databasePath == null || databasePath.isBlank()
                ? "data/requirement-graph.db" : databasePath.trim();
        maxEntitiesPerChunk = bounded(maxEntitiesPerChunk, 1, 100, 20);
        maxRelationsPerChunk = bounded(maxRelationsPerChunk, 1, 200, 30);
        maxInputChars = bounded(maxInputChars, 1_000, 200_000, 20_000);
        maxHops = bounded(maxHops, 0, 4, 2);
        candidateLimit = bounded(candidateLimit, 1, 200, 40);
        extractionModel = extractionModel == null || extractionModel.isBlank() ? null : extractionModel.trim();
        extractionPromptVersion = textOr(extractionPromptVersion, "v1");
        ontologyVersion = textOr(ontologyVersion, "v1");
        schemaVersion = bounded(schemaVersion, 1, 20, 2);
        windowOverlapChars = bounded(windowOverlapChars, 0, 20_000, 400);
        maxWindows = bounded(maxWindows, 1, 10_000, 500);
        maxModelCalls = bounded(maxModelCalls, 1, 20_000, 500);
        maxRetries = bounded(maxRetries, 0, 10, 2);
        maxWallClockSeconds = bounded(maxWallClockSeconds, 1, 86_400, 900);
        maxEstimatedTokens = bounded(maxEstimatedTokens, 0, 10_000_000, 0);
        maxConcurrentWorkers = bounded(maxConcurrentWorkers, 1, 64, 2);
        maxGraphRows = bounded(maxGraphRows, 100, 100_000, 10_000);
        dataClassification = textOr(dataClassification, "INTERNAL");
        allowedProvider = textOr(allowedProvider, "configured");
        projectPolicies = projectPolicies == null ? Map.of() : Map.copyOf(projectPolicies);
    }

    /** Compatibility constructor for the original v1 configuration shape. */
    public RequirementGraphProperties(boolean enabled, boolean extractionEnabled, boolean retrievalEnabled,
                                      String databasePath, int maxEntitiesPerChunk, int maxRelationsPerChunk,
                                      int maxInputChars, int maxHops, int candidateLimit,
                                      String extractionModel, String extractionPromptVersion) {
        this(enabled, extractionEnabled, retrievalEnabled, databasePath, maxEntitiesPerChunk,
                maxRelationsPerChunk, maxInputChars, maxHops, candidateLimit, extractionModel,
                extractionPromptVersion, "v1", 1, 400, 500, 500, 2, 900, 0, 2, 10_000,
                false, false, false, false, false, false, "INTERNAL", "configured", false, Map.of());
    }

    public record ProjectPolicy(
            boolean enabled,
            boolean externalTransmissionAllowed,
            String allowedProvider,
            String dataClassification,
            boolean storePrompts
    ) {
        public ProjectPolicy {
            allowedProvider = textOr(allowedProvider, "configured");
            dataClassification = textOr(dataClassification, "INTERNAL");
        }
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int bounded(int value, int min, int max, int fallback) {
        if (value == 0) return fallback;
        if (value < min || value > max) {
            throw new IllegalArgumentException("requirement graph setting out of range: " + value);
        }
        return value;
    }
}
