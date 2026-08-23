package com.example.requirementrag.knowledge.multisource.alignment;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeStore;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.AlignmentRelation;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.BuildResult;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.CodeSymbolView;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DriftItem;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DriftType;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.LoadedCode;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.MatchMethod;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.TruthRole;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.VersionContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 代码—参数表对齐（Phase 2）：先完成准确率最高的闭环。
 *
 * <p>确定性规则把参数 Claim 与代码符号按规范化名称匹配，生成 READS_CONFIG / USES_PARAMETER；
 * 当可取得代码侧值（通过 CodeValueProvider）时与参数值结构化比较，不一致生成 CONFIG_DRIFT。
 * 代码值提取器默认空实现，保证“不知道就不宣称漂移”。
 */
@Service
public class CodeParameterAlignmentService {

    /** 代码值提供器：未来可接常量/枚举/属性绑定静态扫描；默认不提供值。 */
    public interface CodeValueProvider {
        Optional<CodeValue> resolve(CodeSymbolView symbol);

        record CodeValue(String value, String unit) {
        }
    }

    private final MultiSourceKnowledgeStore knowledgeStore;
    private final CodeCentricAlignmentStore alignmentStore;
    private final CodeSymbolLoader codeSymbolLoader;
    private final VersionContextService versionContextService;
    private CodeValueProvider valueProvider = symbol -> Optional.empty();

    public CodeParameterAlignmentService(MultiSourceKnowledgeStore knowledgeStore,
                                         CodeCentricAlignmentStore alignmentStore,
                                         CodeSymbolLoader codeSymbolLoader,
                                         VersionContextService versionContextService) {
        this.knowledgeStore = knowledgeStore;
        this.alignmentStore = alignmentStore;
        this.codeSymbolLoader = codeSymbolLoader;
        this.versionContextService = versionContextService;
    }

    /** 注入代码值提供器（测试/后续静态扫描扩展用）。 */
    public CodeParameterAlignmentService withValueProvider(CodeValueProvider provider) {
        this.valueProvider = provider == null ? symbol -> Optional.empty() : provider;
        return this;
    }

    /** 构建代码—参数对齐关系（幂等重建 Phase 2 的关系）。 */
    public BuildResult build(String projectId, String version, String environment) {
        VersionContext context = versionContextService.resolve(projectId, version, environment);
        LoadedCode loaded = codeSymbolLoader.load(projectId);
        if (loaded.commitSha() == null) {
            return new BuildResult(0, 0, 0, 0, 0);
        }
        Map<String, List<CodeSymbolView>> byName = codeSymbolLoader.indexBySimpleName(loaded);
        List<ParameterClaim> parameters = knowledgeStore.findParameters(projectId, version);

        alignmentStore.deleteAlignmentRelationsByType(projectId, version, "READS_CONFIG");
        alignmentStore.deleteAlignmentRelationsByType(projectId, version, "USES_PARAMETER");
        alignmentStore.deleteAlignmentRelationsByType(projectId, version, "CONFIG_DRIFT");
        alignmentStore.deleteDriftItemsByType(projectId, version, "CONFIG_DRIFT");

        int relations = 0;
        int drifts = 0;
        Set<String> seen = new HashSet<>();
        for (ParameterClaim parameter : parameters) {
            List<CodeSymbolView> matches = match(parameter.parameter(), byName);
            for (CodeSymbolView symbol : matches) {
                String matchMethod = AlignmentNaming.normalize(parameter.parameter())
                        .equals(AlignmentNaming.normalize(symbol.simpleName()))
                        ? MatchMethod.NORMALIZED_NAME_EXACT.name()
                        : MatchMethod.NORMALIZED_NAME_CONTAINS.name();
                String relationId = relationId(projectId, version, parameter.claimId(), symbol.id(), "READS_CONFIG");
                if (seen.add(relationId)) {
                    alignmentStore.saveAlignmentRelation(new AlignmentRelation(
                            relationId, projectId, version,
                            parameter.claimId(), null, "PARAMETER_TABLE",
                            null, symbol.id(), "CODE", "READS_CONFIG",
                            matchMethod, "RULE_CONFIRMED", 0.9,
                            evidenceId(parameter.claimId(), parameter.evidenceLocation()),
                            context.contextId(), context.contextId(),
                            "参数[" + parameter.parameter() + "] 匹配代码符号 " + symbol.simpleName()
                                    + " (" + symbol.filePath() + ":" + symbol.startLine() + "-" + symbol.endLine() + ")",
                            null, null));
                    relations++;
                }

                Optional<CodeValueProvider.CodeValue> codeValue = valueProvider.resolve(symbol);
                if (codeValue.isPresent() && differs(parameter.normalizedValue(), codeValue.get().value())) {
                    String driftId = driftId(projectId, version, parameter.claimId(), symbol.id(), "CONFIG_DRIFT");
                    alignmentStore.saveDriftItem(new DriftItem(
                            driftId, projectId, version,
                            "UNKNOWN", "param:" + AlignmentNaming.keySegment(parameter.module()) + "."
                                    + AlignmentNaming.keySegment(parameter.parameter()),
                            DriftType.CONFIG_DRIFT.name(),
                            "ERROR", TruthRole.CONFIGURATION.name(),
                            parameter.claimId(), null, parameter.normalizedValue(), codeValue.get().value(),
                            "参数表[" + parameter.parameter() + "]=" + parameter.normalizedValue()
                                    + "，代码=" + codeValue.get().value(),
                            "OPEN", null, Instant.now().toString()));
                    drifts++;
                }
            }
        }
        return new BuildResult(0, 0, 0, relations, drifts);
    }

    /** 查询代码—参数对齐关系。 */
    public List<AlignmentRelation> relations(String projectId, String version, String relationType) {
        return alignmentStore.findAlignmentRelations(projectId, version, relationType);
    }

    private List<CodeSymbolView> match(String parameterName, Map<String, List<CodeSymbolView>> byName) {
        String normalized = AlignmentNaming.normalize(parameterName);
        if (normalized.isBlank()) return List.of();
        List<CodeSymbolView> exact = byName.get(normalized);
        if (exact != null && !exact.isEmpty()) {
            return cap(exact);
        }
        List<CodeSymbolView> result = new ArrayList<>();
        for (Map.Entry<String, List<CodeSymbolView>> entry : byName.entrySet()) {
            if (result.size() >= 3) break;
            if (AlignmentNaming.namesRelated(entry.getKey(), parameterName)) {
                result.addAll(cap(entry.getValue()));
            }
        }
        return result;
    }

    private List<CodeSymbolView> cap(List<CodeSymbolView> symbols) {
        return symbols.size() > 3 ? symbols.subList(0, 3) : symbols;
    }

    private String evidenceId(String claimId, String fallback) {
        List<String> evidence = knowledgeStore.findEvidenceIdsByClaimId(claimId);
        return evidence.isEmpty() ? fallback : evidence.get(0);
    }

    private boolean differs(String parameterValue, String codeValue) {
        BigDecimal parameter = decimal(parameterValue);
        BigDecimal code = decimal(codeValue);
        if (parameter != null && code != null) return parameter.compareTo(code) != 0;
        return !String.valueOf(parameterValue == null ? "" : parameterValue.trim())
                .equalsIgnoreCase(String.valueOf(codeValue == null ? "" : codeValue.trim()));
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.replace("%", "").replace("分钟", "").replace("min", "").replace(",", "").trim();
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String relationId(String projectId, String version, String sourceClaimId, String targetExternalId,
                              String type) {
        return "ar:" + sha256(projectId + "|" + version + "|" + sourceClaimId + "|" + targetExternalId
                + "|" + type).substring(0, 32);
    }

    private String driftId(String projectId, String version, String sourceClaimId, String targetExternalId,
                           String type) {
        return "di:" + sha256(projectId + "|" + version + "|" + sourceClaimId + "|" + targetExternalId
                + "|" + type).substring(0, 32);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}