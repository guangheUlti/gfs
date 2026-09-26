package com.guanghe.fs.file.mount;

import com.guanghe.fs.storage.plugin.core.IStorageOperationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 挂载实时监听服务
 * <p>
 * 基于 OS 目录变更事件（Windows ReadDirectoryChangesW / Linux inotify）把外部改动
 * 的感知延迟从「分钟级定时扫描」降到秒级：
 * <ul>
 *   <li>每个开启实时监听的挂载一个 WatchService + 守护线程，递归注册目录树（深度上限对齐扫描器 32）；</li>
 *   <li>事件风暴防抖：静止 500ms 后触发一次对账（回调 {@code MountScanService#scanSettingAsync}，
 *       扫描线程池串行执行，天然合并排队中的多次触发）；</li>
 *   <li>对账动作是幂等的全量对账（复用现有扫描），不依赖事件内容重建增量——事件语义简化，
 *       兜底 OVERFLOW（事件丢失）同样只靠对账自愈；</li>
 *   <li>新建子目录即时补注册；目录不可达（reset 失败）时放弃该轮注册，靠定时兜底全扫修复；</li>
 *   <li>写穿透持锁期间丢弃触发（MountLocks.isHeld）：网盘内操作由写穿透同步 DB，无需对账，
 *       也避免对账任务与写穿透无谓争锁。</li>
 * </ul>
 * 定时兜底全扫仍保留（MountScheduler）：覆盖监听异常、事件丢失、网络盘不支持等场景。
 *
 * @Author: guangheUlti
 * @Date: 2026/09/25
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MountWatchService {

    /** 事件静止判定窗口：最后一次事件后静默该时长才触发对账 */
    private static final long DEBOUNCE_MILLIS = 500;

    /** 与扫描器 MAX_DEPTH 对齐的监听注册深度上限 */
    private static final int MAX_WATCH_DEPTH = 32;

    /** 事件循环空转间隔（ms）：兼顾防抖检查频率与 CPU 空转 */
    private static final long POLL_INTERVAL_MILLIS = 50;

    /** 挂载锁：写穿透持锁期间丢弃对账触发（写穿透自身保证 DB 一致，扫描无需重复对账） */
    private final MountManager mountManager;

    /** 全局开关（fs.file.mount.watch-enabled，默认开；关闭后所有挂载退回纯定时扫描） */
    @Value("${fs.file.mount.watch-enabled:true}")
    private boolean watchEnabled;

    /** 对账回调：由 MountIntegrationConfig 在启动时注入（避免构造期循环依赖），实现为异步扫描 */
    private volatile Consumer<String> reconcileCallback;

    /** 运行中的监听：settingId → 状态 */
    private final Map<String, WatchState> watches = new ConcurrentHashMap<>();

    public void setReconcileCallback(Consumer<String> reconcileCallback) {
        this.reconcileCallback = reconcileCallback;
    }

    /**
     * 对齐单个挂载的监听状态（幂等，可反复调用）：
     * 期望开启（启用 + 全局开 + 平台支持 + 用户开启）且未运行 → 启动；
     * 期望关闭但仍在运行 → 停止。
     * 在每次扫描（scanSetting）完成后调用，配置变更/启用/禁用后自然收敛。
     */
    public void alignWatch(String settingId, IStorageOperationService instance) {
        boolean desired = watchEnabled
                && instance.isMountMode()
                && instance.isRealtimeWatchSupported()
                && Boolean.TRUE.equals(instance.supportsWatchRealtime());
        if (desired) {
            if (watches.containsKey(settingId)) {
                return; // 已在运行
            }
            Path root = instance.mountRootPath();
            if (root == null) {
                log.warn("挂载未暴露根路径，无法启动实时监听: settingId={}", settingId);
                return;
            }
            startWatch(settingId, root);
        } else {
            stopWatch(settingId);
        }
    }

    /** 停止并移除监听（禁用/卸载/编辑根路径时调用；不存在时静默） */
    public void stopWatch(String settingId) {
        WatchState state = watches.remove(settingId);
        if (state == null) {
            return;
        }
        state.stopped = true;
        try {
            state.watchService.close(); // 令 poll 立即抛 ClosedWatchServiceException 退出循环
        } catch (IOException e) {
            state.thread.interrupt();
        }
        log.info("挂载实时监听已停止: settingId={}", settingId);
    }

    /** 是否在运行（运维/排查用） */
    public boolean isWatching(String settingId) {
        return watches.containsKey(settingId);
    }

    private void startWatch(String settingId, Path root) {
        try {
            WatchService watchService = root.getFileSystem().newWatchService();
            WatchState state = new WatchState(settingId, watchService);
            Thread thread = new Thread(() -> runLoop(state, root), "mount-watch-" + settingId);
            thread.setDaemon(true);
            state.thread = thread;
            registerRecursive(state, root, 0);
            watches.put(settingId, state);
            thread.start();
            log.info("挂载实时监听已启动: settingId={}, root={}", settingId, root);
        } catch (IOException e) {
            // 启动失败不影响挂载本身：退化为纯定时扫描
            log.warn("挂载实时监听启动失败，退化为定时扫描: settingId={}, root={}, error={}",
                    settingId, root, e.getMessage());
        }
    }

    /** 递归注册目录树（不跟随符号链接，防循环） */
    private void registerRecursive(WatchState state, Path dir, int depth) throws IOException {
        if (depth > MAX_WATCH_DEPTH) {
            log.warn("监听注册深度超过 {}，停止下钻: settingId={}, dir={}", MAX_WATCH_DEPTH, state.settingId, dir);
            return;
        }
        dir.register(state.watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    registerRecursive(state, child, depth + 1);
                }
            }
        }
    }

    /** 事件循环：收集事件 → 静止防抖窗口 → 触发一次对账 */
    private void runLoop(WatchState state, Path root) {
        long lastEventAt = -1;
        while (!state.stopped) {
            try {
                WatchKey key = state.watchService.poll(POLL_INTERVAL_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (key == null) {
                    // 无事件：检查防抖窗口是否已满
                    if (lastEventAt >= 0 && System.currentTimeMillis() - lastEventAt >= DEBOUNCE_MILLIS) {
                        lastEventAt = -1;
                        triggerReconcile(state.settingId);
                    }
                    continue;
                }
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        // 事件丢失：标记有变更，交给全量对账自愈
                        log.warn("挂载监听事件溢出，将触发全量对账: settingId={}", state.settingId);
                        lastEventAt = System.currentTimeMillis();
                        continue;
                    }
                    Path dir = (Path) key.watchable();
                    Path child = dir.resolve((Path) event.context());
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE
                            && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                        // 新建子目录即时补注册（其内部事件才收得到）
                        try {
                            registerRecursive(state, child, 0);
                        } catch (IOException e) {
                            log.warn("新目录监听注册失败，靠定时扫描兜底: settingId={}, dir={}, error={}",
                                    state.settingId, child, e.getMessage());
                        }
                    }
                }
                if (!key.reset()) {
                    // 目录不可达（被删/权限变化）：放弃该 key，本轮事件处理完后靠对账与定时扫描修复
                    log.warn("挂载监听目录不可达，watchKey 失效: settingId={}, dir={}", state.settingId, key.watchable());
                }
                lastEventAt = System.currentTimeMillis();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break; // 正常停止路径
            } catch (Exception e) {
                // 未预期异常：不终止监听，短暂退避后继续
                log.warn("挂载监听循环异常，继续: settingId={}, error={}", state.settingId, e.getMessage());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("挂载监听线程退出: settingId={}, root={}", state.settingId, root);
    }

    /** 防抖窗口到期后触发对账：写穿透持锁期间丢弃（写穿透自身保证 DB 一致），否则异步扫描 */
    private void triggerReconcile(String settingId) {
        if (mountManager.locks().isLocked(settingId)) {
            log.debug("写穿透持锁中，丢弃本次实时对账触发: settingId={}", settingId);
            return;
        }
        Consumer<String> callback = this.reconcileCallback;
        if (callback == null) {
            return;
        }
        try {
            callback.accept(settingId);
        } catch (Exception e) {
            log.warn("实时监听触发对账失败: settingId={}", settingId, e);
        }
    }

    /** 单个挂载的监听状态 */
    private static class WatchState {
        final String settingId;
        final WatchService watchService;
        Thread thread;
        volatile boolean stopped;

        WatchState(String settingId, WatchService watchService) {
            this.settingId = settingId;
            this.watchService = watchService;
        }
    }
}
