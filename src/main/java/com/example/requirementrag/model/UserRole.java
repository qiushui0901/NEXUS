package com.example.requirementrag.model;

/** 用户角色枚举，按权限从高到低排列。ordinal() 越小权限越高。 */
public enum UserRole {
    SUPER_ADMIN,
    PROJECT_ADMIN,
    DEVELOPER,
    READONLY;

    /** 当前角色是否蕴含（权限 >= ）目标角色。 */
    public boolean implies(UserRole other) {
        return this.ordinal() <= other.ordinal();
    }
}
