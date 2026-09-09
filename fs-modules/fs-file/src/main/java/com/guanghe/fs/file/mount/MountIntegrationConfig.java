package com.guanghe.fs.file.mount;

import com.guanghe.fs.storage.service.StorageSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * 挂载集成装配
 * <p>
 * fs-storage 不能反向依赖 fs-file：挂载卸载的索引清理通过回调注入到 StorageSettingServiceImpl，
 * 挂载点懒创建通过 FileHomeServiceImpl 的根列表入口触发（此处在启动后注册钩子）。
 *
 * @Author: guangheUlti
 * @Date: 2026/09/09
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MountIntegrationConfig {

    private final StorageSettingService storageSettingService;
    private final MountPointService mountPointService;
    private final MountManager mountManager;

    /**
     * 注册挂载卸载回调：删除挂载设置时清理网盘索引（绝不碰真实文件）。
     * 回调持挂载锁，与扫描/写穿透互斥（8.4-⑮）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerUnmountHook() {
        if (storageSettingService instanceof com.guanghe.fs.storage.service.impl.StorageSettingServiceImpl impl) {
            impl.setMountUnmountConsumer(settingId ->
                    mountManager.locks().callWithLock(settingId, () ->
                            mountPointService.unmount(settingId)));
            log.info("挂载卸载回调注册完成");
        }
    }
}
