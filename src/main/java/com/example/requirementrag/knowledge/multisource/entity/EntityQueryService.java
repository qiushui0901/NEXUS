package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.Citation;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntitySearchResponse;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntityView;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.FactAssessment;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceAggregator.Options;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityMention;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityQueryPlan;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityResolution;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 实体中心证据查询服务（dev md §8.2）：问题 → 实体提取 → 实体解析 → 全版本聚合 → 结构化证据响应。
 * LLM 全程可选：不可用时规则链完整可用。第一版不改动现有 multi-source/search。
 */
@Service
public class EntityQueryService {

    private final QuestionEntityAnalyzer analyzer;
    private final EntityResolverService resolver;
    private final EntityEvidenceAggregator aggregator;
    private final EntityFactPriorityService factPriorityService;

    public EntityQueryService(QuestionEntityAnalyzer analyzer,
                              EntityResolverService resolver,
                              EntityEvidenceAggregator aggregator,
                              EntityFactPriorityService factPriorityService) {
        this.analyzer = analyzer;
        this.resolver = resolver;
        this.aggregator = aggregator;
        this.factPriorityService = factPriorityService;
    }

    /** 实体中心检索请求。 */
    public record EntitySearchRequest(
            String projectId,
            String query,
            List<String> versions,
            Boolean includeHistory,
            Boolean includeCode,
            Boolean includeParameters,
            Boolean includeTests,
            Integer limit
    ) {
    }

    public EntitySearchResponse search(EntitySearchRequest request) {
        String projectId = request.projectId();
        String query = request.query();
        boolean includeCode = request.includeCode() == null || request.includeCode();
        boolean includeParameters = request.includeParameters() == null || request.includeParameters();
        boolean includeTests = request.includeTests() == null || request.includeTests();

        // 1. 问题分析（规则优先）；请求显式 includeHistory 覆盖规则推导值
        EntityQueryPlan rulePlan = analyzer.analyze(projectId, query);
        boolean includeHistory = request.includeHistory() != null
                ? request.includeHistory() : rulePlan.includeHistory();
        // High：显式请求版本必须生效——request.versions() 覆盖分析器从查询文本抽取的版本范围
        // （前端版本输入 → 实体聚合/向量补召回/回答全部按此范围执行；空则沿用分析器推导）
        List<String> versions = (request.versions() != null && !request.versions().isEmpty())
                ? java.util.List.copyOf(request.versions()) : rulePlan.requestedVersions();
        EntityQueryPlan plan = new EntityQueryPlan(
                rulePlan.projectId(), rulePlan.originalQuery(), rulePlan.mentions(),
                rulePlan.intent(), versions, includeHistory,
                rulePlan.asksCurrentState(), rulePlan.asksImplementation(), rulePlan.asksNumericValue());
        List<String> warnings = new ArrayList<>();

        // 2. 实体解析：规则命中优先；规则未命中走成员名/代码符号 + LLM 受限选择
        EntityResolution resolution = plan.mentions().isEmpty()
                ? resolver.resolve(projectId, query)
                : resolver.resolve(projectId, query, plan.mentions());
        List<EntityMention> resolvedMentions = resolution.resolved();
        if (resolvedMentions.isEmpty() && !resolution.candidates().isEmpty()) {
            warnings.add("ENTITY_NEEDS_REVIEW");
        }
        warnings.addAll(resolution.warnings());

        // 3. 全版本证据聚合（limit 接入每块/分区条目上限；includeHistory=false 时 timeline 为空）
        int perBlock = request.limit() == null ? 20 : Math.max(1, Math.min(50, request.limit()));
        List<EntityView> views = aggregator.aggregate(projectId, plan, resolvedMentions,
                new Options(perBlock, includeCode, includeParameters, includeTests, includeHistory));
        List<Citation> citations = aggregator.citations(projectId, views);

        // 4. 每个实体都参与评估；响应级摘要取所有实体的确定性风险并保留全部事实分区
        FactAssessment assessment = assessMerged(plan, views);

        return new EntitySearchResponse(query, plan, views, assessment, citations, warnings);
    }

    /** 对给定实体集做响应级确定性评估摘要（图/向量增强召回复用：合并实体集的评估同态）。 */
    public FactAssessment assessMerged(EntityQueryPlan plan, List<EntityView> views) {
        return views.isEmpty()
                ? FactAssessment.EMPTY
                : mergeAssessments(views.stream().map(view -> factPriorityService.assess(plan, view)).toList());
    }

    private FactAssessment mergeAssessments(List<FactAssessment> assessments) {
        List<com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.AssessmentItem> behavior = new ArrayList<>();
        List<com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.AssessmentItem> values = new ArrayList<>();
        List<com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.AssessmentItem> validation = new ArrayList<>();
        List<com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.AssessmentItem> requirements = new ArrayList<>();
        List<com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.AssessmentItem> gaps = new ArrayList<>();
        for (FactAssessment item : assessments) {
            behavior.addAll(item.currentBehavior());
            values.addAll(item.currentValues());
            validation.addAll(item.validation());
            requirements.addAll(item.requirementTarget());
            gaps.addAll(item.implementationGaps());
        }
        return new FactAssessment(cap(behavior), cap(values), cap(validation), cap(requirements), cap(gaps));
    }

    private <T> List<T> cap(List<T> values) {
        return values.stream().limit(20).toList();
    }
}