package com.example.requirementrag.wiki.module;

import com.example.requirementrag.wiki.WikiModels.Claim;
import com.example.requirementrag.wiki.WikiModels.ClaimSupport;
import com.example.requirementrag.wiki.WikiModels.Evidence;
import com.example.requirementrag.wiki.WikiModels.PageSource;
import com.example.requirementrag.wiki.WikiModels.PageType;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Module 页面发布质量门：阻止无证据、无效引用或跨 commit 证据的页面进入发布。 */
@Component
public class ModuleClaimQualityGate {

    /** 模块证据 ID 的最后一段是页面 evidence 列表下标，namespace 表示事实类型。 */
    private static final Pattern EVIDENCE_INDEX = Pattern.compile(
            "^[a-z][a-z0-9-]*:[A-Za-z0-9._-]+:(\\d+)$");
    private static final Pattern LEGACY_EVIDENCE_INDEX = Pattern.compile("^(requirement|code):(\\d+)$");

    /**
     * 校验页面 Claims 与证据引用；MODULE 页面必须至少有一条代码证据，
     * FULL Claim 必须引用页面内存在的证据。
     *
     * @throws IllegalArgumentException 门禁失败时
     */
    public void validate(String projectId, String version, List<PageSource> pages) {
        Set<String> usedIds = new HashSet<>();
        for (PageSource page : pages) {
            if (page.pageType() != PageType.MODULE) continue;
            if (page.evidence().isEmpty()) {
                throw new IllegalArgumentException("MODULE 页面没有代码证据，禁止发布: " + page.featureId());
            }
            for (Claim claim : list(page.claims())) {
                if (claim.support() == ClaimSupport.FULL && list(claim.evidenceIds()).isEmpty()) {
                    throw new IllegalArgumentException("FULL Claim 缺少证据引用: " + claim.claimId());
                }
                if (claim.support() != ClaimSupport.UNSUPPORTED && list(claim.evidenceIds()).isEmpty()) {
                    throw new IllegalArgumentException("受支持 Claim 缺少证据引用: " + claim.claimId());
                }
                for (String evidenceId : list(claim.evidenceIds())) {
                    Integer index = evidenceIndex(evidenceId);
                    if (index == null || index >= page.evidence().size()) {
                        throw new IllegalArgumentException("Claim 引用了不存在的证据: " + claim.claimId()
                                + " -> " + evidenceId);
                    }
                    Evidence evidence = page.evidence().get(index);
                    if (!projectId.equals(evidence.source())) {
                        throw new IllegalArgumentException("Claim 证据跨项目: " + claim.claimId() + " -> "
                                + evidenceId);
                    }
                    if (!version.equals(evidence.version())) {
                        throw new IllegalArgumentException("Claim 证据跨版本: " + claim.claimId() + " -> "
                                + evidenceId);
                    }
                }
            }
        }
    }

    /** 解析证据 ID 为页面 evidence 下标；格式不识别时返回 null。 */
    static Integer evidenceIndex(String evidenceId) {
        if (evidenceId == null) return null;
        Matcher module = EVIDENCE_INDEX.matcher(evidenceId.trim());
        if (module.matches()) return Integer.valueOf(module.group(1));
        Matcher legacy = LEGACY_EVIDENCE_INDEX.matcher(evidenceId.trim());
        return legacy.matches() ? Integer.valueOf(legacy.group(2)) : null;
    }

    /** 不校验任何内容的门禁实例（旧构造路径的兼容占位）。 */
    public static ModuleClaimQualityGate lenient() {
        return new ModuleClaimQualityGate() {
            @Override
            public void validate(String projectId, String version, List<PageSource> pages) {
            }
        };
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
