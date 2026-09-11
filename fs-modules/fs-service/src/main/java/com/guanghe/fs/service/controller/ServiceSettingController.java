package com.guanghe.fs.service.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.framework.common.domain.Result;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.service.domain.ServiceSetting;
import com.guanghe.fs.service.service.ServiceSettingService;
import com.guanghe.fs.service.domain.ServiceSettingVO;
import com.guanghe.fs.service.domain.UpdateServiceSettingCmd;
import com.guanghe.fs.service.manager.ProtocolServiceManager;
import com.mybatisflex.core.query.QueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.guanghe.fs.service.domain.table.ServiceSettingTableDef.SERVICE_SETTING;

/**
 * 对外文件服务管理 API（挂 /apis/service/**，管理员权限 SERVICE_MANAGE）
 */
@Slf4j
@Tag(name = "对外文件服务")
@RestController
@RequestMapping("/apis/service")
@RequiredArgsConstructor
public class ServiceSettingController {

    private final ServiceSettingService serviceSettingService;
    private final ProtocolServiceManager protocolServiceManager;

    @Operation(summary = "全部服务配置 + 实时运行状态")
    @GetMapping("/list")
    @SaCheckPermission(com.guanghe.fs.system.constant.UserPermissions.SERVICE_MANAGE)
    public Result<List<ServiceSettingVO>> list() {
        List<ServiceSetting> settings = serviceSettingService.list(
                new QueryWrapper().orderBy(SERVICE_SETTING.SERVICE_TYPE.asc()));
        List<ServiceSettingVO> result = new ArrayList<>();
        for (ServiceSetting setting : settings) {
            ServiceSettingVO vo = new ServiceSettingVO();
            vo.setServiceType(setting.getServiceType());
            vo.setEnabled(setting.getEnabled() != null && setting.getEnabled() == 1);
            vo.setPort(setting.getPort());
            vo.setBindAddress(setting.getBindAddress());
            vo.setUpdatedAt(setting.getUpdatedAt());
            ProtocolServiceManager.ServiceRuntime runtime = protocolServiceManager.status(setting.getServiceType());
            vo.setStatus(runtime.status());
            vo.setError(runtime.error());
            result.add(vo);
        }
        return Result.ok(result);
    }

    @Operation(summary = "保存配置并热生效")
    @PutMapping("/{type}/config")
    @SaCheckPermission(com.guanghe.fs.system.constant.UserPermissions.SERVICE_MANAGE)
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> updateConfig(@PathVariable("type") String type,
                                     @RequestBody UpdateServiceSettingCmd cmd) {
        ServiceSetting setting = requireByType(type);
        validate(type, cmd);

        setting.setEnabled(Boolean.TRUE.equals(cmd.getEnabled()) ? 1 : 0);
        if (cmd.getPort() != null) {
            setting.setPort(cmd.getPort());
        }
        if (StrUtil.isNotBlank(cmd.getBindAddress())) {
            setting.setBindAddress(cmd.getBindAddress().trim());
        }
        setting.setUpdatedAt(LocalDateTime.now());
        serviceSettingService.updateById(setting);

        // 热生效：启动失败不抛给保存动作（失败状态经 /list 返回）
        protocolServiceManager.apply(type, setting);
        log.info("对外文件服务配置已更新: type={}, enabled={}", type, setting.getEnabled());
        return Result.ok();
    }

    @Operation(summary = "start / stop / restart")
    @PostMapping("/{type}/{action}")
    @SaCheckPermission(com.guanghe.fs.system.constant.UserPermissions.SERVICE_MANAGE)
    public Result<Void> action(@PathVariable("type") String type, @PathVariable("action") String action) {
        ServiceSetting setting = requireByType(type);
        switch (action) {
            case "start" -> {
                setting.setEnabled(1);
                setting.setUpdatedAt(LocalDateTime.now());
                serviceSettingService.updateById(setting);
                protocolServiceManager.apply(type, setting);
            }
            case "stop" -> {
                setting.setEnabled(0);
                setting.setUpdatedAt(LocalDateTime.now());
                serviceSettingService.updateById(setting);
                protocolServiceManager.apply(type, setting);
            }
            case "restart" -> {
                // 语义：重启 = 停止（不动 enabled）后再拉起；SFTP 强停重建，WebDAV 状态重置
                if (ServiceSettingService.TYPE_SFTP.equals(type)) {
                    setting.setEnabled(setting.getEnabled() == null || setting.getEnabled() != 1 ? 0 : 1);
                    protocolServiceManager.apply(type, setting);
                    setting.setEnabled(1);
                    setting.setUpdatedAt(LocalDateTime.now());
                    serviceSettingService.updateById(setting);
                    protocolServiceManager.apply(type, setting);
                } else {
                    protocolServiceManager.apply(type, setting);
                }
            }
            default -> throw new BusinessException(I18nUtils.getMessage("service.type.invalid"));
        }
        return Result.ok();
    }

    private ServiceSetting requireByType(String type) {
        if (!ServiceSettingService.isValidType(type)) {
            throw new BusinessException(I18nUtils.getMessage("service.type.invalid"));
        }
        ServiceSetting setting = serviceSettingService.getOne(
                new QueryWrapper().where(SERVICE_SETTING.SERVICE_TYPE.eq(type)));
        if (setting == null) {
            throw new BusinessException(I18nUtils.getMessage("service.not.found"));
        }
        return setting;
    }

    private void validate(String type, UpdateServiceSettingCmd cmd) {
        if (cmd.getEnabled() == null) {
            throw new BusinessException(I18nUtils.getMessage("service.port.invalid"));
        }
        if (ServiceSettingService.TYPE_SFTP.equals(type) && cmd.getPort() != null) {
            if (cmd.getPort() < 1024 || cmd.getPort() > 65535) {
                throw new BusinessException(I18nUtils.getMessage("service.port.invalid"));
            }
        }
    }
}
