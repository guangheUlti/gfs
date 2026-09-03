package com.guanghe.fs.storage.plugin.boot;

import com.google.common.util.concurrent.Striped;
import com.guanghe.fs.storage.plugin.core.IStorageOperationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

@Slf4j
@Component
public class StorageInstanceCache {

    /**
     * 主缓存：cacheKey -> 实例
     * cacheKey 格式：configId:platformIdentifier
     */
    private final Map<String, IStorageOperationService> cache = new ConcurrentHashMap<>();

    /**
     * 反向索引：configId -> cacheKey
     * 用于快速通过 configId 定位缓存
     */
    private final Map<String, String> configIdToCacheKey = new ConcurrentHashMap<>();

    private final Striped<Lock> locks = Striped.lock(128);

    public IStorageOperationService get(String cacheKey) {
        IStorageOperationService instance = cache.get(cacheKey);
        if (instance != null) {
            log.debug("缓存命中: cacheKey={}", cacheKey);
        }
        return instance;
    }

    public void put(String cacheKey, IStorageOperationService instance) {
        cache.put(cacheKey, instance);

        // 维护反向索引
        String configId = extractConfigId(cacheKey);
        if (configId != null) {
            configIdToCacheKey.put(configId, cacheKey);
            log.debug("建立索引映射: configId={} -> cacheKey={}", configId, cacheKey);
        }

        log.debug("放入缓存: cacheKey={}, 当前缓存数: {}", cacheKey, cache.size());
    }

    public IStorageOperationService getOrCreate(String cacheKey, Supplier<IStorageOperationService> creator) {
        IStorageOperationService instance = cache.get(cacheKey);
        if (instance != null) {
            return instance;
        }

        Lock lock = locks.get(cacheKey);
        lock.lock();
        try {
            instance = cache.get(cacheKey);
            if (instance != null) {
                return instance;
            }

            instance = creator.get();

            // 使用 put 方法，自动维护索引
            put(cacheKey, instance);

            log.debug("实例创建并缓存: cacheKey={}", cacheKey);
            return instance;

        } finally {
            lock.unlock();
        }
    }

    /**
     * 根据 cacheKey 失效缓存
     */
    public void invalidate(String cacheKey) {
        IStorageOperationService instance = cache.remove(cacheKey);

        // 同步移除反向索引
        String configId = extractConfigId(cacheKey);
        if (configId != null) {
            configIdToCacheKey.remove(configId);
            log.debug("移除索引映射: configId={}", configId);
        }

        if (instance != null) {
            closeInstanceSafely(instance, cacheKey);
            log.info("缓存失效: cacheKey={}, 剩余缓存数: {}", cacheKey, cache.size());
        } else {
            log.debug("缓存中无实例需要失效: cacheKey={}", cacheKey);
        }
    }

    /**
     * 根据 configId 失效缓存
     *
     * @param configId 配置ID
     */
    public void invalidateByConfigId(String configId) {
        String cacheKey = configIdToCacheKey.get(configId);

        if (cacheKey != null) {
            invalidate(cacheKey);
            return;
        }

        // 如果反向索引中找不到，尝试遍历所有缓存查找（兼容旧数据）
        String foundCacheKey = null;
        for (String key : cache.keySet()) {
            String extractedConfigId = extractConfigId(key);
            if (configId.equals(extractedConfigId)) {
                foundCacheKey = key;
                break;
            }
        }

        if (foundCacheKey != null) {
            invalidate(foundCacheKey);
        }
    }

    /**
     * 批量失效（根据 cacheKey）
     */
    public void invalidateBatch(Iterable<String> cacheKeys) {
        int count = 0;
        for (String cacheKey : cacheKeys) {
            IStorageOperationService instance = cache.remove(cacheKey);

            // 同步移除反向索引
            String configId = extractConfigId(cacheKey);
            if (configId != null) {
                configIdToCacheKey.remove(configId);
            }

            if (instance != null) {
                closeInstanceSafely(instance, cacheKey);
                count++;
            }
        }
        if (count > 0) {
            log.info("批量失效完成，共失效 {} 个实例，剩余缓存数: {}", count, cache.size());
        }
    }

    /**
     * 批量失效（根据 configId）（新增方法）
     */
    public void invalidateBatchByConfigIds(List<String> configIds) {
        if (configIds == null || configIds.isEmpty()) {
            return;
        }

        int count = 0;
        for (String configId : configIds) {
            String cacheKey = configIdToCacheKey.get(configId);
            if (cacheKey != null) {
                IStorageOperationService instance = cache.remove(cacheKey);
                configIdToCacheKey.remove(configId);

                if (instance != null) {
                    closeInstanceSafely(instance, cacheKey);
                    count++;
                }
            }
        }

        if (count > 0) {
            log.info("批量失效完成（按 configId），共失效 {} 个实例，剩余缓存数: {}", count, cache.size());
        }
    }

    public void clear() {
        log.warn("清空所有缓存，当前缓存数: {}", cache.size());
        cache.forEach((cacheKey, instance) -> closeInstanceSafely(instance, cacheKey));
        cache.clear();
        configIdToCacheKey.clear();
        log.info("所有缓存已清空");
    }

    public int size() {
        return cache.size();
    }

    public boolean contains(String cacheKey) {
        return cache.containsKey(cacheKey);
    }

    /**
     * 检查 configId 是否有缓存实例（新增方法）
     */
    public boolean containsConfigId(String configId) {
        return configIdToCacheKey.containsKey(configId);
    }

    /**
     * 从 cacheKey 提取 configId
     * cacheKey 格式：configId:platformIdentifier
     *
     * @param cacheKey 缓存键
     * @return configId，如果格式不正确返回 null
     */
    private String extractConfigId(String cacheKey) {
        if (cacheKey == null || cacheKey.isEmpty()) {
            return null;
        }

        int firstColon = cacheKey.indexOf(':');
        if (firstColon <= 0) {
            log.warn("cacheKey 格式异常，无法提取 configId: {}", cacheKey);
            return null;
        }

        return cacheKey.substring(0, firstColon);
    }

    private void closeInstanceSafely(IStorageOperationService instance, String cacheKey) {
        try {
            instance.close();
            log.debug("成功关闭实例: cacheKey={}", cacheKey);
        } catch (IOException e) {
            log.error("关闭实例失败: cacheKey={}, error={}", cacheKey, e.getMessage());
        } catch (Exception e) {
            log.error("关闭实例时发生未知错误: cacheKey={}", cacheKey, e);
        }
    }
}
