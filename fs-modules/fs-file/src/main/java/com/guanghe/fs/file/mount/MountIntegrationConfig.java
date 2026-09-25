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
    private final MountScanService mountScanService;

    /**
     * 注册挂载卸载/启用回调：
     * - 卸载：删除挂载设置时清理网盘索引（绝不碰真实文件），持挂载锁与扫描/写穿透互斥（8.4-⑮）；
     * - 启用：启用挂载式配置后自动异步扫描一次，文件页立即可见（无需等定时器或手动扫描）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerUnmountHook() {
        if (storageSettingService instanceof com.guanghe.fs.storage.service.impl.StorageSettingServiceImpl impl) {
            impl.setMountUnmountConsumer(settingId ->
                    mountManager.locks().callWithLock(settingId, () ->
                            mountPointService.unmount(settingId)));
            impl.setMountEnabledConsumer(this::scanOnEnabled);
            log.info("挂载卸载/启用回调注册完成");
        }
    }

    /** 启用挂载配置后异步扫描一次；当前 HTTP 线程持有登录态，捕获 userId 供挂载点懒创建 */
    private void scanOnEnabled(String settingId) {
        String userId = null;
        try {
            userId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsString();
        } catch (Exception ignored) {
            // 无登录态（系统内部触发）时传 null，扫描器退化为「挂载点缺失则跳过本轮」
        }
        mountScanService.scanSettingAsync(settingId, userId);
    }
}
