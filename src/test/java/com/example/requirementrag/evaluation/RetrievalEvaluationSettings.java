package com.example.requirementrag.evaluation;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Frozen, environment-selectable settings for one isolated retrieval evaluation process. */
record RetrievalEvaluationSettings(
        String datasetResource,
        EvaluationMode mode,
        Path outputDirectory,
        int warmupRuns,
        int repetitions,
        Optional<String> baselineResource
) {
    static final String DEFAULT_BASELINE_RESOURCE = "evaluation/retrieval-baseline-v0.7.json";
    static final String DATASET_ENV = "RETRIEVAL_EVAL_DATASET_RESOURCE";
    static final String BASELINE_ENV = "RETRIEVAL_EVAL_BASELINE_RESOURCE";
    static final String MODE_ENV = "RETRIEVAL_EVAL_MODE";
    static final String OUTPUT_ENV = "RETRIEVAL_EVAL_OUTPUT_DIRECTORY";
    static final String WARMUP_ENV = "RETRIEVAL_EVAL_WARMUP_RUNS";
    static final String REPETITIONS_ENV = "RETRIEVAL_EVAL_REPETITIONS";

    static RetrievalEvaluationSettings fromEnvironment() {
        return from(System.getenv());
    }

    static RetrievalEvaluationSettings from(Map<String, String> environment) {
        String dataset = normalized(environment.get(DATASET_ENV))
                .orElse(RetrievalEvaluationDataset.DEFAULT_RESOURCE);
        EvaluationMode mode = EvaluationMode.parse(environment.get(MODE_ENV));
        Path output = Path.of(normalized(environment.get(OUTPUT_ENV))
                .orElse(Path.of("target", "retrieval-evaluation", mode.id()).toString()));
        int warmupRuns = nonNegative(environment.get(WARMUP_ENV), 0, WARMUP_ENV);
        int repetitions = positive(environment.get(REPETITIONS_ENV), 1, REPETITIONS_ENV);
        Optional<String> baseline = environment.containsKey(BASELINE_ENV)
                ? normalized(environment.get(BASELINE_ENV))
                : Optional.empty();
        return new RetrievalEvaluationSettings(dataset, mode, output, warmupRuns, repetitions, baseline);
    }

    private static int nonNegative(String value, int fallback, String name) {
        int parsed = integer(value, fallback, name);
        if (parsed < 0) throw new IllegalArgumentException(name + " must be >= 0");
        return parsed;
    }

    private static int positive(String value, int fallback, String name) {
        int parsed = integer(value, fallback, name);
        if (parsed <= 0) throw new IllegalArgumentException(name + " must be > 0");
        return parsed;
    }

    private static int integer(String value, int fallback, String name) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static Optional<String> normalized(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        return Optional.of(value.trim());
    }

    enum EvaluationMode {
        BASELINE_0_7("0.7-baseline"),
        RERANK_0_8("0.8-rerank"),
        QUALITY_0_8_1("0.8.1-quality"),
        DOCUMENT_V2_0_8_2("0.8.2-document-v2"),
        CODE_V3_0_8_3("0.8.3-code-v3");

        private final String id;

        EvaluationMode(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }

        static EvaluationMode parse(String value) {
            if (value == null || value.isBlank() || RERANK_0_8.id.equalsIgnoreCase(value.trim())) {
                return RERANK_0_8;
            }
            if (BASELINE_0_7.id.equalsIgnoreCase(value.trim())) return BASELINE_0_7;
            if (QUALITY_0_8_1.id.equalsIgnoreCase(value.trim())) return QUALITY_0_8_1;
            if (DOCUMENT_V2_0_8_2.id.equalsIgnoreCase(value.trim())) return DOCUMENT_V2_0_8_2;
            if (CODE_V3_0_8_3.id.equalsIgnoreCase(value.trim())) return CODE_V3_0_8_3;
            throw new IllegalArgumentException(
                    MODE_ENV + " must be 0.7-baseline, 0.8-rerank, 0.8.1-quality, "
                            + "0.8.2-document-v2, or 0.8.3-code-v3");
        }
    }
}
