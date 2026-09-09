package com.guanghe.fs.file.mount;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 挂载互斥锁
 * <p>
 * key = storagePlatformSettingId。所有挂载写穿透（合并写真实文件、file_info 落库、
 * 目录改名/移动、卸载清索引）与扫描器都持此锁，保证互不交叠。
 *
 * @Author: guangheUlti
 * @Date: 2026/09/09
 */
@Slf4j
public class MountLocks {

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * 在指定挂载设置的锁内执行（可重入，同线程嵌套安全）
     */
    public <T> T callWithLock(String settingId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(settingId, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 在指定挂载设置的锁内执行（无返回值）
     */
    public void callWithLock(String settingId, Runnable action) {
        callWithLock(settingId, () -> {
            action.run();
            return null;
        });
    }
}
