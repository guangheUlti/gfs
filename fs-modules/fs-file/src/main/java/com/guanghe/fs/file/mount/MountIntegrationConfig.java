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
 * fs-storage 不能反向依赖 fs-file：存储配置删除时的索引清理通过回调注入到 StorageSettingServiceImpl，
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
    private final StorageUnmountService storageUnmountService;
    private final MountManager mountManager;

    /**
     * 注册存储卸载回调（fs-file 启动后注入到 fs-storage）：
     * 删除存储配置时回收该存储在 GFS 侧的全部记录（索引、收藏、分享、收集、传输任务），绝不碰真实文件，
     * 持挂载锁与写穿透互斥。
     * <p>
     * HIGHEST_PRECEDENCE：已下线平台的残留配置清理（StoragePlatformAutoRegister，LOWEST_PRECEDENCE）
     * 依赖本回调，顺序不能颠倒。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void registerUnmountHook() {
        if (storageSettingService instanceof com.guanghe.fs.storage.service.impl.StorageSettingServiceImpl impl) {
            impl.setStorageUnmounter(settingId ->
                    mountManager.locks().callWithLock(settingId, () ->
                            storageUnmountService.unmount(settingId)));
            log.info("存储卸载清理回调注册完成");
        }
    }
}
