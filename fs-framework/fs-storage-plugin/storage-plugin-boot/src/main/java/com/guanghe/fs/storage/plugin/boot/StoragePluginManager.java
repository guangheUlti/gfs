package com.guanghe.fs.storage.plugin.boot;

import com.guanghe.fs.framework.common.exception.StorageOperationException;
import com.guanghe.fs.storage.plugin.core.IStorageOperationService;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import com.guanghe.fs.storage.plugin.core.utils.StorageUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class StoragePluginManager implements DisposableBean {

    private final StoragePluginRegistry pluginRegistry;
    private final StorageInstanceFactory instanceFactory;
    private final StorageInstanceCache instanceCache;
    private final LocalStorageManager localStorageManager;

    public IStorageOperationService getCurrentInstance(String configId, Supplier<StorageConfig> configLoader) {
        if (StorageUtils.isLocalConfig(configId)) {
            return localStorageManager.getLocalInstance();
        }

        StorageConfig config = configLoader.get();
        String cacheKey = config.getCacheKey();
        IStorageOperationService instance = instanceCache.get(cacheKey);

        if (instance == null) {
            throw new StorageOperationException("存储实例未初始化，请检查配置: " + configId);
        }
        return instance;
    }

    public IStorageOperationService getOrCreateInstance(String configId, Supplier<StorageConfig> configLoader) {
        if (StorageUtils.isLocalConfig(configId)) {
            return localStorageManager.getLocalInstance();
        }

        StorageConfig config = configLoader.get();
        String cacheKey = config.getCacheKey();

        return instanceCache.getOrCreate(cacheKey, () -> {
            log.debug("开始创建存储实例: configId={}, cacheKey={}", configId, cacheKey);
            return instanceFactory.createInstance(config);
        });
    }

    public IStorageOperationService getLocalInstance() {
        return localStorageManager.getLocalInstance();
    }

    /**
     * 使配置失效（通过 configId 直接失效，不需要加载配置）
     *
     * @param configId 配置ID
     */
    public void invalidateConfig(String configId) {
        if (StorageUtils.isLocalConfig(configId)) {
            log.debug("Local 平台为全局单例，不支持失效操作");
            return;
        }

        log.info("使配置失效: configId={}", configId);
        instanceCache.invalidateByConfigId(configId);
    }

    /**
     * 批量使配置失效（通过 configId 列表）
     *
     * @param configIds 配置ID列表
     */
    public void invalidateConfigs(List<String> configIds) {
        if (configIds == null || configIds.isEmpty()) {
            return;
        }

        // 过滤掉 Local 配置
        List<String> validConfigIds = configIds.stream()
                .filter(id -> !StorageUtils.isLocalConfig(id))
                .toList();

        if (validConfigIds.isEmpty()) {
            log.debug("没有需要失效的配置");
            return;
        }

        log.info("批量使配置失效，共 {} 个配置", validConfigIds.size());
        instanceCache.invalidateBatchByConfigIds(validConfigIds);
    }

    /**
     * 清除用户的所有存储实例
     *
     * @param configIds 用户的配置ID列表
     */
    public void clearUserInstances(List<String> configIds) {
        log.info("清除用户的所有存储实例，共 {} 个配置", configIds == null ? 0 : configIds.size());
        invalidateConfigs(configIds);
    }

    public IStorageOperationService getPrototype(String platformIdentifier) {
        return pluginRegistry.getPrototype(platformIdentifier);
    }

    public Set<String> getAvailablePlatforms() {
        return pluginRegistry.getAvailablePlatforms();
    }

    public void clearAllCache() {
        log.warn("清空所有存储实例缓存");
        instanceCache.clear();
    }

    /**
     * 检查配置是否有缓存实例
     *
     * @param configId 配置ID
     * @return true-存在缓存
     */
    public boolean hasInstance(String configId) {
        if (StorageUtils.isLocalConfig(configId)) {
            return localStorageManager.isLocalInstanceCreated();
        }
        return instanceCache.containsConfigId(configId);
    }

    @Override
    public void destroy() {
        log.info("开始关闭所有存储实例...");
        localStorageManager.destroy();
        instanceCache.clear();
        log.info("所有存储实例已关闭");
    }
}
