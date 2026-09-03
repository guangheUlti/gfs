package com.guanghe.fs.framework.sse;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE连接管理器接口
 * 负责管理用户的SSE连接和消息推送
 * 
 * @author guanghe
 */
public interface SseConnectionManager {
    
    /**
     * 创建SSE连接
     * 如果用户已有连接，将关闭旧连接并创建新连接
     * 
     * @param userId 用户ID
     * @return SseEmitter实例
     */
    SseEmitter createConnection(String userId);
    
    /**
     * 移除SSE连接
     * 
     * @param userId 用户ID
     */
    void removeConnection(String userId);
    
    /**
     * 向指定用户推送事件
     * 
     * @param userId 用户ID
     * @param eventType 事件类型
     * @param data 事件数据
     */
    void sendEvent(String userId, String eventType, Object data);
    
    /**
     * 检查用户是否有活跃连接
     * 
     * @param userId 用户ID
     * @return 是否有连接
     */
    boolean hasConnection(String userId);
}
