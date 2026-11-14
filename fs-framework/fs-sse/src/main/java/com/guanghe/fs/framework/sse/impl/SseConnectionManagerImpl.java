package com.guanghe.fs.framework.sse.impl;

import com.guanghe.fs.framework.sse.SseConnectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SSE 连接管理器：同一用户允许多条连接（多标签页/多窗口），事件广播到全部连接。
 * 单连接模型下后建立的连接会把旧连接挤出映射却不断开，旧页面永远收不到
 * 进度与完成事件，传输任务会一直停在「上传中」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseConnectionManagerImpl implements SseConnectionManager {

    private static final long SSE_TIMEOUT = 60 * 60 * 1000L;
    private static final long HEARTBEAT_INTERVAL = 25 * 1000L;

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, SseEmitter>> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(2);

    @Override
    public SseEmitter createConnection(String userId) {
        String connectionId = userId + ":" + UUID.randomUUID();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        connections.computeIfAbsent(userId, key -> new ConcurrentHashMap<>()).put(connectionId, emitter);

        emitter.onCompletion(() -> removeConnection(userId, connectionId, "client closed"));
        emitter.onTimeout(() -> removeConnection(userId, connectionId, "timeout"));
        emitter.onError(throwable -> removeConnection(userId, connectionId,
                "error: " + throwable.getMessage()));

        startHeartbeat(userId, connectionId, emitter);

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("{\"message\":\"SSE connection established\"}"));
        } catch (IOException e) {
            log.warn("Failed to send connection confirmation: userId={}, connectionId={}", userId, connectionId);
            removeConnection(userId, connectionId, "confirmation failed");
        }

        log.info("SSE connection created: userId={}, connectionId={}, active={}",
                userId, connectionId, connectionCount(userId));
        return emitter;
    }

    /**
     * 摘掉一条连接并主动 complete 响应。
     * <p>
     * 只从映射里移除是不够的：浏览器端的 EventSource 收不到任何关闭信号，会一直
     * 以为连接还开着，页面据此跳过高频轮询，进度与完成事件只能等兜底轮询才生效。
     * complete() 让浏览器立刻收到 EOF，按 EventSource 语义自动重连。
     */
    private void removeConnection(String userId, String connectionId, String reason) {
        stopHeartbeat(connectionId);
        ConcurrentHashMap<String, SseEmitter> userConnections = connections.get(userId);
        if (userConnections == null) {
            return;
        }
        SseEmitter emitter = userConnections.remove(connectionId);
        if (userConnections.isEmpty()) {
            connections.remove(userId, userConnections);
        }
        if (emitter == null) {
            return;
        }
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE emitter already closed: userId={}, connectionId={}", userId, connectionId);
        }
        if ("client closed".equals(reason)) {
            log.info("SSE connection removed: userId={}, connectionId={}, reason={}, active={}",
                    userId, connectionId, reason, connectionCount(userId));
        } else {
            log.warn("SSE connection dropped: userId={}, connectionId={}, reason={}, active={}",
                    userId, connectionId, reason, connectionCount(userId));
        }
    }

    private void startHeartbeat(String userId, String connectionId, SseEmitter emitter) {
        ScheduledFuture<?> task = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                if (!isRegistered(userId, connectionId)) {
                    stopHeartbeat(connectionId);
                    return;
                }
                // 用具名事件而不是注释：注释对浏览器 JS 不可见，客户端无法据此判断连接是否还活着
                emitter.send(SseEmitter.event().name("ping").data("{}"));
            } catch (Exception e) {
                removeConnection(userId, connectionId, "heartbeat failed");
            }
        }, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
        heartbeatTasks.put(connectionId, task);
    }

    private void stopHeartbeat(String connectionId) {
        ScheduledFuture<?> task = heartbeatTasks.remove(connectionId);
        if (task != null && !task.isCancelled()) {
            task.cancel(false);
        }
    }

    private boolean isRegistered(String userId, String connectionId) {
        ConcurrentHashMap<String, SseEmitter> userConnections = connections.get(userId);
        return userConnections != null && userConnections.containsKey(connectionId);
    }

    private int connectionCount(String userId) {
        ConcurrentHashMap<String, SseEmitter> userConnections = connections.get(userId);
        return userConnections == null ? 0 : userConnections.size();
    }

    @Override
    public void removeConnection(String userId) {
        ConcurrentHashMap<String, SseEmitter> userConnections = connections.remove(userId);
        if (userConnections == null) {
            return;
        }
        userConnections.forEach((connectionId, emitter) -> {
            stopHeartbeat(connectionId);
            try {
                emitter.complete();
            } catch (Exception e) {
                log.debug("SSE connection already closed: userId={}, connectionId={}", userId, connectionId);
            }
        });
        log.info("SSE connections removed for user: {}, count={}", userId, userConnections.size());
    }

    @Override
    public void sendEvent(String userId, String eventType, Object data) {
        ConcurrentHashMap<String, SseEmitter> userConnections = connections.get(userId);
        if (userConnections == null || userConnections.isEmpty()) {
            log.warn("No active SSE connection found for user: {}, eventType: {}", userId, eventType);
            return;
        }

        String jsonData;
        try {
            jsonData = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.error("Failed to serialize SSE payload: userId={}, eventType={}", userId, eventType, e);
            return;
        }

        for (Map.Entry<String, SseEmitter> entry : userConnections.entrySet()) {
            try {
                entry.getValue().send(SseEmitter.event()
                        .name(eventType)
                        .data(jsonData));
            } catch (Exception e) {
                log.warn("SSE send failed, dropping connection: userId={}, connectionId={}, eventType={}, reason={}",
                        userId, entry.getKey(), eventType, e.getMessage());
                removeConnection(userId, entry.getKey(), "send failed: " + eventType);
            }
        }
    }

    @Override
    public boolean hasConnection(String userId) {
        return connectionCount(userId) > 0;
    }
}
