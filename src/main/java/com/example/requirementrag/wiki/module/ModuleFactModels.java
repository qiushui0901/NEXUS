package com.example.requirementrag.wiki.module;

import com.example.requirementrag.code.CodeRelation;
import com.example.requirementrag.code.CodeSymbol;

import java.util.List;

/** Module 页面纵向闭环的契约模型：事实包、诊断、证据与请求。 */
public final class ModuleFactModels {
    private ModuleFactModels() {}

    /** 一条无法静态确认的模块诊断，不能改写为确定事实。 */
    public record ModuleDiagnostic(String code, String message, String source) {}

    /** 已注册的模块事实证据：页面 Claim 只能引用这些 ID。 */
    public record ModuleEvidence(
            String evidenceId,
            String type,
            String projectId,
            String version,
            String commitSha,
            String source,
            String symbol,
            int startLine,
            int endLine,
            String contentHash
    ) {}

    /** 一个核心流程步骤：调用方 -> 被调用方（同一文件或跨文件）。 */
    public record ModuleFlowStep(String caller, String callee, String filePath, int line,
                                 CodeRelation.Resolution resolution) {}

    /** 模块事实包：页面生成的唯一事实输入，模型不得绕过它自由扫描仓库。 */
    public record ModuleFactBundle(
            String projectId,
            String commitSha,
            String moduleId,
            String title,
            String modulePath,
            List<String> sourceRoots,
            List<String> packages,
            List<CodeSymbol> publicSymbols,
            List<CodeSymbol> entryPoints,
            List<String> callers,
            List<String> callees,
            List<ModuleFlowStep> coreFlows,
            List<String> routes,
            List<String> dataObjects,
            List<String> configuration,
            List<CodeSymbol> tests,
            List<ModuleEvidence> evidence,
            List<ModuleDiagnostic> diagnostics
    ) {}

    /** 模块知识构建请求：明确目标模块，不做全仓自动聚类。 */
    public record ModuleBuildRequest(
            String projectId,
            String version,
            String modulePath,
            String codeCommit,
            String actor,
            String documentId,
            String requirementVersion
    ) {
        /** 兼容旧调用方：不携带需求文档标识。 */
        public ModuleBuildRequest(String projectId, String version, String modulePath, String codeCommit,
                                  String actor) {
            this(projectId, version, modulePath, codeCommit, actor, null, null);
        }
    }
}
