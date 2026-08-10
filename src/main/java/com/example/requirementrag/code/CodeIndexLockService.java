package com.example.requirementrag.code;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 代码索引项目级协调锁：全量索引与增量索引共用同一把锁，
 * 杜绝并发发布乱序（旧任务覆盖新 live）与增量/全量交错写入。
 */
@Component
public class CodeIndexLockService {

    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    /** 在项目级锁内执行索引任务；同项目任务串行执行。 */
    public <T> T execute(String projectId, Supplier<T> task) {
        Object lock = locks.computeIfAbsent(projectId, key -> new Object());
        synchronized (lock) {
            return task.get();
        }
    }
}
