package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeQueryIntent;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 多源知识门禁：定义哪些状态/存疑可以进入检索结果。
 *
 * <p>核心规则：
 * <ul>
 *   <li>{@code REJECTED / STALE / OBSOLETE} 默认不返回；</li>
 *   <li>{@code OPEN / UNDER_DISCUSSION} 存疑只在用户明确询问 DOUBT 时返回，不能作为确认事实；</li>
 *   <li>{@code RESOLVED} 存疑可作为已解决记录返回，但仍标记为存疑类型。</li>
 * </ul>
 */
@Component
public class MultiSourceKnowledgeGate {

    /** 状态是否允许进入普通检索（默认排除已拒绝/过期/废弃）。 */
    public boolean isRetrievable(KnowledgeStatus status) {
        return status != null
                && status != KnowledgeStatus.REJECTED
                && status != KnowledgeStatus.STALE
                && status != KnowledgeStatus.OBSOLETE;
    }

    /** 存疑是否允许进入本次检索结果。 */
    public boolean includeDoubt(DoubtStatus doubtStatus, KnowledgeQueryIntent intent) {
        if (doubtStatus == DoubtStatus.RESOLVED) return true;
        if (doubtStatus == DoubtStatus.REJECTED || doubtStatus == DoubtStatus.OBSOLETE) return false;
        // OPEN / UNDER_DISCUSSION 只在明确查询风险/存疑时返回。
        return intent == KnowledgeQueryIntent.DOUBT;
    }

    /** 过滤存疑列表。 */
    public List<DoubtClaim> filterDoubts(List<DoubtClaim> doubts, KnowledgeQueryIntent intent) {
        if (doubts == null) return List.of();
        return doubts.stream()
                .filter(doubt -> includeDoubt(doubt.status(), intent))
                .toList();
    }
}