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
        int threads = resolveParallelism(properties);
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

    private int resolveParallelism(RagProperties properties) {
        RagProperties.Retrieval retrieval = properties.retrieval();
        if (retrieval == null) return 6;
        RagProperties.Document document = retrieval.document();
        RagProperties.CodeRetrieval code = retrieval.code();
        int docThreads = document != null ? document.resolvedParallelism() : retrieval.resolvedParallelism();
        int codeThreads = code != null ? code.resolvedParallelism() : retrieval.resolvedParallelism();
        // P0 单池兼容：取文档/代码各自 parallelism 的最大值，同时兼容旧 flat 配置
        int legacy = retrieval.resolvedParallelism();
        return Math.max(legacy, Math.max(docThreads, codeThreads));
    }
}
