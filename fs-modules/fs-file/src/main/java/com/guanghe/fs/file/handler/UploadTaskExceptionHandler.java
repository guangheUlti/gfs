package com.guanghe.fs.file.handler;

import com.guanghe.fs.file.cache.TransferTaskCacheManager;
import com.guanghe.fs.file.domain.FileTransferTask;
import com.guanghe.fs.file.mapper.FileTransferTaskMapper;
import com.guanghe.fs.file.enums.TransferTaskStatus;
import com.guanghe.fs.file.service.TransferSseService;
import com.guanghe.fs.framework.common.utils.ErrorMessageUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 上传任务异常处理器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadTaskExceptionHandler {

    private final FileTransferTaskMapper fileTransferTaskMapper;
    private final TransferTaskCacheManager cacheManager;
    private final TransferSseService transferSseService;

    /**
     * 处理任务失败
     *
     * @param taskId   任务ID
     * @param errorMsg 错误信息
     * @param e        异常对象
     */
    public void handleTaskFailed(String taskId, String errorMsg, Exception e) {
        try {
            log.error("任务失败: taskId={}, error={}", taskId, errorMsg, e);

            // 更新数据库状态
            FileTransferTask task = fileTransferTaskMapper.selectOneByQuery(
                    com.mybatisflex.core.query.QueryWrapper.create()
                            .where(FileTransferTask::getTaskId).eq(taskId)
            );

            if (task != null) {
                task.setStatus(TransferTaskStatus.failed);
                task.setErrorMsg(truncateErrorMsg(errorMsg));
                task.setUpdatedAt(LocalDateTime.now());
                fileTransferTaskMapper.update(task);
                
                // 推送失败消息通过SSE（使用用户友好的错误信息）
                String userFriendlyMsg = ErrorMessageUtils.extractUserFriendlyMessage(errorMsg);
                transferSseService.sendErrorEvent(task.getUserId(), taskId, "TASK_FAILED", userFriendlyMsg);
            }

            // 更新缓存状态
            cacheManager.updateTaskStatus(taskId, TransferTaskStatus.failed);

            // 延长缓存过期时间（保留1小时供查询）
            cacheManager.extendTaskExpire(taskId, 1);

        } catch (Exception ex) {
            log.error("处理任务失败时发生异常: taskId={}", taskId, ex);
        }
    }

    /**
     * 处理分片上传失败（不改变任务状态，只记录错误）
     *
     * @param taskId     任务ID
     * @param chunkIndex 分片索引
     * @param errorMsg   错误信息
     * @param e          异常对象
     */
    public void handleChunkUploadFailed(String taskId, Integer chunkIndex, String errorMsg, Exception e) {
        log.error("分片上传失败: taskId={}, chunkIndex={}, error={}", taskId, chunkIndex, errorMsg, e);

        // 获取任务信息以获取userId
        FileTransferTask task = fileTransferTaskMapper.selectOneByQuery(
                com.mybatisflex.core.query.QueryWrapper.create()
                        .where(FileTransferTask::getTaskId).eq(taskId)
        );
        
        if (task != null) {
            // 推送错误消息通过SSE（使用用户友好的错误信息）
            String userFriendlyMsg = ErrorMessageUtils.extractUserFriendlyMessage(errorMsg);
            transferSseService.sendErrorEvent(task.getUserId(), taskId, "CHUNK_UPLOAD_FAILED", 
                String.format("分片 %d 上传失败: %s", chunkIndex, userFriendlyMsg));
        }
    }

    /**
     * 截断错误信息（防止过长）
     */
    private String truncateErrorMsg(String errorMsg) {
        if (errorMsg == null) {
            return "未知错误";
        }
        return errorMsg.length() > 500 ? errorMsg.substring(0, 500) + "..." : errorMsg;
    }
}
