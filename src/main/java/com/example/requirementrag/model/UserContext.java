package com.example.requirementrag.model;

import java.util.List;

public record UserContext(String username, UserRole role, List<String> projects) {

    public static final String REQUEST_ATTRIBUTE = "currentUser";

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

    public static UserContext defaultAdmin() {
        return new UserContext("system", UserRole.SUPER_ADMIN, List.of("*"));
    }
}
