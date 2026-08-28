package com.example.requirementrag.knowledge.multisource.alignment;

import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.CodeSymbolView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves source names to the gameplay-card boundary used by the knowledge-generation prompt.
 *
 * <p>A claim remains an atomic fact. This resolver only chooses the cross-source alignment
 * anchor: one gameplay/system card is one {@code canonical_module}. Version suffixes, UI-only
 * suffixes, child pages and known configuration-table families are folded into that anchor.
 * Unknown synthetic records retain the existing module/subject key so that old callers do not
 * silently acquire a guessed cross-domain mapping.</p>
 */
@Component
public class GameplayCardModuleResolver {

    private static final List<Map.Entry<String, String>> ALIASES = aliases();
    private static final List<Map.Entry<String, String>> TABLE_PREFIXES = tablePrefixes();

    /** Resolve a claim to its gameplay-card module, or the legacy module fallback. */
    public String resolve(KnowledgeClaimRecord claim) {
        if (claim == null) {
            return "";
        }
        String documentName = documentName(claim.documentVersionId(), claim.sourceType());
        String rawModule = AlignmentNaming.moduleOf(claim.sourceType(), claim.factKey(), claim.subject());
        String subject = safe(claim.subject());
        if (documentName.isBlank()) {
            // Synthetic/legacy records do not carry a catalog category. Keep their explicit
            // fact-key module so records from different sources continue to align as before.
            return rawModule;
        }

        String candidate = switch (claim.sourceType()) {
            case PARAMETER_TABLE -> firstNonBlank(documentName, rawModule, subject);
            case TEST_CASE -> firstNonBlank(documentName, rawModule, subject);
            case REQUIREMENT -> firstNonBlank(documentName, subject, rawModule);
            case DOUBT -> firstNonBlank(cardMention(subject), rawModule, documentName, "存疑");
            default -> firstNonBlank(rawModule, subject);
        };
        return canonicalize(candidate);
    }

    /** Return the stable entity key; source records with no catalog boundary keep the legacy key. */
    public String canonicalKey(KnowledgeClaimRecord claim) {
        if (claim == null) {
            return "";
        }
        if (!hasGameplayCardBoundary(claim)) {
            return legacyFallback(claim);
        }
        return AlignmentNaming.keySegment(resolve(claim));
    }

    /** Whether the claim carries a known source boundary that is safe to collapse to one card. */
    public boolean hasGameplayCardBoundary(KnowledgeClaimRecord claim) {
        if (claim == null) {
            return false;
        }
        String documentName = documentName(claim.documentVersionId(), claim.sourceType());
        return !documentName.isBlank();
    }

    /** Resolve a code symbol to a gameplay card using class, qualified name, or file keywords. */
    public String resolveCode(CodeSymbolView symbol) {
        if (symbol == null) {
            return "";
        }
        String text = firstNonBlank(symbol.qualifiedName(), symbol.simpleName(), symbol.filePath());
        String normalized = AlignmentNaming.normalize(text);
        if (normalized.isBlank()) {
            return "";
        }
        String resolved = canonicalize(normalized);
        return resolved.equals(cleanPageName(normalized)) ? "" : resolved;
    }

    /** Return the source names that should be registered as aliases for the resolved card. */
    public List<String> aliases(KnowledgeClaimRecord claim) {
        if (claim == null) {
            return List.of();
        }
        String canonical = resolve(claim);
        LinkedHashMap<String, Boolean> values = new LinkedHashMap<>();
        add(values, canonical);
        add(values, claim.subject());
        add(values, AlignmentNaming.moduleOf(claim.sourceType(), claim.factKey(), claim.subject()));
        String document = documentName(claim.documentVersionId(), claim.sourceType());
        add(values, document);
        return List.copyOf(values.keySet());
    }

    private String canonicalize(String value) {
        String cleaned = cleanPageName(value);
        if (cleaned.isBlank()) {
            return "未分类";
        }
        String normalized = AlignmentNaming.normalize(cleaned);
        // Table-family mappings are more specific than a generic keyword. For example,
        // ImmortalHeroEquip belongs to the equipment card, not the broad hero card.
        for (Map.Entry<String, String> prefix : TABLE_PREFIXES) {
            if (normalized.startsWith(AlignmentNaming.normalize(prefix.getKey()))) {
                return prefix.getValue();
            }
        }
        for (Map.Entry<String, String> alias : ALIASES) {
            if (normalized.equals(AlignmentNaming.normalize(alias.getKey()))
                    || normalized.contains(AlignmentNaming.normalize(alias.getKey()))) {
                return alias.getValue();
            }
        }
        return cleaned;
    }

    private String cleanPageName(String value) {
        String result = safe(value);
        if (result.isBlank()) {
            return "";
        }
        result = result.replaceFirst("(?i)\\.(html?|xlsx?)$", "");
        result = result.replaceAll("(?i)[_\\-\\s]*ver(?:sion)?[_\\-\\s]*[a-z0-9]+", "");
        result = result.replaceAll("[（(]\\s*(?:ver|版本)[^）)]*[）)]", "");
        result = result.replaceAll("(?i)[_\\-\\s]*(?:ui|client|server)$", "");
        result = result.replaceAll("(?:界面优化|逻辑优化|推荐逻辑优化|优化)$", "");
        result = result.replaceAll("(?:界面|说明|相关|表现层)$", "");
        result = result.replaceAll("[_\\-\\s]+$", "").trim();
        return result;
    }

    /** Current importer IDs are dv-<project>-<version>-<category>-<source-name>. */
    private String documentName(String documentVersionId, SourceType sourceType) {
        String value = safe(documentVersionId);
        if (value.isBlank()) {
            return "";
        }
        String marker = switch (sourceType) {
            case REQUIREMENT -> "-prd-";
            case PARAMETER_TABLE -> "-data-";
            case TEST_CASE -> "-case-";
            case DOUBT -> "-qa-";
            default -> "";
        };
        if (marker.isBlank()) {
            return "";
        }
        int start = value.indexOf(marker);
        if (start < 0) {
            return "";
        }
        String name = value.substring(start + marker.length());
        // Content-hash suffixes are added only to changed case registrations.
        if (sourceType == SourceType.TEST_CASE && name.matches(".+-[0-9a-fA-F]{8}$")) {
            name = name.substring(0, name.length() - 9);
        }
        return name;
    }

    private String cardMention(String question) {
        String normalized = AlignmentNaming.normalize(question);
        if (normalized.isBlank()) {
            return "";
        }
        return ALIASES.stream()
                .filter(entry -> normalized.contains(AlignmentNaming.normalize(entry.getKey())))
                .max(Comparator.comparingInt(entry -> AlignmentNaming.normalize(entry.getKey()).length()))
                .map(Map.Entry::getValue)
                .orElse("");
    }

    private String legacyFallback(KnowledgeClaimRecord claim) {
        String module = AlignmentNaming.moduleOf(claim.sourceType(), claim.factKey(), claim.subject());
        return canonicalLegacy(module, claim.subject());
    }

    private String canonicalLegacy(String module, String subject) {
        String m = AlignmentNaming.keySegment(module);
        String s = AlignmentNaming.keySegment(subject);
        return s.isBlank() ? m : (m.isBlank() ? s : m + "." + s);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private void add(Map<String, Boolean> values, String value) {
        if (value != null && !value.isBlank()) {
            values.putIfAbsent(value.trim(), Boolean.TRUE);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<Map.Entry<String, String>> aliases() {
        Map<String, String> mappings = new LinkedHashMap<>();
        // Page merge examples from ragflow-kb-generation-prompt.md.
        put(mappings, "山河图", "山河图");
        put(mappings, "山河图-宠物农场", "山河图");
        put(mappings, "任务", "任务");
        put(mappings, "任务完成提示框", "任务");
        put(mappings, "装备", "装备");
        put(mappings, "装备合成", "装备");
        put(mappings, "联盟", "联盟");
        put(mappings, "创建_加入联盟", "联盟");
        put(mappings, "联盟管理", "联盟");
        put(mappings, "联盟任务", "联盟");
        put(mappings, "联盟商店", "联盟");
        put(mappings, "联盟研究", "联盟");
        put(mappings, "联盟指挥", "联盟");
        put(mappings, "查看其他联盟", "联盟");
        put(mappings, "英雄", "英雄");
        put(mappings, "英雄招募", "英雄");
        put(mappings, "英雄皮肤", "英雄");
        put(mappings, "英雄库", "英雄");
        put(mappings, "英雄相关", "英雄");
        put(mappings, "英雄境界", "英雄");
        put(mappings, "英雄链接", "英雄");
        put(mappings, "英雄魂玉", "英雄");
        put(mappings, "签到", "签到");
        put(mappings, "每日签到", "签到");
        put(mappings, "三界擂台", "三界擂台");
        put(mappings, "排行榜", "排行");
        put(mappings, "排行", "排行");
        put(mappings, "战斗", "战斗");
        put(mappings, "战斗流程", "战斗");
        put(mappings, "战斗逻辑", "战斗");
        put(mappings, "挂机战斗", "挂机战斗");
        put(mappings, "自动推图", "副本");
        put(mappings, "副本", "副本");
        put(mappings, "大世界", "大世界");
        put(mappings, "世界地图", "大世界");
        put(mappings, "宠物", "宠物");
        put(mappings, "宠物招募", "宠物");
        put(mappings, "修炼", "修炼");
        put(mappings, "外观", "外观");
        put(mappings, "法宝", "法宝");
        put(mappings, "神器", "神器");
        put(mappings, "抽奖", "抽奖");
        put(mappings, "招募", "招募");
        put(mappings, "商城", "商城");
        put(mappings, "商店", "商城");
        put(mappings, "奖励", "奖励");
        put(mappings, "福利", "福利");
        put(mappings, "功能解锁", "功能解锁");
        put(mappings, "聊天", "聊天");
        put(mappings, "邮件", "邮件");
        put(mappings, "联盟红包", "联盟");
        put(mappings, "联盟推荐", "联盟");
        put(mappings, "alliance", "联盟");
        put(mappings, "union", "联盟");
        put(mappings, "hero", "英雄");
        put(mappings, "skill", "技能");
        put(mappings, "artifact", "神器");
        put(mappings, "equipment", "装备");
        put(mappings, "task", "任务");
        put(mappings, "arena", "三界擂台");
        put(mappings, "rank", "排行");
        put(mappings, "pve", "副本");
        put(mappings, "tower", "副本");
        put(mappings, "world", "大世界");
        put(mappings, "farm", "山河图");
        put(mappings, "role", "主角");
        put(mappings, "buff", "增益");
        put(mappings, "draw", "抽奖");
        put(mappings, "lottery", "抽奖");
        put(mappings, "shop", "商城");
        put(mappings, "recharge", "商城");
        put(mappings, "reward", "奖励");
        put(mappings, "sign", "签到");
        // Longest aliases must be tested first when one name contains another.
        List<Map.Entry<String, String>> result = new ArrayList<>(mappings.entrySet());
        result.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
        return List.copyOf(result);
    }

    private static List<Map.Entry<String, String>> tablePrefixes() {
        Map<String, String> mappings = new LinkedHashMap<>();
        put(mappings, "ImmortalAlliance", "联盟");
        put(mappings, "ImmortalArena", "三界擂台");
        put(mappings, "ImmortalRankReward", "排行");
        put(mappings, "ImmortalHeroEquip", "装备");
        put(mappings, "ImmortalHeroEquipBonus", "装备");
        put(mappings, "ImmortalHeroEquipMerge", "装备");
        put(mappings, "ImmortalHeroEquipSuit", "装备");
        put(mappings, "ImmortalHero", "英雄");
        put(mappings, "ImmortalSkill", "技能");
        put(mappings, "ImmortalTask", "任务");
        put(mappings, "ImmortalDailyTask", "任务");
        put(mappings, "immortalSign", "签到");
        put(mappings, "ImmortalPve", "副本");
        put(mappings, "ImmortalMonsterGroup", "副本");
        put(mappings, "ImmortalTower", "副本");
        put(mappings, "ImmortalWorld", "大世界");
        put(mappings, "ImmortalFarm", "山河图");
        put(mappings, "ImmortalBuilding", "建筑");
        put(mappings, "ImmortalRole", "主角");
        put(mappings, "ImmortalBuff", "增益");
        put(mappings, "ImmortalDraw", "抽奖");
        put(mappings, "ImmortalLottery", "抽奖");
        put(mappings, "ImmortalShop", "商城");
        put(mappings, "immortalRecharge", "商城");
        put(mappings, "ImmortalReward", "奖励");
        put(mappings, "ImmortalFunctionUnlock", "功能解锁");
        List<Map.Entry<String, String>> result = new ArrayList<>(mappings.entrySet());
        result.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
        return List.copyOf(result);
    }

    private static void put(Map<String, String> mappings, String alias, String canonical) {
        mappings.put(alias, canonical);
    }
}
