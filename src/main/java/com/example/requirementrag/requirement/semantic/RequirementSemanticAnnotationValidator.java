package com.example.requirementrag.requirement.semantic;

import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationInput;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationResult;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticClaimCandidate;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticCondition;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticEntity;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticEvent;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticNumericFact;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticQuestion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 语义标注离线校验器：数组上限、受控枚举、证据子串回查、数值/操作符一致性、
 * factKey 格式与实体引用存在性。校验失败抛携带稳定错误码的 {@link RequirementSemanticException}。
 */
@Service
@ConditionalOnProperty(prefix = "app.rag.requirement-semantic", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class RequirementSemanticAnnotationValidator {
    /** 受控 factKey：小写下划线段，2~8 段，禁止把整句原文当 Key。 */
    private static final Pattern FACT_KEY = Pattern.compile("^[a-z0-9_]+(\\.[a-z0-9_]+){1,7}$");
    /** 容忍单位后缀的数值提取，例如 “30级”→30；无法提取则判为非法数值。 */
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");

    /** 数值型 valueType：value 必须可解析为十进制数。 */
    private static final Set<String> NUMERIC_VALUE_TYPES = Set.of("NUMBER", "DURATION", "RANGE");
    /** 严格比较操作符：无论 valueType 如何，操作数必须是数值。 */
    private static final Set<String> STRICT_COMPARISON_OPERATORS = Set.of("GT", "GTE", "LT", "LTE");
    private static final int MAX_QUOTE_CHARS = 500;
    private static final int MAX_TEXT_CHARS = 1_000;

    private final RequirementSemanticProperties properties;

    public RequirementSemanticAnnotationValidator(RequirementSemanticProperties properties) {
        this.properties = properties;
    }

    /** 可被离线测试复用的确定性输出校验；输入为模型原始输出，返回归一化结果。 */
    public SemanticAnnotationResult validate(SemanticAnnotationInput input, SemanticAnnotationResult result) {
        if (result == null) {
            throw new RequirementSemanticException("SEMANTIC_SCHEMA_INVALID", "需求语义标注结果为空");
        }
        if (result.entities().size() > properties.maxEntitiesPerChunk()
                || result.conditions().size() > properties.maxConditionsPerChunk()
                || result.events().size() > properties.maxEventsPerChunk()
                || result.numericFacts().size() > properties.maxNumericFactsPerChunk()
                || result.claims().size() > properties.maxClaimsPerChunk()
                || result.questionExpansions().size() > properties.maxQuestionsPerChunk()) {
            throw new RequirementSemanticException("SEMANTIC_SCHEMA_INVALID", "需求语义标注数组数量超过上限");
        }
        String rawText = input.rawText() == null ? "" : input.rawText();
        Set<String> entityNames = new HashSet<>();

        List<SemanticEntity> entities = new ArrayList<>();
        Set<String> seenEntities = new HashSet<>();
        for (SemanticEntity raw : result.entities()) {
            if (raw == null || raw.name() == null || raw.name().isBlank()) {
                throw new RequirementSemanticException("SEMANTIC_SCHEMA_INVALID", "需求语义实体缺少 name");
            }
            String name = raw.name().trim();
            if (!seenEntities.add(name)) continue;
            entityNames.add(name);
            List<String> aliases = cleanStrings(raw.aliases(), 10);
            aliases.forEach(entityNames::add);
            entities.add(new SemanticEntity(name, bounded(raw.type(), 100), aliases,
                    certainty(raw.certainty()), evidenceQuote(rawText, raw.evidenceQuote(), "实体")));
        }

        List<SemanticCondition> conditions = new ArrayList<>();
        for (SemanticCondition raw : result.conditions()) {
            if (raw == null) {
                throw new RequirementSemanticException("SEMANTIC_SCHEMA_INVALID", "需求语义条件不能为空");
            }
            String operator = operator(raw.operator());
            String valueType = valueType(raw.valueType());
            String value = bounded(raw.value(), 200);
            validateNumericConsistency(operator, valueType, value, "条件");
            conditions.add(new SemanticCondition(bounded(raw.subject(), 200), bounded(raw.field(), 200),
                    operator, value, bounded(raw.unit(), 50), valueType,
                    bounded(raw.logicalGroup(), 100), certainty(raw.certainty()),
                    evidenceQuote(rawText, raw.evidenceQuote(), "条件")));
        }

        List<SemanticEvent> events = new ArrayList<>();
        for (SemanticEvent raw : result.events()) {
            if (raw == null || raw.event() == null || raw.event().isBlank()) {
                throw new RequirementSemanticException("SEMANTIC_SCHEMA_INVALID", "需求语义事件缺少 event 名称");
            }
            events.add(new SemanticEvent(bounded(raw.subject(), 200), raw.event().trim(),
                    bounded(raw.object(), 200), bounded(raw.result(), MAX_TEXT_CHARS),
                    bounded(raw.condition(), 500), certainty(raw.certainty()),
                    evidenceQuote(rawText, raw.evidenceQuote(), "事件")));
        }

        List<SemanticNumericFact> numericFacts = new ArrayList<>();
        for (SemanticNumericFact raw : result.numericFacts()) {
            if (raw == null || raw.value() == null || raw.value().isBlank()) {
                throw new RequirementSemanticException("SEMANTIC_NUMERIC_INVALID", "需求语义数值缺少 value");
            }
            String operator = operator(raw.operator());
            // 区间事实只能存在于 conditions；numericFacts 是单值事实，BETWEEN 会把区间压成单值。
            if ("BETWEEN".equals(operator)) {
                throw new RequirementSemanticException("SEMANTIC_NUMERIC_INVALID",
                        "需求语义数值不允许 BETWEEN 区间，区间必须用 conditions 表达");
            }
            String value = raw.value().trim();
            validateNumericConsistency(operator, "NUMBER", value, "数值");
            // 服务端解析是归一化结果的唯一权威；模型提供的 normalizedValue 必须与其一致。
            Double normalized = parseNumber(value);
            if (normalized == null) {
                throw new RequirementSemanticException("SEMANTIC_NUMERIC_INVALID",
                        "需求语义数值无法归一化: " + bounded(value, 50));
            }
            if (raw.normalizedValue() != null && Math.abs(raw.normalizedValue() - normalized) > 1e-9) {
                throw new RequirementSemanticException("SEMANTIC_NUMERIC_INVALID",
                        "需求语义数值归一化结果与原文不一致: " + bounded(value, 50));
            }
            String normalizedUnit = bounded(raw.normalizedUnit(), 50);
            if (normalizedUnit.isEmpty()) {
                normalizedUnit = bounded(raw.unit(), 50);
            }
            numericFacts.add(new SemanticNumericFact(bounded(raw.subject(), 200), bounded(raw.field(), 200),
                    value, normalized, bounded(raw.unit(), 50), normalizedUnit, operator,
                    certainty(raw.certainty()), evidenceQuote(rawText, raw.evidenceQuote(), "数值")));
        }

        List<SemanticClaimCandidate> claims = new ArrayList<>();
        Set<String> seenFactKeys = new HashSet<>();
        for (SemanticClaimCandidate raw : result.claims()) {
            if (raw == null || raw.subject() == null || raw.subject().isBlank()) {
                throw new RequirementSemanticException("SEMANTIC_SCHEMA_INVALID", "需求语义 Claim 缺少 subject");
            }
            String factKey = raw.factKey() == null ? "" : raw.factKey().trim().toLowerCase(Locale.ROOT);
            if (!FACT_KEY.matcher(factKey).matches()) {
                throw new RequirementSemanticException("SEMANTIC_FACT_KEY_INVALID",
                        "需求语义 factKey 格式非法: " + bounded(factKey, 80));
            }
            if (!seenFactKeys.add(factKey)) continue;
            String subject = raw.subject().trim();
            if (!entityNames.isEmpty() && !entityNames.contains(subject) && !rawText.contains(subject)) {
                throw new RequirementSemanticException("SEMANTIC_SCHEMA_INVALID",
                        "需求语义 Claim 引用了未声明的主体: " + bounded(subject, 80));
            }
            claims.add(new SemanticClaimCandidate(factKey, subject, bounded(raw.predicate(), 200),
                    bounded(raw.value(), 200), bounded(raw.unit(), 50), certainty(raw.certainty()),
                    evidenceQuote(rawText, raw.evidenceQuote(), "Claim")));
        }

        List<SemanticQuestion> questions = new ArrayList<>();
        for (SemanticQuestion raw : result.questionExpansions()) {
            if (raw == null || raw.text() == null || raw.text().isBlank()) {
                throw new RequirementSemanticException("SEMANTIC_SCHEMA_INVALID", "需求语义问题扩展缺少 text");
            }
            questions.add(new SemanticQuestion(bounded(raw.text(), MAX_TEXT_CHARS), questionType(raw.type())));
        }

        return new SemanticAnnotationResult(List.copyOf(entities), List.copyOf(conditions),
                List.copyOf(events), List.copyOf(numericFacts), List.copyOf(claims),
                List.copyOf(questions), cleanStrings(result.uncertainties(), 20),
                cleanStrings(result.missingContext(), 20), result.selfContained());
    }

    private String evidenceQuote(String rawText, String quote, String kind) {
        String value = quote == null ? "" : quote.trim();
        if (value.isEmpty()) {
            throw new RequirementSemanticException("SEMANTIC_EVIDENCE_UNAVAILABLE",
                    "需求语义" + kind + "缺少 evidenceQuote");
        }
        String bounded = value.length() <= MAX_QUOTE_CHARS ? value : value.substring(0, MAX_QUOTE_CHARS);
        if (!rawText.contains(bounded)) {
            throw new RequirementSemanticException("SEMANTIC_EVIDENCE_UNAVAILABLE",
                    "需求语义" + kind + "的 evidenceQuote 不是原文连续子串");
        }
        return bounded;
    }

    private void validateNumericConsistency(String operator, String valueType,
                                            String value, String kind) {
        if (value == null || value.isBlank()) return;
        if ("BETWEEN".equals(operator)) {
            if (!isRangeValue(value)) {
                throw new RequirementSemanticException("SEMANTIC_NUMERIC_INVALID",
                        kind + " BETWEEN 必须提供上下界区间: " + bounded(value, 50));
            }
            return;
        }
        boolean numeric = NUMERIC_VALUE_TYPES.contains(valueType)
                || STRICT_COMPARISON_OPERATORS.contains(operator);
        if (numeric && parseNumber(value) == null) {
            throw new RequirementSemanticException("SEMANTIC_NUMERIC_INVALID",
                    kind + "声明为数值但 value 无法解析: " + bounded(value, 50));
        }
    }

    private String certainty(String value) {
        return enumValue(value, RequirementSemanticModels.SemanticCertainty.values(),
                "certainty", "SEMANTIC_SCHEMA_INVALID");
    }

    private String operator(String value) {
        return enumValue(value, RequirementSemanticModels.SemanticOperator.values(),
                "operator", "SEMANTIC_SCHEMA_INVALID");
    }

    private String valueType(String value) {
        return enumValue(value, RequirementSemanticModels.SemanticValueType.values(),
                "valueType", "SEMANTIC_SCHEMA_INVALID");
    }

    private String questionType(String value) {
        if (value == null || value.isBlank()) return null;
        return enumValue(value, RequirementSemanticModels.SemanticQuestionType.values(),
                "question type", "SEMANTIC_SCHEMA_INVALID");
    }

    private String enumValue(String value, Enum<?>[] allowed, String field, String errorCode) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        for (Enum<?> candidate : allowed) {
            if (candidate.name().equals(normalized)) return candidate.name();
        }
        throw new RequirementSemanticException(errorCode, "需求语义 " + field + " 非法: " + bounded(value, 50));
    }

    /** 解析数值：提取首个十进制数（容忍单位后缀、全角符号与千分位逗号），无法提取返回 null。 */
    private Double parseNumber(String value) {
        if (value == null) return null;
        java.util.regex.Matcher matcher = NUMBER_PATTERN.matcher(value.replace(",", ""));
        if (!matcher.find()) return null;
        try {
            return Double.parseDouble(matcher.group());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean isRangeValue(String value) {
        String[] parts = value.split("[~～—–至到]|-{1}(?=[0-9])", 2);
        if (parts.length != 2) return false;
        Double left = parseNumber(parts[0]);
        Double right = parseNumber(parts[1]);
        return left != null && right != null;
    }

    private List<String> cleanStrings(List<String> values, int limit) {
        if (values == null) return List.of();
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> bounded(value, MAX_TEXT_CHARS))
                .distinct()
                .limit(limit)
                .toList();
    }

    private String bounded(String value, int limit) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }
}
