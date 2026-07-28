package com.example.requirementrag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded executor for independent retrieval branches. */
@Configuration
public class RetrievalExecutionConfiguration {
    @Bean(name = "retrievalExecutor", destroyMethod = "shutdown")
    ExecutorService retrievalExecutor(RagProperties properties) {
        int threads = properties.retrieval().resolvedParallelism();
        return new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(threads * 16),
                Thread.ofPlatform().name("nexus-retrieval-", 0).factory(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
