package com.example.requirementrag.evaluation;

import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCase;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.Prediction;

/** 空预测基线：用于确认 LLM 结果到底比“全空预测”好多少。 */
public class EmptyGoldPredictor implements RequirementGraphGoldPredictor {
    @Override
    public Prediction predict(GoldCase goldCase) {
        return Prediction.empty();
    }
}