package com.example.requirementrag.wiki.module;

import com.example.requirementrag.code.CodeRelation;
import com.example.requirementrag.code.CodeSymbol;
import com.example.requirementrag.wiki.WikiModels;
import com.example.requirementrag.wiki.WikiModels.Claim;
import com.example.requirementrag.wiki.WikiModels.ClaimSupport;
import com.example.requirementrag.wiki.WikiModels.CodeEntry;
import com.example.requirementrag.wiki.WikiModels.Evidence;
import com.example.requirementrag.wiki.WikiModels.KnowledgeQuality;
import com.example.requirementrag.wiki.WikiModels.PageSource;
import com.example.requirementrag.wiki.WikiModels.PageType;
import com.example.requirementrag.wiki.WikiModels.Relation;
import com.example.requirementrag.wiki.WikiModels.RequirementSource;
import com.example.requirementrag.wiki.WikiModels.Status;
import com.example.requirementrag.wiki.WikiModels.TestKnowledge;
import com.example.requirementrag.wiki.WikiModels.VersionChange;
import com.example.requirementrag.wiki.module.ModuleFactModels.ModuleDiagnostic;
import com.example.requirementrag.wiki.module.ModuleFactModels.ModuleEvidence;
import com.example.requirementrag.wiki.module.ModuleFactModels.ModuleFactBundle;
import com.example.requirementrag.wiki.module.ModuleFactModels.ModuleFlowStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 将 ModuleFactBundle 编译为带声明级证据的模块页面源；只使用事实包，不自由扫描仓库。 */
@Component
public class ModuleWikiPlanner {

    private static final int MAX_ENTRY_POINTS = 15;
    private static final int MAX_FLOWS = 20;

    /** 规划模块页面：职责、入口、流程、依赖、数据配置、测试与缺口 Claims，关联需求证据可选。 */
    public PageSource plan(ModuleFactBundle bundle, String version, String baseVersion, String codeCommit,
                           List<WikiModels.Evidence> requirementEvidence) {
        String featureId = "module-" + safeId(bundle.moduleId());
        List<WikiModels.Evidence> codeEvidence = toEvidence(bundle);
        List<WikiModels.Evidence> evidence = new ArrayList<>(codeEvidence);
        evidence.addAll(requirementEvidence);
        List<RequirementSource> requirementSources = toRequirementSources(requirementEvidence);
        List<CodeEntry> codeEntries = toCodeEntries(bundle, codeCommit);
        List<String> symbols = bundle.publicSymbols().stream()
                .map(CodeSymbol::qualifiedName)
                .distinct()
                .limit(30)
                .toList();
        List<String> processSteps = flowSteps(bundle);
        List<String> missing = new ArrayList<>();
        if (bundle.entryPoints().isEmpty()) missing.add("对外入口证据");
        if (bundle.tests().isEmpty()) missing.add("真实测试证据");
        if (requirementEvidence.isEmpty()) missing.add("关联需求");

        List<Claim> claims = claims(bundle, requirementEvidence, evidence, missing);
        String summary = "模块 " + bundle.moduleId() + " 的确定性事实页：职责、入口、核心流程、数据配置与测试，"
                + "共 " + bundle.evidence().size() + " 条代码证据" + (requirementEvidence.isEmpty() ? "" : "、"
                + requirementEvidence.size() + " 条需求证据") + "，待人工审核。";
        List<String> risks = diagnostics(bundle);
        return new PageSource(
                featureId, bundle.title() + " 模块", "模块", version, Status.DRAFT,
                List.of(), summary,
                requirementSources, List.of("职责边界由符号图与文件扫描确定性抽取；对外边界以代码证据为准。"),
                processSteps, codeEntries, symbols,
                bundle.dataObjects(), List.of(), List.of(), List.of(),
                new TestKnowledge(bundle.tests().isEmpty() ? "NOT_AVAILABLE" : "REPORTED",
                        "", bundle.tests().isEmpty() ? "没有真实测试执行快照" : "识别到 "
                                + bundle.tests().size() + " 个测试符号，尚未关联真实报告",
                        bundle.tests().stream().limit(10).map(CodeSymbol::qualifiedName).toList()),
                new VersionChange("MODULE_FACT", text(baseVersion), version, "基于代码图谱 commit "
                        + text(bundle.commitSha()) + " 确定性编译"),
                new KnowledgeQuality("PENDING_REVIEW", requirementSources.size(), codeEntries.size(),
                        false, List.copyOf(missing)),
                risks, List.of(), List.copyOf(evidence), PageType.MODULE, claims
        );
    }

    /** 需求证据转换为页面需求来源指针：documentId/entryId/contentHash 来自原始 chunk（filePath/symbol/commit 承载）。 */
    private List<RequirementSource> toRequirementSources(List<WikiModels.Evidence> requirements) {
        return requirements.stream()
                .map(item -> new RequirementSource(
                        hasText(item.filePath()) ? item.filePath() : text(item.source()),
                        hasText(item.symbol()) ? item.symbol() : "module",
                        text(item.title()), text(item.version()), text(item.location()),
                        text(item.commit()), text(item.verificationStatus())))
                .limit(20)
                .toList();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 兼容旧调用方：不携带需求证据。 */
    public PageSource plan(ModuleFactBundle bundle, String version, String baseVersion, String codeCommit) {
        return plan(bundle, version, baseVersion, codeCommit, List.of());
    }

    /** 生成八类声明：职责、入口、流程、依赖、数据配置、测试、关联需求与知识缺口，只引用已注册证据。 */
    private List<Claim> claims(ModuleFactBundle bundle, List<Evidence> requirements,
                               List<Evidence> evidence, List<String> missing) {
        List<Claim> claims = new ArrayList<>();
        String module = bundle.moduleId();
        List<String> codeIds = evidenceIds(bundle, "CODE");
        List<String> entryIds = evidenceIds(bundle, "CODE", "ROUTE");
        List<String> flowIds = evidenceIds(bundle, "CODE_GRAPH");
        List<String> dependencyIds = evidenceIds(bundle, "DEPENDENCY");
        List<String> dataConfigIds = evidenceIds(bundle, "DATA", "CONFIG");
        List<String> testIds = evidenceIds(bundle, "TEST_SYMBOL");
        List<String> diagnosticIds = evidenceIds(bundle, "DIAGNOSTIC");
        claims.add(new Claim(module + "-responsibility", "responsibility",
                "模块 " + module + " 的职责边界由 " + bundle.publicSymbols().size() + " 个公开符号构成，"
                        + "需人工审核后确认业务语义",
                bundle.publicSymbols().isEmpty() ? ClaimSupport.UNSUPPORTED : ClaimSupport.PARTIAL,
                prefix(codeIds, 10)));
        claims.add(new Claim(module + "-entry", "entry",
                "对外入口共 " + bundle.entryPoints().size() + " 个（HTTP/消息/定时任务）",
                bundle.entryPoints().isEmpty() ? ClaimSupport.UNSUPPORTED : ClaimSupport.FULL,
                prefix(entryIds, 10)));
        claims.add(new Claim(module + "-flow", "flow",
                "核心流程为模块内符号间的有序调用链，共 " + bundle.coreFlows().size() + " 条边",
                bundle.coreFlows().isEmpty() ? ClaimSupport.UNSUPPORTED : ClaimSupport.PARTIAL,
                prefix(flowIds, 10)));
        claims.add(new Claim(module + "-dependencies", "dependencies",
                "模块调用 " + bundle.callees().size() + " 个外部符号，被 " + bundle.callers().size()
                        + " 个外部符号调用",
                bundle.callers().isEmpty() && bundle.callees().isEmpty() ? ClaimSupport.UNSUPPORTED
                        : ClaimSupport.INFERRED,
                prefix(dependencyIds, 10)));
        claims.add(new Claim(module + "-data-config", "data",
                "数据与消息对象 " + bundle.dataObjects().size() + " 个，配置来源 "
                        + bundle.configuration().size() + " 个",
                bundle.dataObjects().isEmpty() && bundle.configuration().isEmpty()
                        ? ClaimSupport.UNSUPPORTED : ClaimSupport.INFERRED,
                prefix(dataConfigIds, 10)));
        claims.add(new Claim(module + "-tests", "tests",
                "识别到 " + bundle.tests().size() + " 个测试符号；真实执行结果待关联",
                bundle.tests().isEmpty() ? ClaimSupport.UNSUPPORTED : ClaimSupport.INFERRED,
                prefix(testIds, 10)));
        if (!requirements.isEmpty()) {
            int offset = evidence.size() - requirements.size();
            claims.add(new Claim(module + "-requirements", "requirements",
                    "关联需求 " + requirements.size() + " 条（版本 " + text(requirements.get(0).version()) + "）",
                    ClaimSupport.FULL,
                    java.util.stream.IntStream.range(0, Math.min(requirements.size(), 10))
                            .mapToObj(index -> "requirement:" + (offset + index)).toList()));
        }
        if (!bundle.diagnostics().isEmpty()) {
            claims.add(new Claim(module + "-gaps", "gaps",
                    "存在 " + bundle.diagnostics().size() + " 条知识缺口（未解析调用等），发布前需处理或确认",
                    ClaimSupport.INFERRED, prefix(diagnosticIds, 10)));
        }
        return List.copyOf(claims);
    }

    /** 按事实类型选择证据，避免 Claims 共享无关的前 N 条代码证据。 */
    private List<String> evidenceIds(ModuleFactBundle bundle, String... types) {
        Set<String> accepted = Set.of(types);
        return bundle.evidence().stream()
                .filter(item -> accepted.contains(item.type()))
                .map(ModuleEvidence::evidenceId)
                .toList();
    }

    private List<String> prefix(List<String> ids, int count) {
        return ids.size() <= count ? ids : ids.subList(0, count);
    }

    /** 把模块事实转为页面证据列表（与 Claim 引用的 evidenceId 一一对应）。 */
    private List<Evidence> toEvidence(ModuleFactBundle bundle) {
        List<Evidence> result = new ArrayList<>();
        for (ModuleEvidence item : bundle.evidence()) {
            result.add(new Evidence(item.type(), item.symbol(), bundle.projectId(), item.version(),
                    "lines=" + item.startLine() + '-' + item.endLine(),
                    item.symbol() + " @ " + item.source(), item.commitSha(), item.source(),
                    item.symbol(), "PENDING_REVIEW"));
        }
        return List.copyOf(result);
    }

    private List<CodeEntry> toCodeEntries(ModuleFactBundle bundle, String codeCommit) {
        return bundle.entryPoints().stream()
                .map(symbol -> new CodeEntry("对外入口", symbol.filePath(), symbol.qualifiedName(),
                        text(codeCommit).isBlank() ? text(bundle.commitSha()) : text(codeCommit),
                        "CURRENT_VERSION", "PENDING_REVIEW"))
                .limit(MAX_ENTRY_POINTS)
                .toList();
    }

    private List<String> flowSteps(ModuleFactBundle bundle) {
        return bundle.coreFlows().stream()
                .limit(MAX_FLOWS)
                .map(this::flowStepText)
                .toList();
    }

    private String flowStepText(ModuleFlowStep step) {
        String suffix = step.resolution() == CodeRelation.Resolution.UNRESOLVED
                || step.resolution() == CodeRelation.Resolution.HEURISTIC
                ? "（" + step.resolution() + "）" : "";
        return step.caller() + " -> " + step.callee() + " @ " + step.filePath() + ":" + step.line() + suffix;
    }

    private List<String> diagnostics(ModuleFactBundle bundle) {
        return bundle.diagnostics().stream()
                .map(diagnostic -> "[" + diagnostic.code() + "] " + diagnostic.message() + "（"
                        + diagnostic.source() + "）")
                .limit(15)
                .toList();
    }

    private String safeId(String value) {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-|-$", "");
        return id.isBlank() ? "module" : id;
    }

    private String text(String value) {
        return value == null ? "" : value;
    }
}
