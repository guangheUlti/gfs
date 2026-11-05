package com.guanghe.fs.file.integration;

import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.storage.domain.vo.StorageFileUsageVO;
import com.guanghe.fs.storage.service.StorageSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * 存储集成装配
 * <p>
 * fs-storage 不能反向依赖 fs-file：删除存储配置前的占用数量统计通过函数注入到
 * StorageSettingServiceImpl，供前端确认弹窗展示。
 *
 * @Author: guangheUlti
 * @Date: 2026/09/13
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class StorageIntegrationConfig {

    private final StorageSettingService storageSettingService;
    private final FileInfoService fileInfoService;

    /**
     * 注册存储占用统计：FILE_INFO 按 STORAGE_PLATFORM_SETTING_ID 计数（含回收站）。
     * 这些记录只是 GFS 侧的网盘索引，删除存储配置时会被清理，真实文件不受影响。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerStorageFileUsageProvider() {
        if (storageSettingService instanceof com.guanghe.fs.storage.service.impl.StorageSettingServiceImpl impl) {
            impl.setStorageFileUsageProvider(settingId -> new StorageFileUsageVO(
                    fileInfoService.countAllByStorageSettingId(settingId)));
            log.info("存储占用统计注册完成");
        }
    }
}
