package com.guanghe.fs.framework.sse.impl;

import com.guanghe.fs.framework.sse.SseConnectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class SseConnectionManagerImpl implements SseConnectionManager {

    private static final long SSE_TIMEOUT = 60 * 60 * 1000L;
    private static final long HEARTBEAT_INTERVAL = 25 * 1000L;

    private final ConcurrentHashMap<String, SseEmitter> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(2);

    @Override
    public SseEmitter createConnection(String userId) {
        log.info("Creating SSE connection for user: {}", userId);

        // 清理旧连接
        cleanupOldConnection(userId);

        // 创建新连接
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        // 设置回调
        emitter.onCompletion(() -> {
            log.info("SSE connection completed for user: {}", userId);
            cleanup(userId);
        });

        emitter.onTimeout(() -> {
            log.warn("SSE connection timeout for user: {}", userId);
            cleanup(userId);
        });

        emitter.onError(throwable -> {
            log.warn("SSE connection error for user: {}, reason: {}",
                    userId, throwable.getMessage());
            cleanup(userId);
        });

        // 保存连接
        connections.put(userId, emitter);

        // 启动心跳
        startHeartbeat(userId, emitter);

        // 发送连接确认
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("{\"message\":\"SSE connection established\"}"));
        } catch (IOException e) {
            log.warn("Failed to send connection confirmation to user: {}", userId);
        }

        log.info("SSE connection created successfully for user: {}", userId);
        return emitter;
    }

    /**
     * 清理旧连接（不调用 complete，避免异常）
     */
    private void cleanupOldConnection(String userId) {
        SseEmitter oldEmitter = connections.remove(userId);
        if (oldEmitter != null) {
            log.info("Replacing existing connection for user: {}", userId);
            stopHeartbeat(userId);
            // 不调用 complete()，让旧连接自然断开
        }
    }

    /**
     * 统一的清理方法
     */
    private void cleanup(String userId) {
        stopHeartbeat(userId);
        connections.remove(userId);
    }

    /**
     * 启动心跳任务
     */
    private void startHeartbeat(String userId, SseEmitter emitter) {
        stopHeartbeat(userId);

        ScheduledFuture<?> task = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                // 检查连接是否还存在
                if (!connections.containsKey(userId)) {
                    log.debug("Connection no longer exists for user: {}, stopping heartbeat", userId);
                    stopHeartbeat(userId);
                    return;
                }

                // 发送心跳
                emitter.send(SseEmitter.event().comment("heartbeat"));
                log.debug("Heartbeat sent to user: {}", userId);

            } catch (IllegalStateException e) {
                // 连接已关闭（正常情况）
                log.debug("Connection already completed for user: {}, stopping heartbeat", userId);
                cleanup(userId);
            } catch (IOException e) {
                // 网络错误
                log.debug("Heartbeat failed for user: {}, connection closed", userId);
                cleanup(userId);
            } catch (Exception e) {
                // 其他异常
                log.error("Unexpected error in heartbeat for user: {}", userId, e);
                cleanup(userId);
            }
        }, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);

        heartbeatTasks.put(userId, task);
        log.debug("Heartbeat started for user: {}, interval: {}ms", userId, HEARTBEAT_INTERVAL);
    }

    /**
     * 停止心跳任务
     */
    private void stopHeartbeat(String userId) {
        ScheduledFuture<?> task = heartbeatTasks.remove(userId);
        if (task != null && !task.isCancelled()) {
            task.cancel(false);
            log.debug("Heartbeat stopped for user: {}", userId);
        }
    }

    @Override
    public void removeConnection(String userId) {
        stopHeartbeat(userId);
        SseEmitter emitter = connections.remove(userId);
        if (emitter != null) {
            try {
                emitter.complete();
                log.info("SSE connection removed for user: {}", userId);
            } catch (IllegalStateException e) {
                // 连接已经关闭，忽略
                log.debug("Connection already completed for user: {}", userId);
            } catch (Exception e) {
                log.error("Error completing SSE connection for user: {}", userId, e);
            }
        }
    }

    @Override
    public void sendEvent(String userId, String eventType, Object data) {
        SseEmitter emitter = connections.get(userId);

        if (emitter == null) {
            log.warn("No active SSE connection found for user: {}, eventType: {}", userId, eventType);
            return;
        }

        try {
            String jsonData = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event()
                    .name(eventType)
                    .data(jsonData));
            log.debug("SSE event sent to user {}: type={}", userId, eventType);

        } catch (IllegalStateException e) {
            // 连接已关闭
            log.debug("Connection already completed for user: {}, cannot send event", userId);
            cleanup(userId);
        } catch (IOException e) {
            log.warn("Failed to send event to user {}: type={}, connection closed",
                    userId, eventType);
            cleanup(userId);
        } catch (Exception e) {
            log.error("Unexpected error sending event to user {}: type={}", userId, eventType, e);
            cleanup(userId);
        }
    }

    @Override
    public boolean hasConnection(String userId) {
        return connections.containsKey(userId);
    }
}
