package com.guanghe.fs.file.mount;

import com.guanghe.fs.storage.service.StorageSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

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
     * 注册卸载回调（fs-file 启动后注入到 fs-storage）：
     * 删除挂载设置时清理网盘索引（绝不碰真实文件），持挂载锁与写穿透互斥。
     * <p>
     * HIGHEST_PRECEDENCE：已下线平台的残留配置清理（StoragePlatformAutoRegister，LOWEST_PRECEDENCE）
     * 依赖本回调清索引，顺序不能颠倒。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void registerUnmountHook() {
        if (storageSettingService instanceof com.guanghe.fs.storage.service.impl.StorageSettingServiceImpl impl) {
            impl.setMountUnmountConsumer(settingId ->
                    mountManager.locks().callWithLock(settingId, () ->
                            mountPointService.unmount(settingId)));
            log.info("挂载卸载回调注册完成");
        }
    }
}
