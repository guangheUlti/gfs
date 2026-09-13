package com.guanghe.fs.storage.service.impl;

import com.guanghe.fs.framework.common.constant.CommonConstant;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.ErrorMessageUtils;
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
import com.guanghe.fs.storage.plugin.boot.LocalStorageManager;
import com.guanghe.fs.storage.plugin.boot.StoragePluginRegistry;
import com.guanghe.fs.storage.plugin.core.IStorageOperationService;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static com.guanghe.fs.storage.domain.table.StorageSettingTableDef.STORAGE_SETTING;
import static com.guanghe.fs.storage.plugin.core.utils.StorageUtils.platformDisplayOrder;

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

    /**
     * 挂载卸载回调（由 fs-file 在启动时注入，避免 fs-storage 反向依赖 fs-file）。
     * 参数为挂载设置 ID；回调内负责持挂载锁并删除网盘索引。
     */
    private volatile java.util.function.Consumer<String> mountUnmountConsumer;

    public void setMountUnmountConsumer(java.util.function.Consumer<String> consumer) {
        this.mountUnmountConsumer = consumer;
    }

    /**
     * 存储占用检查（由 fs-file 在启动时注入，避免 fs-storage 反向依赖 fs-file）。
     * 返回 true 表示该配置下仍有文件索引，禁止删除。
     */
    private volatile java.util.function.Predicate<String> storageInUseChecker;

    public void setStorageInUseChecker(java.util.function.Predicate<String> checker) {
        this.storageInUseChecker = checker;
    }

    private final Converter converter;

    private final StoragePlatformService storagePlatformService;

    private final StorageServiceFacade storageServiceFacade;

    private final StoragePluginRegistry storagePluginRegistry;

    private final LocalStorageManager localStorageManager;

    private final CacheManager cacheManager;

    /**
     * 启动后装配内置本地存储：
     * 1. 懒建固定 id="Local" 的配置行（首次启动用 application.yml 默认值落库）
     * 2. 向 LocalStorageManager 注入 DB 属性覆盖，使内置实例按 DB 配置的根目录工作
     * 3. 清空本服务列表缓存，避免升级后命中旧版本写入的过期结构（TTL 1h 内不自愈）
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initBuiltinLocalSetting() {
        try {
            ensureBuiltinLocalRow();
            localStorageManager.setPropertiesOverride(this::loadBuiltinLocalProperties);
            List.of("storageSettings", "storageActivePlatforms").forEach(name -> {
                var cache = cacheManager.getCache(name);
                if (cache != null) {
                    cache.clear();
                }
            });
            log.info("内置本地存储配置装配完成: basePath={}",
                    localStorageManager.getEffectiveProperties().get("basePath"));
        } catch (Exception e) {
            log.warn("内置本地存储配置装配失败，沿用 application.yml 默认值: {}", e.getMessage());
        }
    }

    /**
     * 内置 Local 配置行懒建：作为其根目录等属性的持久化载体
     */
    private void ensureBuiltinLocalRow() {
        if (this.getById(StorageUtils.LOCAL_PLATFORM_IDENTIFIER) != null) {
            return;
        }
        StorageSetting local = new StorageSetting();
        local.setId(StorageUtils.LOCAL_PLATFORM_IDENTIFIER);
        local.setPlatformIdentifier(StorageUtils.LOCAL_PLATFORM_IDENTIFIER);
        local.setConfigData(JsonUtils.toJsonString(localStorageManager.getDefaultProperties()));
        local.setEnabled(CommonConstant.Y);
        local.setRemark(I18nUtils.getMessage("storage.system.default"));
        this.save(local);
        log.info("内置本地存储配置行已初始化");
    }

    /**
     * 内置 Local 的 DB 覆盖属性（空白项剔除，交给 application.yml 默认值兜底）
     */
    private Map<String, Object> loadBuiltinLocalProperties() {
        StorageSetting local = this.getById(StorageUtils.LOCAL_PLATFORM_IDENTIFIER);
        if (local == null || local.getConfigData() == null || local.getConfigData().isBlank()) {
            return Map.of();
        }
        Map<String, Object> props = parseConfig(local.getConfigData());
        props.entrySet().removeIf(e -> e.getValue() == null || String.valueOf(e.getValue()).isBlank());
        return props;
    }

    @Override
    @Cacheable(value = "storageSettings", key = "'global'", unless = "#result == null || #result.isEmpty()")
    public List<StorageSettingUserVO> getStorageSettingsByUser() {
        // 存储配置为系统级资源，仅系统管理员可读写，无需再按归属过滤；内置 Local 行单独组装、固定首位
        List<StorageSetting> storageSettings = this.list(
                new QueryWrapper()
                        .where(STORAGE_SETTING.ID.ne(StorageUtils.LOCAL_PLATFORM_IDENTIFIER))
                        .orderBy(STORAGE_SETTING.ENABLED.desc())
        );
        List<StorageSettingUserVO> result = new ArrayList<>();
        result.add(buildLocalSettingVO());
        storageSettings.stream().map(storageSetting -> {
            StorageSettingUserVO vo = converter.convert(storageSetting, StorageSettingUserVO.class);
            vo.setConfigData(maskSensitiveConfig(storageSetting.getConfigData()));
            StoragePlatform storagePlatform = storagePlatformService.getStoragePlatformByIdentifier(storageSetting.getPlatformIdentifier());
            StoragePlatformVO storagePlatformVO = converter.convert(storagePlatform, StoragePlatformVO.class);
            vo.setStoragePlatform(storagePlatformVO);
            return vo;
        }).forEach(result::add);
        // 统一展示顺序：按平台权重排序，同平台内启用优先
        result.sort(Comparator
                .comparingInt((StorageSettingUserVO vo) -> platformDisplayOrder(
                        vo.getStoragePlatform() != null ? vo.getStoragePlatform().getIdentifier() : null))
                .thenComparing(vo -> CommonConstant.Y.equals(vo.getEnabled()) ? 0 : 1));
        return result;
    }

    /**
     * 构造内置本地存储的展示行：id 固定为 "Local"，恒启用，携带生效配置与配置Schema 供编辑表单使用
     */
    private StorageSettingUserVO buildLocalSettingVO() {
        StorageSettingUserVO vo = new StorageSettingUserVO();
        vo.setId(StorageUtils.LOCAL_PLATFORM_IDENTIFIER);
        vo.setEnabled(CommonConstant.Y);
        vo.setRemark(I18nUtils.getMessage("storage.system.default"));
        // 加密口令属敏感字段，展示侧与其它平台配置一样打掩码；编辑提交空/掩码时按“不变”回填
        vo.setConfigData(maskSensitiveConfig(JsonUtils.toJsonString(localStorageManager.getEffectiveProperties())));
        StoragePluginMetadata localMetadata = storagePluginRegistry.getMetadata(StorageUtils.LOCAL_PLATFORM_IDENTIFIER);
        StoragePlatformVO platform = new StoragePlatformVO();
        platform.setIdentifier(StorageUtils.LOCAL_PLATFORM_IDENTIFIER);
        platform.setName(I18nUtils.getMessage("storage.local.name"));
        platform.setDesc(I18nUtils.getMessage("storage.local.desc"));
        platform.setIcon("icon-bendicunchu1");
        platform.setConfigScheme(localMetadata != null && localMetadata.getConfigSchema() != null
                ? localMetadata.getConfigSchema()
                : "[]");
        vo.setStoragePlatform(platform);
        return vo;
    }

    @Override
    @Cacheable(value = "storageActivePlatforms", key = "'global'", unless = "#result == null || #result.isEmpty()")
    public List<StorageActivePlatformsVO> getActiveStoragePlatforms() {
        List<StorageActivePlatformsVO> result = new ArrayList<>();
        // 内置本地存储恒可用，固定首位
        StorageActivePlatformsVO localInstance = new StorageActivePlatformsVO();
        StoragePluginMetadata localMetadata = storagePluginRegistry.getMetadata(StorageUtils.LOCAL_PLATFORM_IDENTIFIER);
        localInstance.setSettingId(StorageUtils.LOCAL_PLATFORM_IDENTIFIER);
        localInstance.setPlatformIdentifier(StorageUtils.LOCAL_PLATFORM_IDENTIFIER);
        localInstance.setPlatformIcon(localMetadata != null
                ? localMetadata.getIcon()
                : "icon-bendicunchu1");
        localInstance.setPlatformName(I18nUtils.getMessage("storage.local.name"));
        localInstance.setIsEnabled(true);
        localInstance.setRemark(I18nUtils.getMessage("storage.system.default"));
        result.add(localInstance);

        // 多激活：全部启用行一并返回（内置 Local 行已单独组装）
        List<StorageSetting> enabledSettings = this.list(
                new QueryWrapper().where(STORAGE_SETTING.ENABLED.eq(CommonConstant.Y))
        );
        for (StorageSetting storageSetting : enabledSettings) {
            if (StorageUtils.LOCAL_PLATFORM_IDENTIFIER.equals(storageSetting.getId())) {
                continue;
            }
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
        // 统一展示顺序（内置 Local 权重最小，恒为首位）
        result.sort(Comparator.comparingInt(
                (StorageActivePlatformsVO vo) -> platformDisplayOrder(vo.getPlatformIdentifier())));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Caching(evict = {
            @CacheEvict(value = "storageSettings", key = "'global'"),
            @CacheEvict(value = "storageActivePlatforms", key = "'global'")
    })
    public void enableOrDisableStoragePlatform(String settingId, Integer action) {
        Integer newStatus = action == 0 ? CommonConstant.N : CommonConstant.Y;

        // 内置本地存储恒启用：重复启用直接成功，禁用一律拒绝
        if (StorageUtils.LOCAL_PLATFORM_IDENTIFIER.equals(settingId)) {
            if (CommonConstant.N.equals(newStatus)) {
                throw new BusinessException(I18nUtils.getMessage("storage.local.disable.forbidden"));
            }
            return;
        }

        StorageSetting storageSetting = this.getById(settingId);
        if (storageSetting == null) {
            throw new BusinessException(I18nUtils.getMessage("storage.config.not.exist"));
        }

        // 多激活：启用不再互斥清空其它启用行，可同时启用多个存储配置
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
        // 保存前真实连接测试：配置错误即拒绝保存，DB 无残留行
        testStorageConnection(cmd.getPlatformIdentifier(), cmd.getConfigData());
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
        if (StorageUtils.LOCAL_PLATFORM_IDENTIFIER.equals(cmd.getSettingId())) {
            editBuiltinLocalSetting(cmd);
            return;
        }
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
        // 用合并后的配置做真实连接测试（掩码占位符不会被当真实密码），失败整单回滚
        testStorageConnection(storageSetting.getPlatformIdentifier(), mergedConfigData);
        storageSetting.setConfigData(mergedConfigData);
        storageSetting.setRemark(cmd.getRemark());
        this.updateById(storageSetting);
        // 刷新缓存
        storageServiceFacade.refreshInstance(cmd.getSettingId());
    }

    /**
     * 内置本地存储编辑：更新固定 id="Local" 配置行的根目录等属性并重建内置实例。
     * 改根目录只影响新写入文件，既有文件索引仍指向旧根目录下的 objectKey，需自行迁移物理文件。
     */
    private void editBuiltinLocalSetting(StorageSettingEditCmd cmd) {
        Map<String, Object> incoming = parseConfig(cmd.getConfigData());
        Object basePath = incoming.get("basePath");
        if (basePath == null || String.valueOf(basePath).isBlank()) {
            throw new BusinessException(I18nUtils.getMessage("storage.local.basepath.required"));
        }
        StorageSetting local = this.getById(StorageUtils.LOCAL_PLATFORM_IDENTIFIER);
        if (local == null) {
            throw new BusinessException(I18nUtils.getMessage("storage.config.not.exist"));
        }
        // 敏感字段（加密口令）提交掩码/空白时保留 DB 旧值，与其它平台配置编辑同一规则
        Map<String, Object> merged = parseConfig(JsonUtils.toJsonString(incoming));
        merged = parseConfig(mergeSensitiveConfig(local.getConfigData(), JsonUtils.toJsonString(merged)));
        String mergedConfigData = JsonUtils.toJsonString(merged);
        // 与其它本地存储实例查重（同根目录的重复实例没有意义）
        if (checkDuplicateConfigForUpdate(StorageUtils.LOCAL_PLATFORM_IDENTIFIER, mergedConfigData,
                StorageUtils.LOCAL_PLATFORM_IDENTIFIER)) {
            throw new BusinessException(I18nUtils.getMessage("storage.config.duplicate"));
        }
        // 真实初始化验证根目录可创建/可写，失败整单回滚
        testStorageConnection(StorageUtils.LOCAL_PLATFORM_IDENTIFIER, mergedConfigData);
        local.setConfigData(mergedConfigData);
        local.setRemark(cmd.getRemark());
        this.updateById(local);
        // 重建内置单例：属性覆盖 supplier 惰性重读 DB 行
        localStorageManager.reset();
        log.info("内置本地存储配置已更新: basePath={}", basePath);
    }

    /**
     * 保存前存储连接测试
     * 用原型工厂创建配置化实例（validateConfig + initialize 真实建连），失败即抛业务异常拒绝保存。
     * configId 传 null 避免测试实例进入缓存；Local 平台的 initialize 会真实创建根目录，兼作目录校验。
     */
    private void testStorageConnection(String platformIdentifier, String configData) {
        StorageConfig cfg = StorageConfig.builder()
                .configId(null)
                .platformIdentifier(platformIdentifier)
                .properties(parseConfig(configData))
                .build();
        IStorageOperationService instance = null;
        try {
            instance = storagePluginRegistry.getPrototype(platformIdentifier)
                    .createConfiguredInstance(cfg);
        } catch (Exception e) {
            log.warn("存储连接测试失败: platform={}, error={}", platformIdentifier, e.getMessage());
            throw new BusinessException(I18nUtils.getMessage("storage.config.test.failed",
                    new Object[]{ErrorMessageUtils.extractUserFriendlyMessage(e)}));
        } finally {
            if (instance != null) {
                try {
                    instance.close();
                } catch (Exception ignore) {
                }
            }
        }
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
        // 内置本地存储是系统默认存储，禁止删除
        if (StorageUtils.LOCAL_PLATFORM_IDENTIFIER.equals(id)) {
            throw new BusinessException(I18nUtils.getMessage("storage.local.delete.forbidden"));
        }
        StorageSetting storageSetting = this.getById(id);

        if (storageSetting == null) {
            throw new BusinessException(I18nUtils.getMessage("storage.config.not.exist"));
        }
        String cacheSettingId = StoragePlatformContextHolder.getConfigId();
        if (id.equals(cacheSettingId)) {
            throw new BusinessException(I18nUtils.getMessage("storage.config.in.use"));
        }
        // 该存储下仍有文件索引时禁止删除，防止文件永久失联
        java.util.function.Predicate<String> inUseChecker = this.storageInUseChecker;
        if (inUseChecker != null && inUseChecker.test(id)) {
            throw new BusinessException(I18nUtils.getMessage("storage.delete.has.files"));
        }

        this.removeById(id);
        storageServiceFacade.removeInstance(id);

        // 挂载平台删除：卸载 = 只清网盘索引（含挂载点与回收站记录），绝不碰真实文件（8.4-⑧）
        if ("LocalMount".equals(storageSetting.getPlatformIdentifier())) {
            try {
                mountUnmountConsumer.accept(id);
            } catch (Exception e) {
                log.error("挂载卸载清理索引失败: settingId={}", id, e);
            }
        }

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
