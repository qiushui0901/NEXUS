package com.example.requirementrag.evolution.scheduling;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.evolution.experience.RetrievalExperienceRecorder;
import com.example.requirementrag.evolution.mining.RetrievalFailureMiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每日失败挖掘调度：仅在 evolution 启用时执行。 */
@Component
public class EvolutionScheduler {

    private static final Logger log = LoggerFactory.getLogger(EvolutionScheduler.class);

    private final RagProperties properties;
    private final RetrievalExperienceRecorder experienceRecorder;
    private final RetrievalFailureMiner failureMiner;

    public EvolutionScheduler(RagProperties properties, RetrievalExperienceRecorder experienceRecorder,
                              RetrievalFailureMiner failureMiner) {
        this.properties = properties;
        this.experienceRecorder = experienceRecorder;
        this.failureMiner = failureMiner;
    }

    @Scheduled(cron = "${app.rag.evolution.scheduler-cron:0 0 3 * * *}")
    public void runDailyMining() {
        if (!properties.evolution().enabled()) {
            return;
        }
        log.info("Evolution daily mining started");
        failureMiner.mine(experienceRecorder.readAll());
        experienceRecorder.cleanExpired();
    }
}
