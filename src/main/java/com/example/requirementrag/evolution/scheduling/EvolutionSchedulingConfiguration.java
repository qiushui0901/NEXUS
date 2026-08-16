package com.example.requirementrag.evolution.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 仅在 evolution 启用时开启调度，避免默认环境引入定时任务。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.rag.evolution.enabled", havingValue = "true")
public class EvolutionSchedulingConfiguration {
}
