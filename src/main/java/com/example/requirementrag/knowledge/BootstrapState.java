package com.example.requirementrag.knowledge;

import com.example.requirementrag.model.BootstrapStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 知识库引导进度与状态的线程安全追踪器。
 */
@Component
public class BootstrapState {

    private final AtomicReference<String> state = new AtomicReference<>("IDLE");
    private final AtomicReference<String> phase = new AtomicReference<>("");
    private final AtomicInteger filesTotal = new AtomicInteger();
    private final AtomicInteger filesProcessed = new AtomicInteger();
    private final AtomicInteger chunks = new AtomicInteger();
    private final AtomicReference<String> currentFile = new AtomicReference<>("");
    private final AtomicReference<String> error = new AtomicReference<>();
    private final AtomicReference<Instant> startedAt = new AtomicReference<>();
    private final AtomicReference<Instant> completedAt = new AtomicReference<>();
    private final AtomicInteger zipFiles = new AtomicInteger();
    private final AtomicInteger xlsxRows = new AtomicInteger();
    private final Set<String> runningProjects = ConcurrentHashMap.newKeySet();

    /** 重置并标记引导开始。 */
    public void start() {
        state.set("RUNNING");
        phase.set("prepare");
        filesTotal.set(0);
        filesProcessed.set(0);
        chunks.set(0);
        currentFile.set("");
        error.set(null);
        startedAt.set(Instant.now());
        completedAt.set(null);
        zipFiles.set(0);
        xlsxRows.set(0);
    }

    /** 更新当前引导阶段名称。 */
    public void phase(String value) {
        phase.set(value);
    }

    /** 设置待处理文件总数。 */
    public void filesTotal(int value) {
        filesTotal.set(value);
    }

    /** 更新已处理文件数与当前文件名。 */
    public void fileProgress(int processed, String fileName) {
        filesProcessed.set(processed);
        currentFile.set(fileName);
    }

    /** 记录导入完成的分块总数。 */
    public void chunks(int value) {
        chunks.set(value);
    }

    /** 记录从 ZIP 导入的文件数。 */
    public void zipFiles(int value) {
        zipFiles.set(value);
    }

    /** 记录从 XLSX 读取的行数（当前仅用于统计）。 */
    public void xlsxRows(int value) {
        xlsxRows.set(value);
    }

    /** 标记引导成功完成。 */
    public void complete() {
        state.set("COMPLETED");
        phase.set("done");
        currentFile.set("");
        completedAt.set(Instant.now());
    }

    /** 标记引导失败并记录错误信息。 */
    public void fail(String message) {
        state.set("FAILED");
        error.set(message);
        completedAt.set(Instant.now());
    }

    /** 获取当前引导状态的快照。 */
    public BootstrapStatus status() {
        return new BootstrapStatus(
                state.get(),
                phase.get(),
                filesTotal.get(),
                filesProcessed.get(),
                chunks.get(),
                currentFile.get(),
                error.get(),
                startedAt.get(),
                completedAt.get());
    }

    /** 返回已导入的 ZIP 文件数。 */
    public int zipFiles() {
        return zipFiles.get();
    }

    /** 返回已读取的 XLSX 行数。 */
    public int xlsxRows() {
        return xlsxRows.get();
    }

    /** 判断引导是否正在运行（全局）。 */
    public boolean running() {
        return "RUNNING".equals(state.get());
    }

    /** 判断指定项目的引导是否正在运行。 */
    public boolean running(String projectId) {
        return projectId != null && runningProjects.contains(projectId);
    }

    /** 尝试获取项目级引导锁。成功返回 true，已在运行返回 false。 */
    public boolean tryStartProject(String projectId) {
        return projectId != null && runningProjects.add(projectId);
    }

    /** 释放项目级引导锁。 */
    public void finishProject(String projectId) {
        if (projectId != null) {
            runningProjects.remove(projectId);
        }
    }
}
