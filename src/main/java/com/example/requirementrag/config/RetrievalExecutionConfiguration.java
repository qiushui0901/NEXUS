package com.example.requirementrag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 检索执行配置：为相互独立的检索分支提供有界线程池。
 */
@Configuration
public class RetrievalExecutionConfiguration {
    /**
     * 注册名为 retrievalExecutor 的有界线程池：固定线程数、有界等待队列，
     * 队列满时由调用线程执行任务（CallerRunsPolicy）形成背压。
     */
    @Bean(name = "retrievalExecutor", destroyMethod = "shutdown")
    ExecutorService retrievalExecutor(RagProperties properties) {
        int threads = properties.retrieval().resolvedParallelism();
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "nexus-retrieval-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(threads * 16),
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
