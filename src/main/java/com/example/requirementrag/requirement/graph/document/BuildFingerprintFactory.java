package com.example.requirementrag.requirement.graph.document;

import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.BuildFingerprint;
import org.springframework.stereotype.Component;

/** 构建指纹工厂（Phase 4）：把源修订与全部语义输入版本绑定。 */
@Component
public class BuildFingerprintFactory {

    public BuildFingerprint create(String sourceRevision) {
        return create(sourceRevision, "v1", "v1", "v1", "v2", "v1", "RULE", "v1");
    }

    public BuildFingerprint create(String sourceRevision, String documentParserVersion,
                                   String chunkingStrategyVersion, String windowPlannerVersion,
                                   String ontologyVersion, String promptVersion, String modelId,
                                   String crossWindowIntegrationVersion) {
        return new BuildFingerprint(
                sourceRevision == null ? "" : sourceRevision,
                documentParserVersion, chunkingStrategyVersion, windowPlannerVersion,
                ontologyVersion, promptVersion, modelId, crossWindowIntegrationVersion);
    }
}