package com.guanghe.fs.service.webdav;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebDAV LOCK / UNLOCK 的内存锁管理器（RFC 4918 写锁的最小实现）。
 * <p>
 * 设计要点（与性能/并发边界对齐）：
 * <ul>
 *   <li>per-resource 的 {@link ConcurrentHashMap}，键 = 文件 id，资源之间完全并行，无全局锁；</li>
 *   <li>锁带过期时间（TTL），后台线程定时清扫过期锁，防止客户端崩溃忘 UNLOCK 造成脏锁累积；</li>
 *   <li>读路径（GET / 视频 Range 播放）完全不经过本组件，锁只影响写语义，性能零影响。</li>
 * </ul>
 * <p>
 * 并发语义（最小可用版）：
 * <ul>
 *   <li><b>独占锁</b>（exclusive）：同人重复加锁幂等刷新；他人持锁冲突时才返回 423。</li>
 *   <li><b>共享锁</b>（shared）：不做互斥（多读者场景），正常返回锁信息。</li>
 *   <li>携带匹配 If token 的请求视为持锁方，刷新并放行。</li>
 * </ul>
 */
@Slf4j
@Component
public class DavLockManager {

    /** 默认锁时长（秒）；请求 Timeout 头缺省/为 Infinite 时使用 */
    public static final long DEFAULT_TIMEOUT_SECONDS = 3600;
    /** 单把锁时长上限（秒），防止客户端请求无限期锁 */
    private static final long MAX_TIMEOUT_SECONDS = 86400;

    /** fileId -> 锁（组件单例，静态表等价于实例表） */
    private static final Map<String, ActiveLock> LOCK_MAP = new ConcurrentHashMap<>();

    /** 后台清扫：每 60s 移除过期锁 */
    private static final ScheduledExecutorService SWEEPER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dav-lock-sweeper");
        t.setDaemon(true);
        return t;
    });

    static {
        SWEEPER.scheduleWithFixedDelay(() -> {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, ActiveLock> e : LOCK_MAP.entrySet()) {
                if (e.getValue().expiresAt() <= now) {
                    LOCK_MAP.remove(e.getKey(), e.getValue());
                }
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    /** 锁作用域 */
    public enum Scope { EXCLUSIVE, SHARED }

    /** 一把活动锁 */
    public record ActiveLock(String fileId, String token, String ownerId, String ownerLabel,
                             Scope scope, long timeoutSeconds, long expiresAt) {
        ActiveLock withExpiresAt(long newExpiresAt) {
            return new ActiveLock(fileId, token, ownerId, ownerLabel, scope, timeoutSeconds, newExpiresAt);
        }
    }

    /** 加锁结果 */
    public record AcquireOutcome(boolean conflict, ActiveLock lock) {
        static AcquireOutcome ok(ActiveLock lock) { return new AcquireOutcome(false, lock); }
        static AcquireOutcome conflict(ActiveLock lock) { return new AcquireOutcome(true, lock); }
    }

    /**
     * 加锁（同 key 并发安全）。若已存在活动锁：
     * <ul>
     *   <li>ifToken 匹配 → 刷新过期时间；</li>
     *   <li>同 ownerId → 幂等刷新（同人重复 LOCK）；</li>
     *   <li>否则保持原锁 → 由返回值标记为冲突。</li>
     * </ul>
     */
    public AcquireOutcome acquire(String fileId, Scope scope, String ownerId, String ownerLabel,
                                  long timeoutSeconds, String ifToken) {
        long ttl = clamp(timeoutSeconds);
        ActiveLock result = LOCK_MAP.compute(fileId, (k, existing) -> {
            long now = System.currentTimeMillis();
            if (existing != null && existing.expiresAt() > now) {
                if (StrUtil.isNotBlank(ifToken) && existing.token().equals(ifToken)) {
                    return existing.withExpiresAt(now + ttl * 1000L);
                }
                if (existing.ownerId().equals(ownerId)) {
                    return existing.withExpiresAt(now + ttl * 1000L);
                }
                return existing; // 他人锁：保持原锁，由返回值标冲突
            }
            String token = "opaquelocktoken:" + UUID.randomUUID();
            return new ActiveLock(k, token, ownerId, ownerLabel, scope, ttl, now + ttl * 1000L);
        });
        // 冲突判定：当前用户未拿到锁而是撞上他人原锁（且无匹配 ifToken）
        boolean isConflict = result != null
                && (StrUtil.isBlank(ifToken) || !result.token().equals(ifToken))
                && !result.ownerId().equals(ownerId);
        return isConflict ? AcquireOutcome.conflict(result) : AcquireOutcome.ok(result);
    }

    /** 释放锁：token 匹配才移除 */
    public boolean unlock(String fileId, String token) {
        if (StrUtil.isBlank(token)) {
            return false;
        }
        AtomicBoolean removed = new AtomicBoolean(false);
        LOCK_MAP.computeIfPresent(fileId, (k, lock) -> {
            if (lock.token().equals(token)) {
                removed.set(true);
                return null; // 移除
            }
            return lock;
        });
        return removed.get();
    }

    /** 是否被「他人」独占锁（本人或共享锁不阻断） */
    public boolean isLockedByOther(String fileId, String ownerId) {
        ActiveLock lock = getActive(fileId);
        return lock != null && lock.scope() == Scope.EXCLUSIVE && !lock.ownerId().equals(ownerId);
    }

    /** 活动锁携带的 token 是否出现在 If 头中 */
    public boolean matchesToken(String fileId, String ifHeader) {
        if (StrUtil.isBlank(ifHeader)) {
            return false;
        }
        ActiveLock lock = getActive(fileId);
        return lock != null && ifHeader.contains(lock.token());
    }

    /** 获取活动锁（惰性清掉已过期项） */
    public ActiveLock getActive(String fileId) {
        ActiveLock lock = LOCK_MAP.get(fileId);
        if (lock == null) {
            return null;
        }
        if (lock.expiresAt() <= System.currentTimeMillis()) {
            LOCK_MAP.remove(fileId, lock);
            return null;
        }
        return lock;
    }

    /** 钳制锁时长到 [1, MAX] 秒，非法则用默认 */
    private static long clamp(long seconds) {
        if (seconds <= 0) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        return Math.min(seconds, MAX_TIMEOUT_SECONDS);
    }
}