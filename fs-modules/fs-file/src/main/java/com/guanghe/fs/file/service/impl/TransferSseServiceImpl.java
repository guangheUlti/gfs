package com.guanghe.fs.file.service.impl;

import com.guanghe.fs.file.cache.TransferTaskCacheManager;
import com.guanghe.fs.file.service.TransferSseService;
import com.guanghe.fs.framework.sse.SseConnectionManager;
import com.guanghe.fs.framework.sse.SseEventType;
import com.guanghe.fs.framework.sse.dto.CompleteEventDTO;
import com.guanghe.fs.framework.sse.dto.ErrorEventDTO;
import com.guanghe.fs.framework.sse.dto.ProgressEventDTO;
import com.guanghe.fs.framework.sse.dto.StatusEventDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 传输任务SSE推送服务实现
 * 
 * @author guanghe
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferSseServiceImpl implements TransferSseService {
    
    private final SseConnectionManager sseConnectionManager;
    private final TransferTaskCacheManager transferTaskCacheManager;
    
    @Override
    public void sendProgressEvent(String userId, String taskId, Long uploadedBytes, Long totalBytes,
                                  Integer uploadedChunks, Integer totalChunks) {
        if (!sseConnectionManager.hasConnection(userId)) {
            log.warn("用户无SSE连接，跳过进度推送: userId={}, taskId={}", userId, taskId);
            return;
        }
        
        ProgressEventDTO data = ProgressEventDTO.builder()
                .taskId(taskId)
                .uploadedBytes(uploadedBytes)
                .totalBytes(totalBytes)
                .uploadedChunks(uploadedChunks)
                .totalChunks(totalChunks)
                .build();
        
        try {
            sseConnectionManager.sendEvent(userId, SseEventType.PROGRESS.getValue(), data);
            log.info("推送进度事件: userId={}, taskId={}, progress={}/{}", 
                     userId, taskId, uploadedChunks, totalChunks);
        } catch (Exception e) {
            log.error("推送进度事件失败: userId={}, taskId={}", userId, taskId, e);
        }
    }
    
    @Override
    public void sendStatusEvent(String userId, String taskId, String status, String message) {
        if (!sseConnectionManager.hasConnection(userId)) {
            log.warn("用户无SSE连接，跳过状态推送: userId={}, taskId={}, status={}", userId, taskId, status);
            return;
        }
        
        StatusEventDTO data = StatusEventDTO.builder()
                .taskId(taskId)
                .status(status)
                .message(message)
                .build();
        
        try {
            sseConnectionManager.sendEvent(userId, SseEventType.STATUS.getValue(), data);
            log.info("推送状态事件: userId={}, taskId={}, status={}, message={}", 
                    userId, taskId, status, message);
        } catch (Exception e) {
            log.error("推送状态事件失败: userId={}, taskId={}", userId, taskId, e);
        }
    }
    
    @Override
    public void sendCompleteEvent(String userId, String taskId, String fileId, String fileName, Long fileSize) {
        CompleteEventDTO data = CompleteEventDTO.builder()
                .taskId(taskId)
                .fileId(fileId)
                .fileName(fileName)
                .fileSize(fileSize)
                .build();
        
        // 先缓存完成事件数据，供前端轮询使用（7天过期）
        try {
            transferTaskCacheManager.cacheCompleteEvent(taskId, data);
            log.debug("缓存完成事件: taskId={}, fileId={}", taskId, fileId);
        } catch (Exception e) {
            log.error("缓存完成事件失败: taskId={}", taskId, e);
        }
        
        // 尝试通过 SSE 推送
        if (!sseConnectionManager.hasConnection(userId)) {
            log.warn("⚠️ 用户无SSE连接，完成事件已缓存，等待前端轮询: userId={}, taskId={}", userId, taskId);
            return;
        }
        
        try {
            sseConnectionManager.sendEvent(userId, SseEventType.COMPLETE.getValue(), data);
            log.info("推送完成事件成功: userId={}, taskId={}, fileId={}", userId, taskId, fileId);
        } catch (Exception e) {
            log.error("推送完成事件失败，已缓存供轮询: userId={}, taskId={}", userId, taskId, e);
        }
    }
    
    @Override
    public void sendErrorEvent(String userId, String taskId, String code, String message) {
        if (!sseConnectionManager.hasConnection(userId)) {
            log.warn("用户无SSE连接，跳过错误推送: userId={}, taskId={}", userId, taskId);
            return;
        }
        
        ErrorEventDTO data = ErrorEventDTO.builder()
                .taskId(taskId)
                .code(code)
                .message(message)
                .build();
        
        try {
            sseConnectionManager.sendEvent(userId, SseEventType.ERROR.getValue(), data);
            log.error("推送错误事件: userId={}, taskId={}, code={}, message={}", 
                     userId, taskId, code, message);
        } catch (Exception e) {
            log.error("推送错误事件失败: userId={}, taskId={}", userId, taskId, e);
        }
    }
}
