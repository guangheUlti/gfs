package com.guanghe.fs.storage.plugin.boot;

import com.guanghe.fs.framework.common.exception.StorageOperationException;
import com.guanghe.fs.storage.plugin.core.IStorageOperationService;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 存储实例工厂
 *
 * @Author: guangheUlti
 * @Date: 2026/01/12 22:06
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageInstanceFactory {

    private final StoragePluginRegistry pluginRegistry;

    /**
     * 创建存储实例
     *
     * @param config 存储配置
     * @return 存储实例
     * @throws StorageOperationException 创建失败时抛出
     */
    public IStorageOperationService createInstance(StorageConfig config) {
        // 验证配置（通用验证）
        config.validate();

        // 获取插件原型
        IStorageOperationService prototype = pluginRegistry.getPrototype(
                config.getPlatformIdentifier()
        );

        // 创建配置化实例
        try {
            IStorageOperationService instance = prototype.createConfiguredInstance(config);

            log.info("创建存储实例成功: configId={}, platform={}",
                    config.getConfigId(),
                    config.getPlatformIdentifier());

            return instance;

        } catch (Exception e) {
            log.error("创建存储实例失败: configId={}, platform={}, error={}",
                    config.getConfigId(),
                    config.getPlatformIdentifier(),
                    e.getMessage(),
                    e);

            throw new StorageOperationException(
                    String.format("创建存储实例失败 [%s]: %s",
                            config.getPlatformIdentifier(), e.getMessage()),
                    e
            );
        }
    }
}
