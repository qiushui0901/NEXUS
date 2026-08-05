package com.example.requirementrag.model;

import java.util.List;

/** 当前请求的用户上下文模型：记录用户名、角色与可访问项目列表，并据此做访问控制判断。 */
public record UserContext(String username, UserRole role, List<String> projects) {

    /** 请求属性名，用于在请求上下文中存放当前用户。 */
    public static final String REQUEST_ATTRIBUTE = "currentUser";

    /** 判断当前用户是否有权访问指定项目：SUPER_ADMIN 全部放行，项目列表含 "*" 表示通配所有项目。 */
    public boolean hasAccessTo(String projectId) {
        if (role == UserRole.SUPER_ADMIN) {
            return true;
        }
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        if (projects.contains("*")) {
            return true;
        }
        return projects.contains(projectId);
    }

    /** 判断当前用户是否满足某权限级别：将权限映射为所需最低角色后与当前角色比较。 */
    public boolean hasPermission(Permission permission) {
        UserRole required = switch (permission) {
            case PUBLIC_READ -> UserRole.READONLY;
            case OPERATE -> UserRole.DEVELOPER;
            case WRITE -> UserRole.PROJECT_ADMIN;
            case ADMIN -> UserRole.SUPER_ADMIN;
        };
        return role.implies(required);
    }

    /** @deprecated 使用 {@link #hasPermission(Permission)} */
    @Deprecated
    public boolean canWrite() {
        return hasPermission(Permission.WRITE);
    }

    /** 构造系统默认管理员上下文：用户名 system、SUPER_ADMIN 角色、通配所有项目。 */
    public static UserContext defaultAdmin() {
        return new UserContext("system", UserRole.SUPER_ADMIN, List.of("*"));
    }
}
