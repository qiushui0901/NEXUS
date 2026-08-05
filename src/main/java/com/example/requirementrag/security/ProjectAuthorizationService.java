package com.example.requirementrag.security;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.web.AccessDeniedException;
import org.springframework.stereotype.Service;

/** 与传输层无关的权限与项目范围授权。 */
@Service
public class ProjectAuthorizationService {

    private final ProjectRegistry projectRegistry;

    public ProjectAuthorizationService(ProjectRegistry projectRegistry) {
        this.projectRegistry = projectRegistry;
    }

    /** 校验用户是否具备指定权限，不具备时抛 AccessDeniedException。 */
    public void requirePermission(UserContext user, Permission permission) {
        if (user == null || !user.hasPermission(permission)) {
            throw new AccessDeniedException("Insufficient permissions");
        }
    }

    /**
     * 校验用户对项目的访问权：请求项目为空时回退到默认项目；无权访问时抛 AccessDeniedException。
     *
     * @param user              当前用户上下文
     * @param requestedProjectId 请求的项目 ID，可为空
     * @return 解析后的项目 ID（已 trim，或默认项目 ID）
     */
    public String requireProjectAccess(UserContext user, String requestedProjectId) {
        String effective = hasText(requestedProjectId)
                ? requestedProjectId.trim()
                : projectRegistry.defaultProject().id();
        if (hasText(effective) && (user == null || !user.hasAccessTo(effective))) {
            throw new AccessDeniedException("Insufficient permissions");
        }
        return effective;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
