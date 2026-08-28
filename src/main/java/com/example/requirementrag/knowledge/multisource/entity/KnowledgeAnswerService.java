package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.AssessmentItem;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntitySearchResponse;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.FactAssessment;
import com.example.requirementrag.service.GenerationChatOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI 带证据回答（dev md §11）：只吃受限证据包，模型输出引用必须经服务端校验，
 * 冲突时同时报告实现偏差。LLM 不可用时用确定性模板降级（不编造结论）。
 */
@Service
public class KnowledgeAnswerService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAnswerService.class);

    /** 证据包分节上限。 */
    private static final int EVIDENCE_CAP = 40;
    private static final int SECTION_CAP = 8;

    private final ChatClient chatClient;
    private final RagProperties ragProperties;
    private final EntityExtractionProperties properties;

    public KnowledgeAnswerService(ChatClient chatClient, RagProperties ragProperties,
                                  EntityExtractionProperties properties) {
        this.chatClient = chatClient;
        this.ragProperties = ragProperties;
        this.properties = properties;
    }

    /** 回答输出。sourceType：模型声明的分节证据类型（CODE/PARAMETER_TABLE/TEST_RESULT/REQUIREMENT/MIXED）。 */
    public record AnswerSection(String title, String text, String sourceType, List<String> evidenceIds) {
    }

    public record AnswerOutcome(String answer, List<AnswerSection> sections,
                                String status, String citationQuality,
                                boolean llmUsed, List<String> warnings) {
    }

    /** LLM 结构化输出（证据引用由服务端校验）。 */
    public record AgentAnswerRaw(String answer, List<AnswerSection> sections) {
    }

    /** 允许的分节来源类型（MIXED 不再作为合法声明，避免参数表证据绕过支撑代码行为结论）。 */
    private static final java.util.Set<String> VALID_SECTION_TYPES =
            java.util.Set.of("CODE", "PARAMETER_TABLE", "TEST_RESULT", "REQUIREMENT");

    /** 基于实体检索证据生成回答。 */
    public AnswerOutcome answer(EntitySearchResponse evidence) {
        List<String> warnings = new ArrayList<>();
        Map<String, String> typeById = evidenceTypeById(evidence);
        Set<String> allowed = Set.copyOf(typeById.keySet());
        String packageText = buildEvidencePackage(evidence);

        if (llmAvailable() && packageText != null && packageText.length() <= 200_000) {
            try {
                AgentAnswerRaw raw = chatClient.prompt()
                        .system(systemPrompt())
                        .user(packageText)
                        .options(GenerationChatOptions.forModel(resolvedModel()))
                        .call()
                        .entity(AgentAnswerRaw.class);
                if (raw != null) {
                    return normalize(raw, allowed, typeById, statusFor(evidence), evidence);
                }
            } catch (RuntimeException exception) {
                log.warn("Entity answer LLM failed: {}", exception.getClass().getSimpleName());
            }
            warnings.add("ANSWER_LLM_UNAVAILABLE");
        }

        // 确定性模板降级
        return templateAnswer(evidence, warnings);
    }

    /**
     * 服务端校验引用：ID 必须存在于允许集 **且** 与分节声明的来源类型一致
     * （禁止用参数表 Evidence 支撑“代码已实现”结论等类型错配）。
     * 非法/类型不符的引用丢弃并降级质量；全部被丢弃时整体回退模板。
     */
    private AnswerOutcome normalize(AgentAnswerRaw raw, Set<String> allowed, Map<String, String> typeById,
                                    String status, EntitySearchResponse evidence) {
        int total = 0;
        int kept = 0;
        boolean rejectedReference = false;
        List<AnswerSection> sections = new ArrayList<>();
        for (AnswerSection section : raw.sections() == null ? List.<AnswerSection>of() : raw.sections()) {
            if (sections.size() >= SECTION_CAP) break;
            String title = section.title() == null || section.title().isBlank() ? "回答" : section.title();
            String text = section.text() == null ? "" : section.text();
            String expected = section.sourceType() == null ? null
                    : section.sourceType().trim().toUpperCase(Locale.ROOT);
            List<String> valid = new ArrayList<>();
            List<String> rawEvidenceIds = section.evidenceIds() == null ? List.of() : section.evidenceIds();
            if (!text.isBlank() && rawEvidenceIds.isEmpty()) {
                rejectedReference = true;
            }
            for (String evidenceId : rawEvidenceIds) {
                total++;
                String actual = typeById.get(evidenceId);
                // 分节必须声明单一合法来源类型且与证据类型一致；MIXED/未知 → 该分节证据整体不可信
                boolean typeOk = VALID_SECTION_TYPES.contains(expected) && expected.equals(actual);
                if (allowed.contains(evidenceId) && typeOk) {
                    valid.add(evidenceId);
                    kept++;
                } else {
                    rejectedReference = true;
                }
            }
            sections.add(new AnswerSection(title, text,
                    VALID_SECTION_TYPES.contains(expected) ? expected : "UNVERIFIED", valid));
        }
        String quality = total == 0 ? "UNVERIFIED"
                : kept < total ? "PARTIAL"
                : "VERIFIED";
        if (kept == 0 || rejectedReference) {
            // 只要有一个分节引用不可信，就不能返回模型的整段 answer（其文本可能依赖被丢弃的引用）。
            return templateAnswer(evidence, List.of("ANSWER_EVIDENCE_UNVERIFIED"));
        }
        String answer = sections.stream().map(AnswerSection::text)
                .filter(text -> text != null && !text.isBlank())
                .findFirst().orElse("无法确定");
        return new AnswerOutcome(answer, sections, status, quality, true, List.of());
    }

    /** 引用 ID → 来源类型：只注册真实 Evidence ID；Claim ID 不能冒充证据。 */
    private Map<String, String> evidenceTypeById(EntitySearchResponse evidence) {
        Map<String, String> typeById = new java.util.HashMap<>();
        for (var citation : evidence.citations()) {
            if (citation.evidenceId() != null && !citation.evidenceId().isBlank()) {
                typeById.put(citation.evidenceId(), citation.sourceType());
            }
        }
        for (var view : evidence.entities()) {
            for (var ref : view.currentFacts().code()) {
                if (ref.evidenceIds() != null) {
                    for (String evidenceId : ref.evidenceIds()) {
                        typeById.putIfAbsent(evidenceId, "CODE");
                    }
                }
            }
        }
        return typeById;
    }

    /** 构建受限证据包（分节 + 引用 ID），有界。 */
    private String buildEvidencePackage(EntitySearchResponse evidence) {
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (var view : evidence.entities()) {
            if (used >= EVIDENCE_CAP) break;
            sb.append("[ENTITY] ").append(view.canonicalName()).append('\n');
            if (!view.currentFacts().code().isEmpty()) {
                sb.append("[CURRENT_CODE]\n");
                for (var ref : view.currentFacts().code()) {
                    if (used++ >= EVIDENCE_CAP) break;
                    sb.append("- ").append(ref.subject())
                            .append(" location=").append(ref.location() == null ? "未知" : ref.location())
                            .append(" code=").append(ref.excerpt() == null ? "不可用" : ref.excerpt())
                            .append(" (evidence=")
                            .append(ref.evidenceIds().isEmpty() ? "无" : String.join(",", ref.evidenceIds()))
                            .append(")\n");
                }
            }
            if (!view.currentFacts().parameterTables().isEmpty()) {
                sb.append("[CURRENT_PARAMETER_TABLE]\n");
                for (var ref : view.currentFacts().parameterTables()) {
                    if (used++ >= EVIDENCE_CAP) break;
                    sb.append("- ").append(ref.subject()).append('=').append(ref.objectValue())
                            .append(ref.unit()).append(" (evidence=")
                            .append(ref.evidenceIds().isEmpty() ? "无" : String.join(",", ref.evidenceIds()))
                            .append(")\n");
                }
            }
            if (!view.currentFacts().testResults().isEmpty()) {
                sb.append("[TEST_RESULT]\n");
                for (var ref : view.currentFacts().testResults()) {
                    if (used++ >= EVIDENCE_CAP) break;
                    sb.append("- ").append(ref.subject()).append('=').append(ref.objectValue())
                            .append(" (evidence=")
                            .append(ref.evidenceIds().isEmpty() ? "无" : String.join(",", ref.evidenceIds()))
                            .append(")\n");
                }
            }
            if (evidence.plan() != null && evidence.plan().includeHistory()
                    && !view.timeline().isEmpty()) {
                sb.append("[HISTORICAL_REQUIREMENT]\n");
                for (var block : view.timeline()) {
                    for (var ref : block.requirements()) {
                        if (used++ >= EVIDENCE_CAP) break;
                        sb.append("- ").append(block.businessVersion()).append(": ")
                                .append(ref.subject()).append('=').append(ref.objectValue())
                                .append(" (evidence=")
                                .append(ref.evidenceIds().isEmpty() ? "无" : String.join(",", ref.evidenceIds()))
                                .append(")\n");
                    }
                }
            }
            if (!view.conflicts().isEmpty()) {
                sb.append("[CONFLICTS]\n");
                for (var conflict : view.conflicts()) {
                    sb.append("- factKey=").append(conflict.factKey())
                            .append(" values=").append(String.join(",", conflict.values()))
                            .append(" status=").append(conflict.status()).append('\n');
                }
            }
        }
        if (used == 0) {
            return null;
        }
        return sb.toString();
    }

    /** 确定性模板降级：偏差 / 缺失来源 / 无法确定。 */
    private AnswerOutcome templateAnswer(EntitySearchResponse evidence, List<String> warnings) {
        FactAssessment assessment = evidence.factAssessment() == null
                ? FactAssessment.EMPTY : evidence.factAssessment();
        List<String> gapTypes = assessment.implementationGaps().stream()
                .map(AssessmentItem::type).distinct().toList();
        List<String> refs = new ArrayList<>();
        for (var citation : evidence.citations()) {
            if (refs.size() >= 5) break;
            if (citation.evidenceId() != null) refs.add(citation.evidenceId());
        }
        String status;
        String answer;
        if (!gapTypes.isEmpty()) {
            status = "REVIEW_REQUIRED";
            answer = "检测到实现偏差（" + String.join("、", gapTypes)
                    + "）：当前代码与数值表/需求目标不一致，需要人工核对。"
                    + (refs.isEmpty() ? "" : " 引用: " + String.join(",", refs));
        } else if (!assessment.currentValues().isEmpty()
                && !assessment.currentBehavior().isEmpty()) {
            // 无确定性冲突但须有证据支持才确认；任一事实缺证据 → UNVERIFIED
            boolean allSupported = allFactItemsSupported(assessment);
            status = allSupported ? "CONFIRMED" : "UNVERIFIED";
            answer = allSupported
                    ? "当前数值表提供配置值，代码存在相关实现；未发现确定性冲突。"
                    + (refs.isEmpty() ? "" : " 引用: " + String.join(",", refs))
                    : "当前存在代码与数值表证据，但部分事实缺少可回源的 Evidence，无法确认。"
                    + (refs.isEmpty() ? "" : " 引用: " + String.join(",", refs));
        } else {
            status = "UNVERIFIED";
            answer = "无法确定：证据不足，缺少" + missingSources(evidence) + "。";
        }
        // 模板结论可能综合多个来源，不能伪装成单一来源分节；引用仍通过答案顶层返回。
        List<AnswerSection> sections = List.of();
        return new AnswerOutcome(answer, sections, status, "UNVERIFIED", false, warnings);
    }

    /** 事实评估各分区条目是否全部有证据支持（SUPPORTED）；UNVERIFIED 任一条 → false。 */
    private static boolean allFactItemsSupported(FactAssessment assessment) {
        return allSupported(assessment.currentBehavior())
                && allSupported(assessment.currentValues())
                && allSupported(assessment.validation())
                && allSupported(assessment.requirementTarget());
    }

    private static boolean allSupported(List<AssessmentItem> items) {
        if (items.isEmpty()) return true;
        return items.stream().allMatch(item -> "SUPPORTED".equals(item.status()));
    }

    private String missingSources(EntitySearchResponse evidence) {
        List<String> missing = new ArrayList<>();
        boolean hasCode = evidence.entities().stream()
                .anyMatch(view -> !view.currentFacts().code().isEmpty());
        boolean hasParams = evidence.entities().stream()
                .anyMatch(view -> !view.currentFacts().parameterTables().isEmpty());
        if (!hasCode) missing.add("当前代码证据");
        if (!hasParams) missing.add("当前数值表证据");
        if (missing.isEmpty()) missing.add("足够证据");
        return String.join("、", missing);
    }

    private String statusFor(EntitySearchResponse evidence) {
        FactAssessment assessment = evidence.factAssessment() == null
                ? FactAssessment.EMPTY : evidence.factAssessment();
        boolean hasGap = assessment.implementationGaps().stream()
                .anyMatch(item -> "CONFLICTED".equals(item.status())
                        || "REVIEW_REQUIRED".equals(item.status()));
        boolean allSupported = allFactItemsSupported(assessment);
        return hasGap ? "REVIEW_REQUIRED" : allSupported ? "CONFIRMED" : "UNVERIFIED";
    }

    private String systemPrompt() {
        return """
                你只能基于提供的证据回答。规则：
                1. 当前行为优先引用 [CURRENT_CODE]；当前数值优先引用 [CURRENT_PARAMETER_TABLE]。
                2. [TEST_RESULT] 只说明验证是否通过，不能单独推导未执行的行为。
                3. [HISTORICAL_REQUIREMENT] 用于说明目标和历史，不得覆盖当前代码事实。
                4. 代码与数值表不一致时，必须同时报告两者与实现偏差。
                5. 每个关键结论必须附证据 ID（evidence=...）。
                6. 没有足够证据时回答“无法确定”，并指出缺失的来源。
                7. 不得把相似文本、LLM 推测或未确认关系写成确定事实。
                输出 JSON: {"answer":"...","sections":[{"title":"...","text":"...","sourceType":"CODE|PARAMETER_TABLE|TEST_RESULT|REQUIREMENT","evidenceIds":["..."]}]}
                注意：每个分节必须声明且只能声明一个 sourceType（不提供 MIXED）；分节的 evidenceIds 只能引用与该
                sourceType 一致的证据；代码行为结论只能引用 [CURRENT_CODE] 的 evidence（location 字段是代码位置），
                数值结论只能引用 [CURRENT_PARAMETER_TABLE] 的 evidence，验证结论只能引用 [TEST_RESULT] 的 evidence。
                不要引用类型不匹配的证据。无法由单一证据类型支持的分节，请拆分为多个分节。""";
    }

    private boolean llmAvailable() {
        return chatClient != null && resolvedModel() != null;
    }

    private String resolvedModel() {
        if (properties != null && properties.model() != null && !properties.model().isBlank()) {
            return properties.model();
        }
        if (ragProperties == null || ragProperties.llm() == null) {
            return null;
        }
        return ragProperties.llm().resolvedDevelopmentPlanModel();
    }
}