package com.example.requirementrag.model;

/**
 * API 访问权限级别，数值越高所需角色越高。
 */
public enum Permission {
    /** 只读检索（搜索、图谱、跨项目查询）— 所有角色。 */
    PUBLIC_READ,
    /** 业务操作（评审、开发方案生成）— DEVELOPER 及以上。 */
    OPERATE,
    /** 数据写入（索引、导入、引导）— PROJECT_ADMIN 及以上。 */
    WRITE,
    /** 管理操作 — 仅 SUPER_ADMIN。 */
    ADMIN
}
