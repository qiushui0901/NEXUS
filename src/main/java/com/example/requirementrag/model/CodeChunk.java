package com.example.requirementrag.model;

import java.util.List;
import java.util.Locale;

/**
 * 可写入向量库的代码片段，包含静态分析与 LLM 生成的语义元数据。
 * 按照企业级代码 RAG 标注规范，涵盖文件/模块/类/方法/调用/业务/风险多级标注。
 */
public record CodeChunk(
        String id,
        String projectId,
        String commitSha,
        String filePath,
        String symbolType,
        String symbolName,
        int startLine,
        int endLine,
        String text,
        String contentHash,
        String language,
        String className,
        String module,
        String layer,
        String businessDescCn,
        String businessDescEn,
        List<String> callRelation,
        List<String> keywords,
        List<String> userQuestions,
        List<String> synonyms,
        String extendsClass,
        List<String> implementsInterfaces,
        List<String> annotations,
        String repositoryId,
        String repositoryName,
        String repositoryKind
) {
    /** 兼容已有完整构造器：仓库元数据尚未提供时留空。 */
    public CodeChunk(String id, String projectId, String commitSha, String filePath,
                     String symbolType, String symbolName, int startLine, int endLine,
                     String text, String contentHash, String language, String className,
                     String module, String layer, String businessDescCn, String businessDescEn,
                     List<String> callRelation, List<String> keywords, List<String> userQuestions,
                     List<String> synonyms, String extendsClass, List<String> implementsInterfaces,
                     List<String> annotations) {
        this(id, projectId, commitSha, filePath, symbolType, symbolName, startLine, endLine,
                text, contentHash, language, className, module, layer, businessDescCn, businessDescEn,
                callRelation, keywords, userQuestions, synonyms, extendsClass, implementsInterfaces,
                annotations, "", "", "");
    }
    /**
     * 兼容旧构造器：供 0.7 版本之前的调用方及已存储载荷使用，
     * 未指定语言时按文件路径推断 {@link com.example.requirementrag.code.CodeLanguage}。
     */
    public CodeChunk(String id, String projectId, String commitSha, String filePath,
                     String symbolType, String symbolName, int startLine, int endLine,
                     String text, String contentHash) {
        this(id, projectId, commitSha, filePath, symbolType, symbolName, startLine, endLine,
                text, contentHash, com.example.requirementrag.code.CodeLanguage.fromPath(filePath).id(),
                "", "", "", "", "", List.of(), List.of(), List.of(), List.of(), "", List.of(), List.of());
    }

    /** 兼容旧构造器：显式指定语言，语义字段留空。 */
    public CodeChunk(String id, String projectId, String commitSha, String filePath,
                     String symbolType, String symbolName, int startLine, int endLine,
                     String text, String contentHash, String language) {
        this(id, projectId, commitSha, filePath, symbolType, symbolName, startLine, endLine,
                text, contentHash, language,
                "", "", "", "", "", List.of(), List.of(), List.of(), List.of(), "", List.of(), List.of());
    }

    /** 紧凑构造器：将可能为 null 的列表归一化为不可变空列表。 */
    public CodeChunk {
        callRelation = callRelation == null ? List.of() : List.copyOf(callRelation);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        userQuestions = userQuestions == null ? List.of() : List.copyOf(userQuestions);
        synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
        implementsInterfaces = implementsInterfaces == null ? List.of() : List.copyOf(implementsInterfaces);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
        repositoryId = repositoryId == null ? "" : repositoryId;
        repositoryName = repositoryName == null ? "" : repositoryName;
        repositoryKind = repositoryKind == null ? "" : repositoryKind;
    }

    /** 将目录中的仓库身份附加到检索结果，不改变底层代码内容。 */
    public CodeChunk withRepositoryMetadata(String id, String name, String kind) {
        return new CodeChunk(this.id, this.projectId, this.commitSha, this.filePath, this.symbolType,
                this.symbolName, this.startLine, this.endLine, this.text, this.contentHash, this.language,
                this.className, this.module, this.layer, this.businessDescCn, this.businessDescEn,
                this.callRelation, this.keywords, this.userQuestions, this.synonyms, this.extendsClass,
                this.implementsInterfaces, this.annotations, id, name, kind);
    }

    /** 附加语义元数据（annotator 阶段，向后兼容）。 */
    public CodeChunk withSemantics(String businessDescCn, String businessDescEn,
                                    List<String> callRelation, List<String> keywords) {
        return new CodeChunk(id, projectId, commitSha, filePath, symbolType, symbolName,
                startLine, endLine, text, contentHash, language,
                className, module, layer, businessDescCn, businessDescEn,
                callRelation, keywords, userQuestions, synonyms,
                extendsClass, implementsInterfaces, annotations, repositoryId, repositoryName, repositoryKind);
    }

    /** 附加完整语义元数据（含 user_questions 和 synonyms）。 */
    public CodeChunk withFullSemantics(String businessDescCn, String businessDescEn,
                                        List<String> callRelation, List<String> keywords,
                                        List<String> userQuestions, List<String> synonyms) {
        return new CodeChunk(id, projectId, commitSha, filePath, symbolType, symbolName,
                startLine, endLine, text, contentHash, language,
                className, module, layer, businessDescCn, businessDescEn,
                callRelation, keywords, userQuestions, synonyms,
                extendsClass, implementsInterfaces, annotations, repositoryId, repositoryName, repositoryKind);
    }

    /** 设置静态分析结果（extends/implements/annotations）与类级信息。 */
    public CodeChunk withStaticAnalysis(String className, String extendsClass,
                                         List<String> implementsInterfaces, List<String> annotations) {
        String inferredModule = module == null || module.isBlank() ? inferModule(filePath) : module;
        String inferredLayer = layer == null || layer.isBlank() ? inferLayer(className, filePath) : layer;
        return new CodeChunk(id, projectId, commitSha, filePath, symbolType, symbolName,
                startLine, endLine, text, contentHash, language,
                className, inferredModule, inferredLayer, businessDescCn, businessDescEn,
                callRelation, keywords, userQuestions, synonyms,
                extendsClass, implementsInterfaces, annotations, repositoryId, repositoryName, repositoryKind);
    }

    /** 从文件路径推断所属模块（包路径中第一个有意义的段）。 */
    private static String inferModule(String filePath) {
        if (filePath == null || filePath.isBlank()) return "";
        String[] segments = filePath.replace('\\', '/').split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            String seg = segments[i].toLowerCase(Locale.ROOT);
            if (seg.endsWith(".java") || seg.equals("java") || seg.equals("main")
                    || seg.equals("src") || seg.equals("com") || seg.equals("service")
                    || seg.equals("controller") || seg.equals("dao") || seg.equals("model")
                    || seg.equals("handler") || seg.equals("moa") || seg.equals("util")
                    || seg.equals("config") || seg.equals("common") || seg.equals("example")) {
                continue;
            }
            if (seg.length() > 2) return seg;
        }
        return "";
    }

    /** 按类名与路径推断代码分层（controller/service/dao/model 等）。 */
    private static String inferLayer(String className, String filePath) {
        if (className == null) className = "";
        String lower = className.toLowerCase(Locale.ROOT);
        String pathLower = filePath == null ? "" : filePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith("controller") || lower.endsWith("moa") || pathLower.contains("/controller/") || pathLower.contains("/moa/"))
            return "controller";
        if (lower.endsWith("service") || lower.endsWith("manager") || pathLower.contains("/service/"))
            return "service";
        if (lower.endsWith("dao") || lower.endsWith("mapper") || lower.endsWith("repository") || pathLower.contains("/dao/"))
            return "dao";
        if (lower.endsWith("handler") || lower.endsWith("processor") || lower.endsWith("facade"))
            return "handler";
        if (lower.endsWith("model") || lower.endsWith("entity") || lower.endsWith("bean")
                || lower.endsWith("dto") || lower.endsWith("vo") || lower.endsWith("bo") || pathLower.contains("/model/"))
            return "model";
        if (lower.endsWith("config") || lower.endsWith("cfg") || lower.endsWith("properties") || pathLower.contains("/config/"))
            return "config";
        if (lower.endsWith("util") || lower.endsWith("utils") || lower.endsWith("helper") || pathLower.contains("/util/"))
            return "util";
        if (lower.endsWith("rpc") || lower.endsWith("client") || lower.endsWith("feign") || pathLower.contains("/rpc/"))
            return "rpc";
        if (lower.endsWith("listener") || lower.endsWith("observer") || lower.endsWith("event"))
            return "event";
        return "";
    }
}
