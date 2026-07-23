package com.example.requirementrag.web;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.CodeGraphRequest;
import com.example.requirementrag.model.CodeGraphResponse;
import com.example.requirementrag.model.CodeIndexResponse;
import com.example.requirementrag.model.CodeSearchRequest;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.SourceSnippet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 代码知识 REST 接口：索引、搜索和源码片段读取。
 */
@RestController
@RequestMapping("/api/code")
public class CodeController {

    private final CodeKnowledgeService codeKnowledgeService;
    private final ProjectAccessGuard accessGuard;

    /** 注入代码知识服务。 */
    public CodeController(CodeKnowledgeService codeKnowledgeService, ProjectAccessGuard accessGuard) {
        this.codeKnowledgeService = codeKnowledgeService;
        this.accessGuard = accessGuard;
    }

    /** 扫描配置的 Java 仓库，并将代码 chunk 写入 Qdrant。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping("/index")
    public CodeIndexResponse index(@RequestParam(required = false) String projectId,
                                   HttpServletRequest httpRequest) throws IOException {
        accessGuard.requireProjectAccess(httpRequest, projectId);
        if (projectId != null && !projectId.isBlank()) {
            return codeKnowledgeService.index(projectId);
        }
        return codeKnowledgeService.index();
    }

    /** 对已索引代码执行语义检索。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @PostMapping("/search")
    public List<CodeChunk> search(@Valid @RequestBody CodeSearchRequest request, HttpServletRequest httpRequest) {
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return codeKnowledgeService.search(request.query(), request.projectId(), request.limit());
    }

    /** 根据查询和视图返回代码图谱。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @PostMapping("/graph")
    public CodeGraphResponse graph(@Valid @RequestBody CodeGraphRequest request, HttpServletRequest httpRequest) {
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return codeKnowledgeService.graph(request.query(), request.projectId(), request.view(), request.limit(),
                request.crossSide());
    }

    /** 返回代码索引统计。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam(required = false) String projectId,
                                      HttpServletRequest httpRequest) {
        accessGuard.requireProjectAccess(httpRequest, projectId);
        return Map.of("chunks", codeKnowledgeService.count(projectId));
    }

    /** 读取源码片段。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/source")
    public SourceSnippet source(@RequestParam(required = false) String projectId,
                                @RequestParam String filePath,
                                @RequestParam(required = false) Integer startLine,
                                @RequestParam(required = false) Integer endLine,
                                HttpServletRequest httpRequest) throws IOException {
        accessGuard.requireProjectAccess(httpRequest, projectId);
        return codeKnowledgeService.source(projectId, filePath, startLine, endLine);
    }
}
