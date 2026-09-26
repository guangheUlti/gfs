package com.guanghe.fs.file.integration;

import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.storage.facade.StorageServiceFacade;
import com.guanghe.fs.storage.service.StorageSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * 存储集成装配
 * <p>
 * fs-storage 不能反向依赖 fs-file：删除存储配置前的文件占用检查通过谓词注入到
 * StorageSettingServiceImpl（该存储下仍有文件索引时禁止删除，防止文件永久失联）。
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
    private final StorageServiceFacade storageServiceFacade;

    /**
     * 注册存储删除占用检查：FILE_INFO 按 STORAGE_PLATFORM_SETTING_ID 计数（不含回收站）。
     * 本地挂载（直读）存储例外：其索引行只是浏览时的目录快照缓存（真实 FS 为唯一真相源），
     * 不构成文件占用，删除配置时由挂载卸载回调清理，故放行删除。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerStorageInUseChecker() {
        if (storageSettingService instanceof com.guanghe.fs.storage.service.impl.StorageSettingServiceImpl impl) {
            impl.setStorageInUseChecker(settingId ->
                    fileInfoService.countByStorageSettingId(settingId) > 0
                            && !isDirectAccessStorage(settingId));
            log.info("存储删除占用检查注册完成");
        }
    }

    /** 是否为本地挂载（直读）存储：能力位判断，实例不可用时按保守处理（仍有索引则阻止删除） */
    private boolean isDirectAccessStorage(String settingId) {
        try {
            return storageServiceFacade.getStorageService(settingId).isDirectAccess();
        } catch (Exception e) {
            return false;
        }
    }
}
