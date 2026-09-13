package com.guanghe.fs.storage.plugin.boot;

import com.guanghe.fs.storage.plugin.core.IStorageOperationService;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import com.guanghe.fs.storage.plugin.core.utils.StorageUtils;
import com.guanghe.fs.storage.plugin.local.config.LocalStorageProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Local 存储管理器
 *
 * 管理 Local 存储全局单例
 * 懒加载创建实例
 * 提供线程安全的单例访问
 *
 * @Author: guangheUlti
 * @Date: 2026/01/12 22:06
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalStorageManager {

    private final LocalStorageProperties localStorageProperties;
    private final StorageInstanceFactory instanceFactory;

    /**
     * Local 全局单例实例
     */
    private volatile IStorageOperationService localInstance;

    /**
     * 创建锁（双重检查锁）
     */
    private final Lock createLock = new ReentrantLock();

    /**
     * DB 侧属性覆盖（由 fs-storage 注入，返回内置 Local 的 configData；为空时沿用 application.yml 默认值）
     */
    private volatile Supplier<Map<String, Object>> propertiesOverride;

    /**
     * 初始化：打印配置信息
     */
    @PostConstruct
    public void init() {
        log.info("Local 存储配置: basePath={}", localStorageProperties.getBasePath());
    }

    /**
     * 获取 Local 实例（懒加载）
     *
     * @return Local 存储实例
     */
    public IStorageOperationService getLocalInstance() {
        // 第一次检查（无锁）
        if (localInstance != null) {
            return localInstance;
        }

        // 持锁创建
        createLock.lock();
        try {
            // 第二次检查（持锁）
            if (localInstance != null) {
                return localInstance;
            }

            // 创建 Local 实例
            localInstance = createLocalInstance();

            log.info("Local 全局实例创建成功（系统默认存储）: basePath={}",
                    effectiveProperties().get("basePath"));

            return localInstance;

        } finally {
            createLock.unlock();
        }
    }

    /**
     * 创建 Local 实例
     *
     * @return Local 存储实例
     */
    private IStorageOperationService createLocalInstance() {
        Map<String, Object> properties = effectiveProperties();
        StorageConfig localConfig = StorageConfig.builder()
                .configId(null) // Local 无需 configId
                .platformIdentifier(StorageUtils.LOCAL_PLATFORM_IDENTIFIER)
                .enabled(true)
                .properties(properties)
                .build();

        return instanceFactory.createInstance(localConfig);
    }

    /**
     * 注入 DB 侧属性覆盖并重建实例
     * 由 fs-storage 在启动与内置 Local 配置变更时调用
     */
    public void setPropertiesOverride(Supplier<Map<String, Object>> override) {
        this.propertiesOverride = override;
        reset();
    }

    /**
     * application.yml 默认属性（用于内置 Local 配置行初始化）
     */
    public Map<String, Object> getDefaultProperties() {
        return localStorageProperties.toPropertiesMap();
    }

    /**
     * 当前生效属性：application.yml 默认值 + DB 覆盖值（空白值忽略）
     */
    public Map<String, Object> getEffectiveProperties() {
        return effectiveProperties();
    }

    private Map<String, Object> effectiveProperties() {
        Map<String, Object> props = new HashMap<>(localStorageProperties.toPropertiesMap());
        Supplier<Map<String, Object>> override = this.propertiesOverride;
        if (override != null) {
            Map<String, Object> custom = override.get();
            if (custom != null) {
                custom.forEach((key, value) -> {
                    if (value != null && !String.valueOf(value).isBlank()) {
                        props.put(key, value);
                    }
                });
            }
        }
        return props;
    }

    /**
     * 重置 Local 实例：下次访问时按当前覆盖属性懒重建
     */
    public void reset() {
        destroy();
    }

    /**
     * 销毁 Local 实例
     */
    public void destroy() {
        if (localInstance != null) {
            try {
                localInstance.close();
                log.info("Local 全局实例已关闭");
            } catch (IOException e) {
                log.error("关闭 Local 实例失败: {}", e.getMessage(), e);
            } finally {
                localInstance = null;
            }
        }
    }

    /**
     * 检查 Local 实例是否已创建
     *
     * @return true-已创建
     */
    public boolean isLocalInstanceCreated() {
        return localInstance != null;
    }
}
