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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

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
     * 初始化：打印配置信息
     */
    @PostConstruct
    public void init() {
        log.info("Local 存储配置: basePath={}, baseUrl={}",
                localStorageProperties.getBasePath(),
                localStorageProperties.getBaseUrl());
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
                    localStorageProperties.getBasePath());

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
        StorageConfig localConfig = StorageConfig.builder()
                .configId(null) // Local 无需 configId
                .platformIdentifier(StorageUtils.LOCAL_PLATFORM_IDENTIFIER)
                .enabled(true)
                .properties(localStorageProperties.toPropertiesMap())
                .build();

        return instanceFactory.createInstance(localConfig);
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
