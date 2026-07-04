package com.rag.notebook.rag;

import com.rag.notebook.knowledge.entity.ChromaCleanupTask;
import com.rag.notebook.knowledge.repository.ChromaCleanupTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ChromaDB 清理任务定时调度器
 * 定时重试删除 ChromaDB 中的残留数据
 */
@Slf4j
@Component
public class ChromaCleanupScheduler {

    private final ChromaCleanupTaskRepository cleanupTaskRepository;
    private final VectorStoreService vectorStoreService;

    public ChromaCleanupScheduler(ChromaCleanupTaskRepository cleanupTaskRepository,
                                  VectorStoreService vectorStoreService) {
        this.cleanupTaskRepository = cleanupTaskRepository;
        this.vectorStoreService = vectorStoreService;
    }

    /**
     * 每5分钟执行一次清理任务
     * 重试删除 ChromaDB 中的残留数据
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void cleanupFailedChromaDeletions() {
        log.info("开始执行Chroma清理任务...");
        List<ChromaCleanupTask> tasks = cleanupTaskRepository.findByStatus("pending");

        if (tasks.isEmpty()) {
            log.debug("没有待清理的Chroma任务");
            return;
        }

        log.info("发现 {} 个待清理的Chroma任务", tasks.size());

        int successCount = 0;
        int failCount = 0;

        for (ChromaCleanupTask task : tasks) {
            try {
                boolean deleted = false;

                // 根据任务类型选择不同的删除方法
                if ("note".equals(task.getTaskType())) {
                    // 笔记类型：直接使用 noteStore 删除
                    deleted = deleteNoteFromChroma(task.getDocId());
                } else {
                    // 知识库类型：使用 REST API 删除
                    deleted = vectorStoreService.deleteFromChromaByDocId(task.getDocId());
                }

                if (deleted) {
                    // 成功则删除任务记录
                    cleanupTaskRepository.delete(task);
                    successCount++;
                    log.info("Chroma清理任务成功: docId={}, type={}", task.getDocId(), task.getTaskType());
                } else {
                    // 失败则更新重试次数
                    task.setRetryCount(task.getRetryCount() + 1);
                    if (task.getRetryCount() >= task.getMaxRetry()) {
                        task.setStatus("failed");
                        log.warn("Chroma清理任务超过最大重试次数，标记为failed: docId={}, retryCount={}",
                                task.getDocId(), task.getRetryCount());
                    }
                    cleanupTaskRepository.save(task);
                    failCount++;
                }
            } catch (Exception e) {
                log.error("Chroma清理任务异常: docId={}, error={}", task.getDocId(), e.getMessage());
                task.setRetryCount(task.getRetryCount() + 1);
                if (task.getRetryCount() >= task.getMaxRetry()) {
                    task.setStatus("failed");
                }
                cleanupTaskRepository.save(task);
                failCount++;
            }
        }

        log.info("Chroma清理任务完成: 成功={}, 失败={}", successCount, failCount);
    }

    /**
     * 从笔记向量库删除数据
     */
    private boolean deleteNoteFromChroma(String noteId) {
        try {
            vectorStoreService.deleteNoteFromStore(noteId);
            return true;
        } catch (Exception e) {
            log.error("笔记向量删除失败: noteId={}, error={}", noteId, e.getMessage());
            return false;
        }
    }

    /**
     * 每天凌晨3点清理超过7天的 failed 任务
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupOldFailedTasks() {
        log.info("开始清理过期的Chroma清理任务...");
        LocalDateTime before = LocalDateTime.now().minusDays(7);
        int deletedCount = cleanupTaskRepository.deleteByStatusAndCreatedAtBefore("failed", before);
        log.info("已清理 {} 条过期的failed任务", deletedCount);
    }
}
