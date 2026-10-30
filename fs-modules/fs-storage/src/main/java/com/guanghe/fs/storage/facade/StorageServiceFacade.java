package com.guanghe.fs.storage.facade;

import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.exception.StorageConfigException;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.storage.domain.StorageSetting;
import com.guanghe.fs.storage.mapper.StorageSettingMapper;
import com.guanghe.fs.storage.plugin.boot.StoragePluginManager;
import com.guanghe.fs.storage.plugin.core.IStorageOperationService;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import com.guanghe.fs.storage.plugin.core.context.StoragePlatformContextHolder;
import com.guanghe.fs.storage.plugin.core.utils.StorageUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 存储服务门面
 * 职责：
 * 1. 提供统一的存储服务访问入口
 * 2. 整合上下文、配置加载、实例管理
 * 3. 对外隐藏内部实现细节
 *
 * @Author: guangheUlti
 * @Date: 2024/10/26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageServiceFacade {

    private final StoragePluginManager pluginManager;
    private final StorageSettingMapper storageSettingMapper;
    private final ObjectMapper objectMapper;

    /**
     * 获取当前上下文的存储服务
     * 从 ThreadLocal 获取 configId，然后获取对应的存储实例
     *
     * @return 存储服务实例
     */
    public IStorageOperationService getCurrentStorageService() {
        String configId = StoragePlatformContextHolder.getConfigId();
        return getStorageService(configId);
    }

    /**
     * 根据配置ID获取存储服务（推荐使用）
     * <p>
     * 此方法不依赖 ThreadLocal，适用于所有场景，包括异步任务和多线程环境。
     *
     * @param configId 配置ID（可从 FileInfo.storagePlatformSettingId 获取）
     * @return 存储服务实例
     */
    public IStorageOperationService getStorageService(String configId) {
        // Local 存储
        if (StorageUtils.isLocalConfig(configId)) {
            return pluginManager.getLocalInstance();
        }

        // 用户配置存储（带缓存和延迟加载）
        return pluginManager.getOrCreateInstance(
                configId,
                () -> loadConfigFromDatabase(configId)
        );
    }

    /**
     * 刷新存储实例
     * 先失效缓存，下次使用时自动重新加载
     *
     * @param configId 配置ID
     */
    public void refreshInstance(String configId) {
        if (StorageUtils.isLocalConfig(configId)) {
            log.warn("Local 存储实例不支持刷新");
            return;
        }

        // 检查是否有缓存实例
        boolean hasInstance = pluginManager.hasInstance(configId);
        if (!hasInstance) {
            return;
        }

        // 失效缓存，下次使用时自动重新加载
        pluginManager.invalidateConfig(configId);
    }

    /**
     * 移除存储实例
     * 用于删除配置或禁用配置时清理缓存
     *
     * @param configId 配置ID
     */
    public void removeInstance(String configId) {
        if (StorageUtils.isLocalConfig(configId)) {
            log.warn("Local 存储实例不支持移除");
            return;
        }

        log.info("移除存储实例: configId={}", configId);

        // 直接失效缓存，不需要加载配置
        pluginManager.invalidateConfig(configId);
    }

    /**
     * 从数据库加载配置（仅在缓存未命中时调用）
     *
     * @param configId 配置ID
     * @return 存储配置对象
     * @throws BusinessException 配置不存在或无效时抛出
     */
    private StorageConfig loadConfigFromDatabase(String configId) {
        log.debug("从数据库加载存储配置: configId={}", configId);

        // 查询配置
        StorageSetting settings = storageSettingMapper.selectOneById(configId);

        // 配置验证
        if (settings == null) {
            throw new BusinessException(I18nUtils.getMessage("storage.config.not.found", new Object[]{configId}));
        }
        if (settings.getDeleted() == 1) {
            throw new BusinessException(I18nUtils.getMessage("storage.config.deleted", new Object[]{configId}));
        }
        if (settings.getEnabled() == 0) {
            throw new BusinessException(I18nUtils.getMessage("storage.config.disabled", new Object[]{configId}));
        }

        // 构建配置对象
        return buildStorageConfig(settings);
    }

    /**
     * 构建 StorageConfig 对象
     *
     * @param setting 数据库配置记录
     * @return StorageConfig
     * @throws StorageConfigException 配置解析失败时抛出
     */
    private StorageConfig buildStorageConfig(StorageSetting setting) {
        Map<String, Object> properties;

        try {
            if (StrUtil.isBlank(setting.getConfigData())) {
                throw new StorageConfigException(
                        I18nUtils.getMessage("storage.config.data.empty", new Object[]{setting.getId()})
                );
            }

            properties = objectMapper.readValue(
                    setting.getConfigData(),
                    new TypeReference<>() {
                    }
            );

        } catch (StorageConfigException e) {
            // 重新抛出配置异常
            throw e;
        } catch (Exception e) {
            log.error("配置数据解析失败: configId={}, error={}",
                    setting.getId(), e.getMessage(), e);
            throw new StorageConfigException(
                    I18nUtils.getMessage("storage.config.data.parse.failed", 
                            new Object[]{setting.getId(), e.getMessage()}),
                    e
            );
        }

        return StorageConfig.builder()
                .configId(setting.getId())
                .platformIdentifier(setting.getPlatformIdentifier())
                .properties(properties)
                .enabled(setting.getEnabled() != null && setting.getEnabled() == 1)
                .remark(setting.getRemark())
                .build();
    }
}
