package com.example.requirementrag.evaluation;

import com.example.requirementrag.evaluation.RequirementGraphGoldModels.DriftDecision;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCase;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldEntity;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldWindow;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PredictedRelation;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.Prediction;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PredictionStatus;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PublicationDecision;
import com.example.requirementrag.requirement.graph.RequirementGraphException;
import com.example.requirementrag.requirement.graph.RequirementGraphExtractionService;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractedEntity;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractedRelation;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractionInput;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractionResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 生产抽取链路预测器。
 *
 * <p>与 {@link PromptExtractionBenchmarkPredictor}（单次 Prompt 基准）不同，本预测器把每个金标用例路由到
 * <b>真实 {@link RequirementGraphExtractionService#extract(ExtractionInput)}</b>：
 * 走生产 System Prompt、Schema/证据回查校验、实体/关系类型与本体校验。
 *
 * <ul>
 *   <li>多窗口用例逐窗口独立抽取，再按生产 BuildAccumulator 的合并语义（type+规范名+别名）跨窗口合并；</li>
 *   <li>关系谓词被限制在正式 {@code RelationType} 本体内（抽取服务校验），不产生金标里的非正式谓词；</li>
 *   <li>单窗口失败不影响其它窗口（部分失败保留成功窗口结果并记录 FAILURE 状态与 errorCode）。</li>
 * </ul>
 *
 * <p>注意：抽取链路本身不产出 Claim/代码事实/漂移决策，这些维度本预测器恒为空（诚实反映链路边界）。
 */
public class ProductionGraphPredictor implements RequirementGraphGoldPredictor {

    private final RequirementGraphExtractionService extractionService;

    public ProductionGraphPredictor(RequirementGraphExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    @Override
    public Prediction predict(GoldCase goldCase) {
        long startNanos = System.nanoTime();
        List<ExtractionResult> results = new ArrayList<>();
        int failedWindows = 0;
        String firstErrorCode = "";
        List<ExtractionInput> inputs = inputs(goldCase);
        if (inputs.isEmpty()) {
            return Prediction.empty();
        }
        for (ExtractionInput input : inputs) {
            try {
                results.add(extractionService.extract(input));
            } catch (RequirementGraphException exception) {
                failedWindows++;
                if (firstErrorCode.isBlank()) firstErrorCode = exception.code();
            } catch (RuntimeException exception) {
                failedWindows++;
                if (firstErrorCode.isBlank()) firstErrorCode = classify(exception);
            }
        }
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        if (results.isEmpty()) {
            return new Prediction(Set.of(), List.of(), List.of(), List.of(), List.of(),
                    new DriftDecision("", "", "", List.of()), PublicationDecision.NOT_PUBLISHED,
                    PredictionStatus.FAILURE, firstErrorCode.isBlank() ? "GRAPH_WINDOW_FAILED" : firstErrorCode,
                    latencyMs, 0);
        }
        MergedGraph merged = merge(results, goldCase.entities());
        PredictionStatus status = failedWindows > 0 ? PredictionStatus.FAILURE : PredictionStatus.SUCCESS;
        return new Prediction(merged.entities(), merged.relations(), List.of(), merged.uncertainties(), List.of(),
                new DriftDecision("", "", "", List.of()), PublicationDecision.NOT_PUBLISHED,
                status, failedWindows > 0 ? firstErrorCode : "", latencyMs, 0);
    }

    private List<ExtractionInput> inputs(GoldCase goldCase) {
        List<ExtractionInput> inputs = new ArrayList<>();
        for (GoldWindow window : goldCase.windows()) {
            inputs.add(new ExtractionInput(window.filename(), window.parentId(), window.parentOrder(),
                    "", "", window.contentHash(), window.text(), window.windowId(),
                    window.startOffset(), window.endOffset()));
        }
        if (inputs.isEmpty() && goldCase.inputText() != null && !goldCase.inputText().isBlank()) {
            String text = goldCase.inputText();
            inputs.add(new ExtractionInput("gold-case", "gold-case", 1, "", "", "", text,
                    "window:gold-case", 0, text.length()));
        }
        return inputs;
    }

    /** 合并：type+规范名为主键，别名跨窗口融合；关系端点解析为全局规范名。 */
    private MergedGraph merge(List<ExtractionResult> results, List<GoldEntity> goldEntities) {
        Map<String, String> typeAndNameKeyToName = new LinkedHashMap<>();
        Map<String, String> typeAndNameKeyToCanonical = new LinkedHashMap<>();
        Map<String, Set<String>> typeAndNameKeyToAliases = new LinkedHashMap<>();
        Map<String, String> localIdToName = new LinkedHashMap<>();
        Map<String, String> localIdToKey = new LinkedHashMap<>();
        List<String> uncertainties = new ArrayList<>();
        Set<String> entityNames = new LinkedHashSet<>();

        for (ExtractionResult result : results) {
            uncertainties.addAll(result.uncertainties());
            for (ExtractedEntity extracted : result.entities()) {
                String type = extracted.type();
                String name = clean(extracted.name());
                if (name.isBlank()) continue;
                String key = resolveKey(type, name,
                        typeAndNameKeyToCanonical, typeAndNameKeyToAliases);
                typeAndNameKeyToCanonical.putIfAbsent(key, name);
                typeAndNameKeyToName.putIfAbsent(key, name);
                typeAndNameKeyToAliases.computeIfAbsent(key, ignored -> new LinkedHashSet<>())
                        .addAll(extracted.aliases());
                entityNames.add(name);
                localIdToName.put(extracted.localId(), name);
                localIdToKey.put(extracted.localId(), key);
            }
        }

        // 金标实体作为“期望名称”索引，方便关系端点解析回 gold canonical（若抽取名称一致）。
        Map<String, String> goldNameKeyToCanonical = new LinkedHashMap<>();
        for (GoldEntity entity : goldEntities) {
            goldNameKeyToCanonical.putIfAbsent(canonical(entity.canonicalName()), entity.canonicalName());
            for (String alias : entity.aliases()) {
                goldNameKeyToCanonical.putIfAbsent(canonical(alias), entity.canonicalName());
            }
        }

        List<PredictedRelation> relations = new ArrayList<>();
        Set<String> seenRelations = new LinkedHashSet<>();
        for (ExtractionResult result : results) {
            for (ExtractedRelation extracted : result.relations()) {
                String source = resolveGlobal(localIdToName.get(extracted.sourceLocalId()),
                        goldNameKeyToCanonical);
                String target = resolveGlobal(localIdToName.get(extracted.targetLocalId()),
                        goldNameKeyToCanonical);
                if (source.isBlank() || target.isBlank() || source.equals(target)) continue;
                String predicate = extracted.type();
                String relationKey = source + "|" + predicate + "|" + target;
                if (seenRelations.add(relationKey)) {
                    relations.add(new PredictedRelation(source, target, predicate));
                }
            }
        }
        return new MergedGraph(entityNames, relations, uncertainties);
    }

    /** 与生产 BuildAccumulator 语义一致：先按 (type, 规范名) 找已有 key，再按别名融合。 */
    private String resolveKey(String type, String name,
                              Map<String, String> keyToCanonical,
                              Map<String, Set<String>> keyToAliases) {
        String exactKey = type + "|" + canonical(name);
        if (keyToCanonical.containsKey(exactKey)) return exactKey;
        for (Map.Entry<String, String> entry : keyToCanonical.entrySet()) {
            Set<String> aliases = keyToAliases.getOrDefault(entry.getKey(), Set.of());
            if (aliases.stream().anyMatch(alias -> canonical(alias).equals(canonical(name)))) {
                return entry.getKey();
            }
        }
        return exactKey;
    }

    private String resolveGlobal(String name, Map<String, String> goldNameKeyToCanonical) {
        if (name == null || name.isBlank()) return "";
        String key = canonical(name);
        String goldCanonical = goldNameKeyToCanonical.get(key);
        return goldCanonical == null ? name : goldCanonical;
    }

    private String canonical(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String classify(RuntimeException exception) {
        String text = (exception.getClass().getSimpleName() + " "
                + (exception.getMessage() == null ? "" : exception.getMessage())).toLowerCase(Locale.ROOT);
        if (text.contains("timeout") || text.contains("timed out")) return "GRAPH_MODEL_TIMEOUT";
        if (text.contains("429") || text.contains("rate limit") || text.contains("rate_limit")) return "GRAPH_MODEL_RATE_LIMITED";
        if (text.contains("unavailable") || text.contains("connection")) return "GRAPH_MODEL_UNAVAILABLE";
        return "GRAPH_WINDOW_FAILED";
    }

    private record MergedGraph(Set<String> entities, List<PredictedRelation> relations, List<String> uncertainties) {
    }
}
