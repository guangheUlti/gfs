package com.guanghe.fs.storage.service;

import com.guanghe.fs.storage.domain.StoragePlatform;
import com.guanghe.fs.storage.domain.StorageSetting;
import com.guanghe.fs.storage.facade.StorageServiceFacade;
import com.guanghe.fs.storage.mapper.StoragePlatformMapper;
import com.guanghe.fs.storage.mapper.StorageSettingMapper;
import com.guanghe.fs.storage.plugin.boot.StoragePluginRegistry;
import com.guanghe.fs.storage.plugin.core.dto.StoragePluginMetadata;
import com.guanghe.fs.storage.plugin.core.utils.StorageUtils;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.guanghe.fs.storage.domain.table.StorageSettingTableDef.STORAGE_SETTING;

/**
 * 存储平台自动注册服务
 * 在应用启动时自动扫描插件并同步到数据库
 *
 * Local 插件同样注册平台行（携带配置Schema），支持添加多个不同根目录的本地存储实例；
 * 其内置配置行固定 id="Local"，由 StorageSettingServiceImpl 懒建
 *
 * @Author: guangheUlti
 * @Date: 2026/01/12 22:06
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoragePlatformAutoRegister implements ApplicationRunner {

    private final StoragePluginRegistry pluginRegistry;
    private final StoragePlatformMapper storagePlatformMapper;
    private final StorageSettingMapper storageSettingMapper;
    private final ObjectMapper objectMapper;
    private final StorageSettingService storageSettingService;
    private final StorageServiceFacade storageServiceFacade;

    @Override
    public void run(@NonNull ApplicationArguments args) {
        try {
            syncPluginsToDatabase();
        } catch (DataAccessException e) {
            log.warn("数据库不可用，跳过插件同步: {}", e.getMessage());
        } catch (Exception e) {
            log.error("插件同步失败", e);
        }
    }


    /**
     * 同步插件信息到数据库
     */
    public void syncPluginsToDatabase() {
        log.info("开始同步存储插件到数据库...");

        Collection<StoragePluginMetadata> allMetadata = pluginRegistry.getAllMetadata();
        int insertCount = 0;
        int updateCount = 0;
        int skipCount = 0;

        for (StoragePluginMetadata metadata : allMetadata) {
            try {
                SyncResult result = syncSinglePlugin(metadata);
                switch (result) {
                    case INSERTED -> insertCount++;
                    case UPDATED -> updateCount++;
                    case SKIPPED -> skipCount++;
                }
            } catch (Exception e) {
                log.error("同步插件失败: {}", metadata.getIdentifier(), e);
            }
        }

        log.info("存储插件同步完成，新增: {}, 更新: {}, 跳过: {}",
                insertCount, updateCount, skipCount);

        cleanupOrphanPlatforms(allMetadata);
    }

    /**
     * 清理孤儿平台行：平台行的真相来源是代码插件清单，
     * 插件被移除后残留的平台行（及其全部配置）一并清理，避免前端下拉出现已下线平台。
     */
    private void cleanupOrphanPlatforms(Collection<StoragePluginMetadata> allMetadata) {
        Set<String> activeIdentifiers = allMetadata.stream()
                .map(StoragePluginMetadata::getIdentifier)
                .collect(Collectors.toSet());
        List<StoragePlatform> allPlatforms = storagePlatformMapper.selectListByQuery(QueryWrapper.create());
        for (StoragePlatform row : allPlatforms) {
            if (StorageUtils.LOCAL_PLATFORM_IDENTIFIER.equals(row.getIdentifier())
                    || activeIdentifiers.contains(row.getIdentifier())) {
                continue;
            }
            // 先删引用该平台的配置（含禁用的，避免 getStorageSettingsByUser 组装 VO 时 NPE），再删平台行
            try {
                List<StorageSetting> settings = storageSettingMapper.selectListByQuery(
                        QueryWrapper.create().where(STORAGE_SETTING.PLATFORM_IDENTIFIER.eq(row.getIdentifier())));
                for (StorageSetting setting : settings) {
                    storageServiceFacade.removeInstance(setting.getId());
                    storageSettingService.removeById(setting.getId());
                }
                storagePlatformMapper.deleteById(row.getId());
                log.warn("清理孤儿存储平台: {}", row.getIdentifier());
            } catch (Exception e) {
                log.error("清理孤儿存储平台失败: {}", row.getIdentifier(), e);
            }
        }
    }

    /**
     * 同步单个插件到数据库
     *
     * @param metadata 插件元数据
     * @return 同步结果
     */
    private SyncResult syncSinglePlugin(StoragePluginMetadata metadata) {
        StoragePlatform existing = storagePlatformMapper.selectByIdentifier(metadata.getIdentifier());

        if (existing == null) {
            // 新插件，插入记录
            StoragePlatform platform = new StoragePlatform();
            platform.setIdentifier(metadata.getIdentifier());
            platform.setName(metadata.getName());
            platform.setConfigScheme(validateSchema(metadata.getConfigSchema()));
            platform.setIcon(metadata.getIcon());
            platform.setLink(metadata.getLink());
            platform.setDesc(metadata.getDescription());
            platform.setIsDefault(Boolean.TRUE.equals(metadata.getIsDefault()) ? 1 : 0);

            storagePlatformMapper.insert(platform);
            log.debug("新增存储平台: {}", metadata.getIdentifier());
            return SyncResult.INSERTED;
        }

        // 检查是否需要更新
        if (needsUpdate(existing, metadata)) {
            // 更新记录，但保留 is_default 字段
            existing.setName(metadata.getName());
            existing.setConfigScheme(validateSchema(metadata.getConfigSchema()));
            existing.setIcon(metadata.getIcon());
            existing.setLink(metadata.getLink());
            existing.setDesc(metadata.getDescription());
            // 注意：不更新 is_default，保留管理员设置

            storagePlatformMapper.update(existing);
            log.debug("更新存储平台: {}", metadata.getIdentifier());
            return SyncResult.UPDATED;
        }

        return SyncResult.SKIPPED;
    }


    /**
     * 检查是否需要更新数据库记录
     *
     * @param existing 现有数据库记录
     * @param metadata 插件元数据
     * @return true-需要更新
     */
    private boolean needsUpdate(StoragePlatform existing, StoragePluginMetadata metadata) {
        return !Objects.equals(existing.getName(), metadata.getName())
                || !Objects.equals(existing.getConfigScheme(), validateSchema(metadata.getConfigSchema()))
                || !Objects.equals(existing.getIcon(), metadata.getIcon())
                || !Objects.equals(existing.getLink(), metadata.getLink())
                || !Objects.equals(existing.getDesc(), metadata.getDescription());
    }

    /**
     * 验证 JSON Schema 格式有效性
     * 无效时返回空对象 "{}"
     *
     * @param schema JSON Schema 字符串
     * @return 有效的 JSON 字符串，无效时返回 "{}"
     */
    private String validateSchema(String schema) {
        if (schema == null || schema.isBlank()) {
            return "{}";
        }
        try {
            // 尝试解析 JSON 以验证格式
            objectMapper.readTree(schema);
            return schema;
        } catch (Exception e) {
            log.warn("无效的配置Schema，使用空对象: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * 同步结果枚举
     */
    private enum SyncResult {
        /** 新增记录 */
        INSERTED,
        /** 更新记录 */
        UPDATED,
        /** 跳过（无变化） */
        SKIPPED
    }
}
