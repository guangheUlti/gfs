package com.guanghe.fs.file.schedule;

import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.guanghe.fs.file.cache.TransferTaskCacheManager;
import com.guanghe.fs.file.domain.FileTransferTask;
import com.guanghe.fs.file.enums.TransferTaskStatus;
import com.guanghe.fs.file.enums.TransferTaskType;
import com.guanghe.fs.file.service.FileTransferTaskService;
import com.guanghe.fs.storage.facade.StorageServiceFacade;
import com.guanghe.fs.storage.plugin.core.IStorageOperationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.guanghe.fs.file.domain.table.FileTransferTaskTableDef.FILE_TRANSFER_TASK;

/**
 * 传输任务清理定时任务
 * 
 * 定期清理过期的传输任务记录和缓存
 * 
 * @author guangheUlti
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferTaskCleanupScheduler {

    /** 孤儿分片目录判定阈值：最后修改超过该时长且已无对应活动任务，才认为是残留 */
    private static final Duration ORPHAN_TEMP_DIR_MAX_AGE = Duration.ofHours(1);

    private final FileTransferTaskService fileTransferTaskService;
    private final TransferTaskCacheManager cacheManager;
    private final StorageServiceFacade storageServiceFacade;

    /**
     * 清理过期的传输任务记录
     * 
     * 每天凌晨2点执行，清理已完成/已取消/失败的任务（7天前）以及长时间未活动的任务（3天前）
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupExpiredTasks() {
        log.info("开始清理过期的传输任务记录");
        
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime sevenDaysAgo = now.minusDays(7);
            LocalDateTime threeDaysAgo = now.minusDays(3);
            
            // 清理已完成或已取消的任务（7天前）
            QueryWrapper completedOrCanceledQuery = new QueryWrapper();
            completedOrCanceledQuery.where(
                FILE_TRANSFER_TASK.STATUS.in(TransferTaskStatus.completed, TransferTaskStatus.canceled)
                    .and(FILE_TRANSFER_TASK.UPDATED_AT.lt(sevenDaysAgo))
            );
            List<FileTransferTask> completedOrCanceledTasks = fileTransferTaskService.list(completedOrCanceledQuery);
            
            if (!completedOrCanceledTasks.isEmpty()) {
                List<String> taskIds = completedOrCanceledTasks.stream()
                        .map(FileTransferTask::getTaskId)
                        .collect(Collectors.toList());
                
                cleanupResidualTempDirs(completedOrCanceledTasks);
                fileTransferTaskService.removeByIds(idsOf(completedOrCanceledTasks));
                cacheManager.cleanTasks(taskIds);
                
                log.info("清理已完成或已取消的任务: count={}", completedOrCanceledTasks.size());
            }
            
            // 清理失败的任务（7天前）
            QueryWrapper failedQuery = new QueryWrapper();
            failedQuery.where(
                FILE_TRANSFER_TASK.STATUS.eq(TransferTaskStatus.failed)
                    .and(FILE_TRANSFER_TASK.UPDATED_AT.lt(sevenDaysAgo))
            );
            List<FileTransferTask> failedTasks = fileTransferTaskService.list(failedQuery);
            
            if (!failedTasks.isEmpty()) {
                List<String> taskIds = failedTasks.stream()
                        .map(FileTransferTask::getTaskId)
                        .collect(Collectors.toList());
                
                cleanupResidualTempDirs(failedTasks);
                fileTransferTaskService.removeByIds(idsOf(failedTasks));
                cacheManager.cleanTasks(taskIds);
                
                log.info("清理失败的任务: count={}", failedTasks.size());
            }
            
            // 清理长时间未活动的未完成任务（3天前）
            QueryWrapper inactiveQuery = new QueryWrapper();
            inactiveQuery.where(
                FILE_TRANSFER_TASK.STATUS.in(
                        TransferTaskStatus.initialized,
                        TransferTaskStatus.checking,
                        TransferTaskStatus.uploading,
                        TransferTaskStatus.paused,
                        TransferTaskStatus.merging)
                    .and(FILE_TRANSFER_TASK.UPDATED_AT.lt(threeDaysAgo))
            );
            List<FileTransferTask> inactiveTasks = fileTransferTaskService.list(inactiveQuery);
            
            if (!inactiveTasks.isEmpty()) {
                abortIncompleteMultipartUploads(inactiveTasks);
                List<String> taskIds = inactiveTasks.stream()
                        .map(FileTransferTask::getTaskId)
                        .collect(Collectors.toList());
                
                fileTransferTaskService.removeByIds(idsOf(inactiveTasks));
                cacheManager.cleanTasks(taskIds);
                
                log.info("清理长时间未活动的任务: count={}", inactiveTasks.size());
            }
            
            sweepOrphanTempDirs();

            int totalCleaned = completedOrCanceledTasks.size() + failedTasks.size() + inactiveTasks.size();
            log.info("传输任务清理完成: totalCleaned={}", totalCleaned);
            
        } catch (Exception e) {
            log.error("清理过期传输任务失败", e);
        }
    }

    /** removeByIds 要的是主键，传实体进去会被当成参数序列化后拼进 SQL */
    private static List<Long> idsOf(List<FileTransferTask> tasks) {
        return tasks.stream().map(FileTransferTask::getId).collect(Collectors.toList());
    }

    private void abortIncompleteMultipartUploads(List<FileTransferTask> tasks) {
        for (FileTransferTask task : tasks) {
            if (!TransferTaskType.upload.equals(task.getTaskType())
                    || StrUtil.isBlank(task.getUploadId())
                    || StrUtil.isBlank(task.getObjectKey())) {
                continue;
            }
            try {
                IStorageOperationService storageService = storageServiceFacade
                        .getStorageService(task.getStoragePlatformSettingId());
                storageService.abortMultipartUpload(task.getObjectKey(), task.getUploadId());
            } catch (Exception e) {
                log.warn("清理过期分片上传失败: taskId={}, uploadId={}",
                        task.getTaskId(), task.getUploadId(), e);
            }
        }
    }

    /**
     * 任务行即将删除，先删掉 uploadId 对应的本地分片目录。
     * <p>
     * 只遍历常驻实例（已缓存 + 内置系统存储），不通过 configId 取实例：
     * 后者会在删除历史任务时唤醒休眠的远程存储。uploadId 是 UUID，
     * 不属于本实例的目录一查不存在即返回，逐个尝试代价可忽略。
     */
    private void cleanupResidualTempDirs(List<FileTransferTask> tasks) {
        List<String> uploadIds = tasks.stream()
                .filter(task -> TransferTaskType.upload.equals(task.getTaskType()))
                .map(FileTransferTask::getUploadId)
                .filter(StrUtil::isNotBlank)
                .toList();
        if (uploadIds.isEmpty()) {
            return;
        }
        for (IStorageOperationService service : storageServiceFacade.getResidentStorageServices()) {
            for (String uploadId : uploadIds) {
                try {
                    service.cleanupResidualTempDir(uploadId);
                } catch (Exception e) {
                    log.warn("清理任务残留分片目录失败: uploadId={}", uploadId, e);
                }
            }
        }
    }

    /**
     * 兜底扫描：删除已无对应活动任务的分片临时目录。
     * <p>
     * 合并与中止处的删除万一失败（文件句柄未释放等），残留目录不会再被任何业务流程触达；
     * 任务行删除后 uploadId 也随之消失，这些目录即成为孤儿，由本方法回收。
     */
    private void sweepOrphanTempDirs() {
        QueryWrapper liveQuery = new QueryWrapper();
        liveQuery.where(FILE_TRANSFER_TASK.UPLOAD_ID.isNotNull());

        Set<String> liveUploadIds = fileTransferTaskService.list(liveQuery).stream()
                .filter(task -> !isTerminal(task.getStatus()))
                .map(FileTransferTask::getUploadId)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());

        int deleted = 0;
        for (IStorageOperationService service : storageServiceFacade.getResidentStorageServices()) {
            try {
                deleted += service.cleanupOrphanTempDirs(liveUploadIds, ORPHAN_TEMP_DIR_MAX_AGE);
            } catch (Exception e) {
                log.warn("兜底清理分片临时目录失败: service={}", service.getClass().getSimpleName(), e);
            }
        }
        if (deleted > 0) {
            log.info("兜底清理孤儿分片目录: count={}", deleted);
        }
    }

    /** 终态任务不会再使用 uploadId，其临时目录即便仍存在也不再被任何业务流程触达 */
    private static boolean isTerminal(TransferTaskStatus status) {
        return status == TransferTaskStatus.completed
                || status == TransferTaskStatus.canceled
                || status == TransferTaskStatus.failed;
    }
}
