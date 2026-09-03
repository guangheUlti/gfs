package com.guanghe.fs.storage.plugin.boot;

import com.guanghe.fs.framework.common.exception.StorageOperationException;
import com.guanghe.fs.storage.plugin.core.IStorageOperationService;
import com.guanghe.fs.storage.plugin.core.annotation.StoragePlugin;
import com.guanghe.fs.storage.plugin.core.dto.StoragePluginMetadata;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件注册器
 *
 * @Author: guangheUlti
 * @Date: 2026/01/12 22:06
 */
@Slf4j
@Component
public class StoragePluginRegistry {
    /**
     * 插件原型实例缓存
     * Key: platformIdentifier (如: Local, AliyunOSS, RustFS)
     * Value: 原型实例（用于获取 Schema 和创建新实例）
     */
    private final Map<String, IStorageOperationService> prototypes = new ConcurrentHashMap<>();
    
    /**
     * 插件元数据缓存
     * Key: platformIdentifier
     * Value: 插件元数据
     */
    private final Map<String, StoragePluginMetadata> metadataMap = new ConcurrentHashMap<>();

    /**
     * 初始化：通过 SPI 加载插件
     * 只有同时满足以下条件的类才被认为是有效的存储插件：
     * 1. 实现 IStorageOperationService 接口
     * 2. 在 META-INF/services 中注册（SPI）
     * 3. 标注 @StoragePlugin 注解
     */
    @PostConstruct
    public void loadPlugins() {
        log.info("开始加载存储插件...");

        ServiceLoader<IStorageOperationService> loader =
                ServiceLoader.load(IStorageOperationService.class);

        int loadedCount = 0;
        int skippedCount = 0;
        
        for (IStorageOperationService prototype : loader) {
            Class<?> pluginClass = prototype.getClass();
            String className = pluginClass.getName();
            
            // 验证是否标注了 @StoragePlugin 注解
            StoragePlugin annotation = pluginClass.getAnnotation(StoragePlugin.class);
            if (annotation == null) {
                log.warn("跳过未标注 @StoragePlugin 注解的插件: {}", className);
                skippedCount++;
                continue;
            }
            
            String identifier = annotation.identifier();

            if (prototypes.containsKey(identifier)) {
                log.warn("发现重复的平台标识符，跳过: {}", identifier);
                skippedCount++;
                continue;
            }

            // 提取并缓存元数据
            try {
                StoragePluginMetadata metadata = StoragePluginMetadata.fromPluginClass(pluginClass);
                prototypes.put(identifier, prototype);
                metadataMap.put(identifier, metadata);
                log.info("注册存储插件: {} ({})", annotation.name(), identifier);
                loadedCount++;
            } catch (Exception e) {
                log.error("提取插件元数据失败: {}", className, e);
                skippedCount++;
            }
        }

        log.info("存储插件加载完成，成功: {}, 跳过: {}, 可用平台: {}", 
            loadedCount, skippedCount, prototypes.keySet());

        if (loadedCount == 0) {
            log.warn("未加载到任何存储插件，请检查 META-INF/services 配置和 @StoragePlugin 注解");
        }
    }

    /**
     * 获取插件原型实例
     *
     * @param platformIdentifier 平台标识符
     * @return 原型实例
     * @throws StorageOperationException 插件不存在时抛出
     */
    public IStorageOperationService getPrototype(String platformIdentifier) {
        IStorageOperationService prototype = prototypes.get(platformIdentifier);

        if (prototype == null) {
            throw new StorageOperationException(
                    String.format("不支持的存储平台: %s，可用平台: %s",
                            platformIdentifier, prototypes.keySet())
            );
        }

        return prototype;
    }

    /**
     * 检查插件是否存在
     *
     * @param platformIdentifier 平台标识符
     * @return true-存在
     */
    public boolean hasPlugin(String platformIdentifier) {
        return prototypes.containsKey(platformIdentifier);
    }

    /**
     * 获取所有可用平台标识符
     *
     * @return 平台标识符集合（不可修改）
     */
    public Set<String> getAvailablePlatforms() {
        return Collections.unmodifiableSet(prototypes.keySet());
    }

    /**
     * 获取插件数量
     *
     * @return 插件数量
     */
    public int getPluginCount() {
        return prototypes.size();
    }
    
    /**
     * 获取所有插件元数据
     *
     * @return 所有插件元数据集合（不可修改）
     */
    public Collection<StoragePluginMetadata> getAllMetadata() {
        return Collections.unmodifiableCollection(metadataMap.values());
    }
    
    /**
     * 获取指定插件的元数据
     *
     * @param identifier 平台标识符
     * @return 插件元数据，如果不存在返回 null
     */
    public StoragePluginMetadata getMetadata(String identifier) {
        return metadataMap.get(identifier);
    }
    
    /**
     * 从插件实例获取标识符（通过注解）
     *
     * @param plugin 插件实例
     * @return 平台标识符，如果插件未标注注解返回 null
     */
    public String getIdentifier(IStorageOperationService plugin) {
        if (plugin == null) {
            return null;
        }
        StoragePlugin annotation = plugin.getClass().getAnnotation(StoragePlugin.class);
        return annotation != null ? annotation.identifier() : null;
    }
}
