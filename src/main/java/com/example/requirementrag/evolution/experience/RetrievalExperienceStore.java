package com.example.requirementrag.evolution.experience;

import java.util.List;

/** 检索经验事件存储。 */
public interface RetrievalExperienceStore {

    /**
     * 追加一条经验事件。
     *
     * @return true 表示写入成功；false 表示写入失败（调用方负责记录 dropped/write_failures 指标）
     */
    boolean append(RetrievalExperience experience);

    /** 读取当前保留期内全部经验事件，用于失败挖掘和实验回放。 */
    List<RetrievalExperience> readAll();
}
