package com.example.requirementrag.evolution.experience;

import java.util.List;

/** 检索经验事件存储。 */
public interface RetrievalExperienceStore {

    /** 追加一条经验事件；实现必须保证失败不抛出到调用方（由 recorder 处理）。 */
    void append(RetrievalExperience experience);

    /** 读取当前保留期内全部经验事件，用于失败挖掘和实验回放。 */
    List<RetrievalExperience> readAll();
}
