package com.guanghe.fs.file.handler;

import com.guanghe.fs.file.cache.TransferTaskCacheManager;
import com.guanghe.fs.file.domain.FileTransferTask;
import com.guanghe.fs.file.enums.DownloadErrorCode;
import com.guanghe.fs.file.mapper.FileTransferTaskMapper;
import com.guanghe.fs.file.enums.TransferTaskStatus;
import com.guanghe.fs.file.service.TransferSseService;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.exception.StorageOperationException;
import com.guanghe.fs.framework.common.utils.ErrorMessageUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.time.LocalDateTime;

/**
 * 下载任务异常处理器
 * 
 * 负责处理下载任务过程中的各种异常情况，包括文件不存在、权限不足、存储读取失败等
 * 
 * @author guangheUlti
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DownloadTaskExceptionHandler {

    private final FileTransferTaskMapper fileTransferTaskMapper;
    private final TransferTaskCacheManager cacheManager;
    private final TransferSseService transferSseService;

    /**
     * 处理下载任务失败
     * 
     * 根据异常类型自动识别错误代码，更新任务状态为 failed，并推送 error 事件
     *
     * @param taskId   任务ID
     * @param errorMsg 错误信息
     * @param e        异常对象
     */
    public void handleDownloadTaskFailed(String taskId, String errorMsg, Exception e) {
        try {
            log.error("下载任务失败: taskId={}, error={}", taskId, errorMsg, e);

            // 根据异常类型确定错误代码
            DownloadErrorCode errorCode = determineErrorCode(e);

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
                transferSseService.sendErrorEvent(task.getUserId(), taskId, 
                    errorCode.getCode(), errorCode.getMessage() + ": " + userFriendlyMsg);
                
                log.info("已推送下载失败事件: taskId={}, errorCode={}", taskId, errorCode.getCode());
            }

            // 更新缓存状态
            cacheManager.updateTaskStatus(taskId, TransferTaskStatus.failed);

            // 延长缓存过期时间（保留1小时供查询）
            cacheManager.extendTaskExpire(taskId, 1);

        } catch (Exception ex) {
            log.error("处理下载任务失败时发生异常: taskId={}", taskId, ex);
        }
    }

    /**
     * 处理文件不存在异常
     *
     * @param taskId 任务ID
     * @param fileId 文件ID
     */
    public void handleFileNotFound(String taskId, String fileId) {
        String errorMsg = String.format("文件不存在: fileId=%s", fileId);
        log.error("下载失败 - {}", errorMsg);
        
        handleDownloadTaskFailed(taskId, errorMsg, 
            new BusinessException(DownloadErrorCode.FILE_NOT_FOUND.getMessage()));
    }

    /**
     * 处理权限不足异常
     *
     * @param taskId 任务ID
     * @param userId 用户ID
     * @param fileId 文件ID
     */
    public void handlePermissionDenied(String taskId, String userId, String fileId) {
        String errorMsg = String.format("权限不足: userId=%s, fileId=%s", userId, fileId);
        log.error("下载失败 - {}", errorMsg);
        
        handleDownloadTaskFailed(taskId, errorMsg, 
            new BusinessException(DownloadErrorCode.PERMISSION_DENIED.getMessage()));
    }

    /**
     * 处理存储读取失败异常
     *
     * @param taskId    任务ID
     * @param objectKey 对象键
     * @param e         异常对象
     */
    public void handleStorageReadFailed(String taskId, String objectKey, Exception e) {
        String errorMsg = String.format("存储读取失败: objectKey=%s, error=%s", 
            objectKey, e.getMessage());
        log.error("下载失败 - {}", errorMsg, e);
        
        handleDownloadTaskFailed(taskId, errorMsg, 
            new StorageOperationException(DownloadErrorCode.STORAGE_READ_FAILED.getMessage(), e));
    }

    /**
     * 处理分片下载失败（不改变任务状态，只记录错误）
     * 
     * 用于单个分片下载失败的情况，允许前端重试该分片
     *
     * @param taskId     任务ID
     * @param chunkIndex 分片索引
     * @param errorMsg   错误信息
     * @param e          异常对象
     */
    public void handleChunkDownloadFailed(String taskId, Integer chunkIndex, String errorMsg, Exception e) {
        log.error("分片下载失败: taskId={}, chunkIndex={}, error={}", taskId, chunkIndex, errorMsg, e);

        // 根据异常类型确定错误代码
        DownloadErrorCode errorCode = determineErrorCode(e);

        // 获取任务信息以获取userId
        FileTransferTask task = fileTransferTaskMapper.selectOneByQuery(
                com.mybatisflex.core.query.QueryWrapper.create()
                        .where(FileTransferTask::getTaskId).eq(taskId)
        );
        
        if (task != null) {
            // 推送错误消息通过SSE（使用用户友好的错误信息）
            String userFriendlyMsg = ErrorMessageUtils.extractUserFriendlyMessage(errorMsg);
            transferSseService.sendErrorEvent(task.getUserId(), taskId, errorCode.getCode(), 
                String.format("分片 %d 下载失败: %s", chunkIndex, userFriendlyMsg));
        }
    }

    /**
     * 根据异常类型确定错误代码
     *
     * @param e 异常对象
     * @return 错误代码
     */
    private DownloadErrorCode determineErrorCode(Exception e) {
        if (e == null) {
            return DownloadErrorCode.STORAGE_READ_FAILED;
        }

        // 根据异常类型判断（不依赖消息文本）
        if (e instanceof FileNotFoundException) {
            return DownloadErrorCode.FILE_NOT_FOUND;
        }
        
        if (e instanceof StorageOperationException) {
            return DownloadErrorCode.STORAGE_READ_FAILED;
        }
        
        if (e instanceof BusinessException) {
            // 对于BusinessException，直接使用通用错误码
            // 具体的错误信息已经在异常消息中了
            return DownloadErrorCode.STORAGE_READ_FAILED;
        }

        // 默认返回存储读取失败
        return DownloadErrorCode.STORAGE_READ_FAILED;
    }

    /**
     * 截断错误信息（防止过长）
     *
     * @param errorMsg 错误信息
     * @return 截断后的错误信息
     */
    private String truncateErrorMsg(String errorMsg) {
        if (errorMsg == null) {
            return "未知错误";
        }
        return errorMsg.length() > 500 ? errorMsg.substring(0, 500) + "..." : errorMsg;
    }
}
