package com.example.requirementrag.service;

import com.example.requirementrag.model.DoubtBatch;
import com.example.requirementrag.model.RequirementDoubt;
import com.example.requirementrag.model.ReviewRequest;
import com.example.requirementrag.observability.RagObservability;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 评审门面，合并当前版本与历史版本存疑生成结果。
 */
@Service
public class ReviewFacadeService {

    private final DoubtReviewService doubtReviewService;
    private final RagObservability observability;

    /**
     * 注入基于向量库检索的评审服务。
     */
    public ReviewFacadeService(DoubtReviewService doubtReviewService, RagObservability observability) {
        this.doubtReviewService = doubtReviewService;
        this.observability = observability;
    }

    /**
     * 执行完整评审：当前版本新问题 + 历史版本遗留存疑。
     * 当前版本必须基于 Qdrant 向量库检索上下文生成。
     */
    public DoubtBatch review(ReviewRequest request) {
        DoubtBatch current = doubtReviewService.reviewCurrentVersion(request);
        DoubtBatch prior = doubtReviewService.reviewPriorVersion(request);

        List<RequirementDoubt> merged = new ArrayList<>(current.doubts().size() + prior.doubts().size());
        merged.addAll(current.doubts());
        merged.addAll(prior.doubts());
        observability.event("review_completed");
        return new DoubtBatch(merged);
    }
}
