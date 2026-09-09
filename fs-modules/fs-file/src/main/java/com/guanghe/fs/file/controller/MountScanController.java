package com.guanghe.fs.file.mount;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.guanghe.fs.framework.common.domain.Result;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.storage.domain.StorageSetting;
import com.guanghe.fs.storage.service.StorageSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 挂载扫描控制器
 * 放在 fs-file 模块（fs-storage 不能反向依赖 fs-file）
 *
 * @Author: guangheUlti
 * @Date: 2026/09/09
 */
@Slf4j
@RestController
@RequestMapping("/apis/file/mount")
@RequiredArgsConstructor
@Tag(name = "挂载扫描", description = "本地目录挂载手动扫描接口")
public class MountScanController {

    private final MountScanService mountScanService;
    private final StorageSettingService storageSettingService;

    @PostMapping("/scan/{settingId}")
    @SaCheckPermission("storage:manage")
    @Operation(summary = "触发挂载扫描", description = "对指定挂载设置立即执行一次真实目录到网盘索引的同步")
    public Result<?> scan(@PathVariable String settingId) {
        StorageSetting setting = storageSettingService.getById(settingId);
        if (setting == null) {
            throw new BusinessException(I18nUtils.getMessage("storage.config.not.exist"));
        }
        // 异步触发会丢失错误反馈，这里同步执行但限制在挂载锁内（扫描与写穿透互斥）
        mountScanService.scanSetting(setting);
        return Result.ok();
    }
}
