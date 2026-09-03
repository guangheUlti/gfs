package com.guanghe.fs.file.service;

/**
 * 传输任务SSE推送服务接口
 * 负责封装各类SSE事件的推送逻辑
 * 
 * @author guanghe
 */
public interface TransferSseService {
    
    /**
     * 推送进度事件
     * 
     * @param userId 用户ID
     * @param taskId 任务ID
     * @param uploadedBytes 已上传字节数
     * @param totalBytes 总字节数
     * @param uploadedChunks 已上传分片数
     * @param totalChunks 总分片数
     */
    void sendProgressEvent(String userId, String taskId, Long uploadedBytes, Long totalBytes, 
                          Integer uploadedChunks, Integer totalChunks);
    
    /**
     * 推送状态事件
     * 
     * @param userId 用户ID
     * @param taskId 任务ID
     * @param status 任务状态
     * @param message 状态消息
     */
    void sendStatusEvent(String userId, String taskId, String status, String message);
    
    /**
     * 推送完成事件
     * 
     * @param userId 用户ID
     * @param taskId 任务ID
     * @param fileId 文件ID
     * @param fileName 文件名
     * @param fileSize 文件大小
     */
    void sendCompleteEvent(String userId, String taskId, String fileId, String fileName, Long fileSize);
    
    /**
     * 推送错误事件
     * 
     * @param userId 用户ID
     * @param taskId 任务ID
     * @param code 错误代码
     * @param message 错误消息
     */
    void sendErrorEvent(String userId, String taskId, String code, String message);
}
