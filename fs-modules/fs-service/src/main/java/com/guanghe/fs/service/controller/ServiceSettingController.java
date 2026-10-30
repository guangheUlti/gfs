package com.guanghe.fs.service.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.framework.common.domain.Result;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.service.domain.ServiceGuestUserVO;
import com.guanghe.fs.service.domain.ServiceSetting;
import com.guanghe.fs.service.service.ServiceSettingService;
import com.guanghe.fs.service.domain.ServiceSettingVO;
import com.guanghe.fs.service.domain.UpdateServiceSettingCmd;
import com.guanghe.fs.service.manager.ProtocolServiceManager;
import com.guanghe.fs.system.constant.UserStatus;
import com.guanghe.fs.system.domain.SysUser;
import com.guanghe.fs.system.mapper.SysUserMapper;
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
import static com.guanghe.fs.system.domain.table.SysUserTableDef.SYS_USER;

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
    private final com.guanghe.fs.storage.service.StorageSettingService storageSettingService;
    private final SysUserMapper userMapper;
    private final ProtocolServiceManager protocolServiceManager;
    private final com.guanghe.fs.service.manager.ProtocolReadOnlyService protocolReadOnlyService;
    private final com.guanghe.fs.service.manager.ExternalStorageScopeService externalStorageScopeService;
    private final com.guanghe.fs.service.spi.ExternalFileServiceRegistry externalFileServiceRegistry;

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
            vo.setReadOnly(ServiceSettingService.isReadOnly(setting));
            vo.setAllowGuest(ServiceSettingService.isGuestAllowed(setting));
            String guestUserId = ServiceSettingService.guestUserId(setting);
            vo.setGuestUserId(guestUserId);
            vo.setGuestUsername(usernameOf(guestUserId));
            vo.setGuestPassword(ServiceSettingService.isGuestPasswordRequired(setting));
            vo.setStorageSettingId(ServiceSettingService.storageSettingId(setting));
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
        // 只读模式对四条协议（webdav/sftp/ftp/oss）均生效，存 config_data 扩展 JSON：
        // 单个开关不值得为它加一列，也不用让已有安装手工执行 ALTER TABLE
        if (cmd.getReadOnly() != null) {
            setting.setConfigData(ServiceSettingService.mergeReadOnly(
                    setting.getConfigData(), Boolean.TRUE.equals(cmd.getReadOnly())));
        }
        // 游客访问只对 AList 有意义：TVBox/影视仓这类客户端不登录，匿名请求以「公开账号」身份只读
        if (ServiceSettingService.TYPE_ALIST.equals(type)) {
            if (cmd.getGuestUserId() != null) {
                setting.setConfigData(ServiceSettingService.mergeGuestUser(
                        setting.getConfigData(), StrUtil.trimToNull(cmd.getGuestUserId())));
            }
            if (cmd.getAllowGuest() != null) {
                setting.setConfigData(ServiceSettingService.mergeGuest(
                        setting.getConfigData(), Boolean.TRUE.equals(cmd.getAllowGuest())));
            }
            if (cmd.getGuestPassword() != null) {
                setting.setConfigData(ServiceSettingService.mergeGuestPassword(
                        setting.getConfigData(), Boolean.TRUE.equals(cmd.getGuestPassword())));
            }
            if (Boolean.TRUE.equals(cmd.getAllowGuest())) {
                requireNormalUser(ServiceSettingService.guestUserId(setting));
            }
        }
        // 服务级存储：外部协议固定落到该存储；空值表示不修改，Local 表示内置系统存储
        if (cmd.getStorageSettingId() != null) {
            requireStorage(cmd.getStorageSettingId());
            setting.setConfigData(ServiceSettingService.mergeStorageSettingId(
                    setting.getConfigData(), cmd.getStorageSettingId()));
        }
        if (cmd.getPort() != null) {
            setting.setPort(cmd.getPort());
        }
        if (StrUtil.isNotBlank(cmd.getBindAddress())) {
            setting.setBindAddress(cmd.getBindAddress().trim());
        }
        setting.setUpdatedAt(LocalDateTime.now());
        serviceSettingService.updateById(setting);

        // 只读开关无需重建服务，直接刷运行时快照，下一条写请求就生效
        protocolReadOnlyService.update(type, ServiceSettingService.isReadOnly(setting));
        // 存储作用域同样即时生效
        externalStorageScopeService.invalidate(type);

        // 热生效：启动失败不抛给保存动作（失败状态经 /list 返回）
        protocolServiceManager.apply(type, setting);
        log.info("对外文件服务配置已更新: type={}, enabled={}, readOnly={}, allowGuest={}, guestUserId={}, guestPassword={}",
                type, setting.getEnabled(), ServiceSettingService.isReadOnly(setting),
                ServiceSettingService.isGuestAllowed(setting), ServiceSettingService.guestUserId(setting),
                ServiceSettingService.isGuestPasswordRequired(setting));
        return Result.ok();
    }

    @Operation(summary = "游客视角候选账号（仅正常状态用户）")
    @GetMapping("/alist/users")
    @SaCheckPermission(com.guanghe.fs.system.constant.UserPermissions.SERVICE_MANAGE)
    public Result<List<ServiceGuestUserVO>> alistUsers() {
        List<SysUser> users = userMapper.selectListByQuery(new QueryWrapper()
                .where(SYS_USER.STATUS.eq(UserStatus.NORMAL))
                .orderBy(SYS_USER.USERNAME.asc()));
        List<ServiceGuestUserVO> result = new ArrayList<>(users.size());
        for (SysUser user : users) {
            ServiceGuestUserVO vo = new ServiceGuestUserVO();
            vo.setId(user.getId());
            vo.setUsername(user.getUsername());
            vo.setNickname(user.getNickname());
            result.add(vo);
        }
        return Result.ok(result);
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
                // 语义：重启 = 停止（不动 enabled）后再拉起；socket 型强停重建，非 socket 型状态重置
                com.guanghe.fs.service.spi.ExternalFileService impl = externalFileServiceRegistry.get(type);
                if (impl != null && impl.socketBased()) {
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

    /** 服务级存储必须是存在且已启用的配置；Local 恒可用 */
    private void requireStorage(String settingId) {
        String id = StrUtil.trimToEmpty(settingId);
        if (id.isEmpty() || com.guanghe.fs.storage.plugin.core.utils.StorageUtils.isLocalConfig(id)) {
            return;
        }
        var storage = storageSettingService.getById(id);
        if (storage == null || storage.getEnabled() == null || storage.getEnabled() != 1) {
            throw new BusinessException(I18nUtils.getMessage("service.storage.invalid"));
        }
    }

    /** 游客绑定账号必须是存在且状态正常的用户，否则匿名请求会带着无效身份进入业务层 */
    private void requireNormalUser(String userId) {
        if (StrUtil.isBlank(userId)) {
            throw new BusinessException(I18nUtils.getMessage("service.guest.user.required"));
        }
        SysUser user = userMapper.selectOneById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != UserStatus.NORMAL) {
            throw new BusinessException(I18nUtils.getMessage("service.guest.user.invalid"));
        }
    }

    private String usernameOf(String userId) {
        if (StrUtil.isBlank(userId)) {
            return null;
        }
        SysUser user = userMapper.selectOneById(userId);
        return user == null ? null : user.getUsername();
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
        // socket 型服务（SFTP/FTP…）才有端口语义；从 SPI 实现取能力与默认端口
        com.guanghe.fs.service.spi.ExternalFileService impl =
                externalFileServiceRegistry.get(type);
        if (impl != null && impl.socketBased() && cmd.getPort() != null) {
            if (cmd.getPort() < 1024 || cmd.getPort() > 65535) {
                throw new BusinessException(I18nUtils.getMessage("service.port.invalid"));
            }
        }
    }
}
