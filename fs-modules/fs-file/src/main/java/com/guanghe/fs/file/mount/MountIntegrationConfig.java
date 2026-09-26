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
    private final MountWatchService mountWatchService;

    /**
     * 注册挂载变更回调（fs-file 启动后注入到 fs-storage）：
     * - 卸载：删除挂载设置时清理网盘索引（绝不碰真实文件），持挂载锁与扫描/写穿透互斥（8.4-⑮），并停止实时监听与调度状态；
     * - 配置变更（新增启用/编辑保存）：异步扫描一次使文件页立即可见，并按新配置对齐实时监听
     *   （开关切换/根路径变更 → stop+重启 watch；扫描完成时也会收敛一次，此处先行保证编辑后立即生效）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerUnmountHook() {
        if (storageSettingService instanceof com.guanghe.fs.storage.service.impl.StorageSettingServiceImpl impl) {
            impl.setMountUnmountConsumer(settingId -> {
                mountWatchService.stopWatch(settingId);
                mountManager.locks().callWithLock(settingId, () ->
                        mountPointService.unmount(settingId));
            });
            impl.setMountConfigChangedConsumer(this::onMountConfigChanged);
            // 实时监听对账回调：防抖窗口到期后异步扫描（与手动/启用触发同一串行线程池，天然合并多次触发；
            // userId=null：对账不需要懒建挂载点——监听在跑说明挂载点早已存在）
            mountWatchService.setReconcileCallback(settingId -> mountScanService.scanSettingAsync(settingId, null));
            log.info("挂载卸载/配置变更回调注册完成");
        }
    }

    /** 配置变更（启用/编辑保存/禁用）入口：当前 HTTP 线程持有登录态，捕获 userId 供挂载点懒创建 */
    private void onMountConfigChanged(String settingId) {
        // 先停旧监听（幂等）：覆盖禁用、关闭开关、改根路径场景；期望开启的场景由扫描完成后的 alignWatch 重新拉起
        mountWatchService.stopWatch(settingId);
        String userId = null;
        try {
            userId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsString();
        } catch (Exception ignored) {
            // 无登录态（系统内部触发）时传 null，扫描器退化为「挂载点缺失则跳过本轮」
        }
        mountScanService.scanSettingAsync(settingId, userId);
    }
}
