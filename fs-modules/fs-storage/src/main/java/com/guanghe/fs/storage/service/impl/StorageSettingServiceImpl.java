package com.guanghe.fs.storage.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.guanghe.fs.framework.common.constant.CommonConstant;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.framework.common.utils.JsonUtils;
import com.guanghe.fs.storage.domain.StoragePlatform;
import com.guanghe.fs.storage.domain.StorageSetting;
import com.guanghe.fs.storage.domain.cmd.StorageSettingAddCmd;
import com.guanghe.fs.storage.domain.cmd.StorageSettingEditCmd;
import com.guanghe.fs.storage.domain.vo.StorageActivePlatformsVO;
import com.guanghe.fs.storage.domain.vo.StoragePlatformVO;
import com.guanghe.fs.storage.domain.vo.StorageSettingUserVO;
import com.guanghe.fs.storage.facade.StorageServiceFacade;
import com.guanghe.fs.storage.mapper.StorageSettingMapper;
import com.guanghe.fs.storage.plugin.boot.StoragePluginRegistry;
import com.guanghe.fs.storage.plugin.core.context.StoragePlatformContextHolder;
import com.guanghe.fs.storage.plugin.core.dto.StoragePluginMetadata;
import com.guanghe.fs.storage.plugin.core.utils.StorageUtils;
import com.guanghe.fs.storage.service.StoragePlatformService;
import com.guanghe.fs.storage.service.StorageSettingService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import io.github.linpeilie.Converter;
import tools.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static com.guanghe.fs.storage.domain.table.StorageSettingTableDef.STORAGE_SETTING;

/**
 * 存储平台配置业务接口实现
 *
 * @Author: guangheUlti
 * @Date: 2024/10/25 14:38
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageSettingServiceImpl extends ServiceImpl<StorageSettingMapper, StorageSetting> implements StorageSettingService {

    private static final String SECRET_MASK = "********";

    private final Converter converter;

    private final StoragePlatformService storagePlatformService;

    private final StorageServiceFacade storageServiceFacade;
    
    private final StoragePluginRegistry storagePluginRegistry;

    @Override
    @Cacheable(value = "storageSettings", key = "'global'", unless = "#result == null || #result.isEmpty()")
    public List<StorageSettingUserVO> getStorageSettingsByUser() {
        // 存储配置为系统级资源，仅系统管理员可读写，无需再按归属过滤
        List<StorageSetting> storageSettings = this.list(
                new QueryWrapper()
                        .orderBy(STORAGE_SETTING.ENABLED.desc())
        );
        if (CollUtil.isEmpty(storageSettings)) {
            return new ArrayList<>();
        }
        return storageSettings.stream().map(storageSetting -> {
            StorageSettingUserVO vo = converter.convert(storageSetting, StorageSettingUserVO.class);
            vo.setConfigData(maskSensitiveConfig(storageSetting.getConfigData()));
            StoragePlatform storagePlatform = storagePlatformService.getStoragePlatformByIdentifier(storageSetting.getPlatformIdentifier());
            StoragePlatformVO storagePlatformVO = converter.convert(storagePlatform, StoragePlatformVO.class);
            vo.setStoragePlatform(storagePlatformVO);
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    @Cacheable(value = "storageActivePlatforms", key = "'global'", unless = "#result == null || #result.isEmpty()")
    public List<StorageActivePlatformsVO> getActiveStoragePlatforms() {
        StorageSetting storageSetting = this.getOne(
                new QueryWrapper().where(STORAGE_SETTING.ENABLED.eq(CommonConstant.Y))
        );
        List<StorageActivePlatformsVO> result = new ArrayList<>();
        // 添加默认本地存储平台
        StorageActivePlatformsVO localInstance = new StorageActivePlatformsVO();
        StoragePluginMetadata localMetadata = storagePluginRegistry.getMetadata(StorageUtils.LOCAL_PLATFORM_IDENTIFIER);
        localInstance.setSettingId(StorageUtils.LOCAL_PLATFORM_IDENTIFIER);
        localInstance.setPlatformIdentifier(StorageUtils.LOCAL_PLATFORM_IDENTIFIER);
        if (localMetadata != null) {
            localInstance.setPlatformIcon(localMetadata.getIcon());
            localInstance.setPlatformName(localMetadata.getName());
        } else {
            // 回退到默认值
            localInstance.setPlatformIcon("icon-bendicunchu1");
            localInstance.setPlatformName(I18nUtils.getMessage("storage.local.name"));
        }
        localInstance.setIsEnabled(true);
        localInstance.setRemark(I18nUtils.getMessage("storage.system.default"));
        if (storageSetting != null) {
            localInstance.setIsEnabled(false);
            StoragePlatform storagePlatform = storagePlatformService.getStoragePlatformByIdentifier(storageSetting.getPlatformIdentifier());
            StorageActivePlatformsVO vo = new StorageActivePlatformsVO();
            vo.setSettingId(storageSetting.getId());
            vo.setPlatformIdentifier(storageSetting.getPlatformIdentifier());
            if (storagePlatform != null) {
                vo.setPlatformIcon(storagePlatform.getIcon());
                vo.setPlatformName(storagePlatform.getName());
            }
            vo.setRemark(storageSetting.getRemark());
            vo.setCreatedAt(storageSetting.getCreatedAt());
            vo.setUpdatedAt(storageSetting.getUpdatedAt());
            vo.setIsEnabled(true);
            result.add(vo);
        }
        result.add(localInstance);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Caching(evict = {
            @CacheEvict(value = "storageSettings", key = "'global'"),
            @CacheEvict(value = "storageActivePlatforms", key = "'global'")
    })
    public void enableOrDisableStoragePlatform(String settingId, Integer action) {
        StorageSetting storageSetting = this.getById(settingId);
        if (storageSetting == null) {
            throw new BusinessException(I18nUtils.getMessage("storage.config.not.exist"));
        }

        Integer newStatus = action == 0 ? CommonConstant.N : CommonConstant.Y;

        if (newStatus.equals(CommonConstant.Y)) {
            // 全系统同时只允许一个存储配置处于启用状态
            List<StorageSetting> storageSettings = this.list(
                    new QueryWrapper()
                            .where(STORAGE_SETTING.ENABLED.eq(CommonConstant.Y))
            );
            storageSettings.forEach(s -> s.setEnabled(CommonConstant.N));
            this.updateBatch(storageSettings);
        }
        storageSetting.setEnabled(newStatus);
        this.updateById(storageSetting);

        if (newStatus.equals(CommonConstant.N)) {
            storageServiceFacade.removeInstance(settingId);
        } else {
            storageServiceFacade.refreshInstance(settingId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    @Caching(evict = {
            @CacheEvict(value = "storageSettings", key = "'global'"),
            @CacheEvict(value = "storageActivePlatforms", key = "'global'")
    })
    public void addStorageSetting(StorageSettingAddCmd cmd) {
        boolean exists = this.checkDuplicateConfig(
                cmd.getPlatformIdentifier(),
                cmd.getConfigData()
        );
        if (exists) {
            throw new BusinessException(I18nUtils.getMessage("storage.config.duplicate"));
        }
        StorageSetting storageSetting = new StorageSetting();
        storageSetting.setPlatformIdentifier(cmd.getPlatformIdentifier());
        storageSetting.setConfigData(cmd.getConfigData());
        storageSetting.setEnabled(CommonConstant.N);
        storageSetting.setRemark(cmd.getRemark());
        this.save(storageSetting);
        log.info("新增存储配置成功: settingId={}, platform={}",
                storageSetting.getId(),
                cmd.getPlatformIdentifier());
    }

    /**
     * 检查是否存在重复配置
     */
    private boolean checkDuplicateConfig(String platformIdentifier,
                                         String configData) {
        List<StorageSetting> existingSettings = this.list(new QueryWrapper()
                .where(STORAGE_SETTING.PLATFORM_IDENTIFIER.eq(platformIdentifier))
        );
        // 将新配置转为标准JSON格式
        String normalizedNewConfig = JsonUtils.normalizeJson(configData);
        // 遍历现有配置，比较JSON内容
        return existingSettings.stream()
                .anyMatch(setting -> {
                    String normalizedExisting = JsonUtils.normalizeJson(setting.getConfigData());
                    return normalizedNewConfig.equals(normalizedExisting);
                });
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    @Caching(evict = {
            @CacheEvict(value = "storageSettings", key = "'global'"),
            @CacheEvict(value = "storageActivePlatforms", key = "'global'")
    })
    public void editStorageSetting(StorageSettingEditCmd cmd) {
        StorageSetting storageSetting = this.getById(cmd.getSettingId());
        if (storageSetting == null) {
            throw new BusinessException(I18nUtils.getMessage("storage.config.not.exist"));
        }
        String mergedConfigData = mergeSensitiveConfig(storageSetting.getConfigData(), cmd.getConfigData());
        boolean exists = this.checkDuplicateConfigForUpdate(
                storageSetting.getPlatformIdentifier(),
                mergedConfigData,
                cmd.getSettingId()
        );
        if (exists) {
            throw new BusinessException(I18nUtils.getMessage("storage.config.duplicate"));
        }
        storageSetting.setConfigData(mergedConfigData);
        storageSetting.setRemark(cmd.getRemark());
        this.updateById(storageSetting);
        // 刷新缓存
        storageServiceFacade.refreshInstance(cmd.getSettingId());
    }

    /**
     * 检查更新时是否存在重复配置（排除自身）
     */
    private boolean checkDuplicateConfigForUpdate(String platformIdentifier,
                                                  String configData,
                                                  String excludeId) {
        List<StorageSetting> existingSettings = this.list(new QueryWrapper()
                .where(STORAGE_SETTING.PLATFORM_IDENTIFIER.eq(platformIdentifier)
                        .and(STORAGE_SETTING.ID.ne(excludeId))
                )
        );
        String normalizedNewConfig = JsonUtils.normalizeJson(configData);
        return existingSettings.stream()
                .anyMatch(setting -> {
                    String normalizedExisting = JsonUtils.normalizeJson(setting.getConfigData());
                    return normalizedNewConfig.equals(normalizedExisting);
                });
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    @Caching(evict = {
            @CacheEvict(value = "storageSettings", key = "'global'"),
            @CacheEvict(value = "storageActivePlatforms", key = "'global'")
    })
    public void deleteStorageSettingById(String id) {
        StorageSetting storageSetting = this.getById(id);

        if (storageSetting == null) {
            throw new BusinessException(I18nUtils.getMessage("storage.config.not.exist"));
        }
        String cacheSettingId = StoragePlatformContextHolder.getConfigId();
        if (id.equals(cacheSettingId)) {
            throw new BusinessException(I18nUtils.getMessage("storage.config.in.use"));
        }

        this.removeById(id);
        storageServiceFacade.removeInstance(id);

        log.info("存储配置已删除并移除缓存: settingId={}", id);
    }

    @Override
    public List<StorageSetting> listByPlatformIdentifier(String platformIdentifier) {
        return this.list(
                new QueryWrapper()
                        .where(STORAGE_SETTING.PLATFORM_IDENTIFIER.eq(platformIdentifier))
                        .and(STORAGE_SETTING.ENABLED.eq(CommonConstant.Y))
        );
    }

    private String maskSensitiveConfig(String configData) {
        Map<String, Object> config = parseConfig(configData);
        config.replaceAll((key, value) -> isSensitiveKey(key) && value != null ? SECRET_MASK : value);
        return JsonUtils.toJsonString(config);
    }

    private String mergeSensitiveConfig(String existingConfigData, String incomingConfigData) {
        Map<String, Object> existing = parseConfig(existingConfigData);
        Map<String, Object> incoming = parseConfig(incomingConfigData);
        existing.forEach((key, value) -> {
            Object incomingValue = incoming.get(key);
            if (isSensitiveKey(key)
                    && (incomingValue == null
                    || String.valueOf(incomingValue).isBlank()
                    || SECRET_MASK.equals(String.valueOf(incomingValue)))) {
                incoming.put(key, value);
            }
        });
        return JsonUtils.toJsonString(incoming);
    }

    private Map<String, Object> parseConfig(String configData) {
        Map<String, Object> config = JsonUtils.parseObject(
                configData,
                new TypeReference<LinkedHashMap<String, Object>>() { }
        );
        return config == null ? new LinkedHashMap<>() : config;
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("token")
                || (normalized.contains("access") && normalized.contains("key"));
    }
}
