package com.example.requirementrag.evaluation;

import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCase;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PredictedRelation;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.Prediction;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则预测器 = 评测基线。
 *
 * <p>仅做极轻量启发式：问题/冲突/未给出 → 不确定性；REQ 编号 → 实体与 REFERENCES。
 * 用于验证评测管道本身，不代表系统真实能力。
 */
@Component
public class RuleGoldPredictor implements RequirementGraphGoldPredictor {

    private static final Pattern REQ_ID = Pattern.compile("REQ-\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUESTION_MARK = Pattern.compile("[?？]|冲突|未给出|未确认|是否存在|是否支持");

    @Override
    public Prediction predict(GoldCase goldCase) {
        String text = goldCase.inputText() == null ? "" : goldCase.inputText();
        Set<String> entities = new LinkedHashSet<>();
        List<PredictedRelation> relations = new ArrayList<>();
        List<String> uncertainties = new ArrayList<>();
        if (QUESTION_MARK.matcher(text).find()) {
            String snippet = text.trim();
            if (snippet.length() > 100) snippet = snippet.substring(0, 100);
            uncertainties.add(snippet);
            return new Prediction(entities, relations, List.of(), uncertainties);
        }
        Matcher matcher = REQ_ID.matcher(text);
        String own = null;
        while (matcher.find()) {
            String req = matcher.group().toUpperCase(Locale.ROOT);
            entities.add(req);
            if (own == null) {
                own = req;
            } else {
                String id = "rule:" + sha256(own + "|" + req).substring(0, 16);
                relations.add(new PredictedRelation(own, req, "REFERENCES"));
            }
        }
        if (text.contains("GrowFundService")) {
            entities.add("GrowFundService");
        }
        return new Prediction(entities, relations, List.of(), uncertainties);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}