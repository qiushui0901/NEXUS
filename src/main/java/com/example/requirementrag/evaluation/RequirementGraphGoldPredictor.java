package com.example.requirementrag.evaluation;

import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCase;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.Prediction;

/** 金标评测预测器 SPI：给定一个 GoldCase 返回系统的实体/关系/Claim/存疑预测。 */
public interface RequirementGraphGoldPredictor {
    Prediction predict(GoldCase goldCase);
}