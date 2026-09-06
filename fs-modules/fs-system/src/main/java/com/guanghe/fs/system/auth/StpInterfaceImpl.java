package com.guanghe.fs.system.auth;

import cn.dev33.satoken.stp.StpInterface;
import com.guanghe.fs.system.constant.UserPermissions;
import com.guanghe.fs.system.domain.SysUser;
import com.guanghe.fs.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 自定义权限加载接口实现类
 * <p>
 * 权限为用户级：所有登录用户拥有文件读写与分享权限；系统管理员
 * （用户名与 security.super-admin.username 一致）额外拥有存储管理与日志查看权限。
 *
 * @Author: guangheUlti
 * @Date: 2024-11-20 14:46
 */
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final SysUserMapper userMapper;

    /** 系统管理员用户名 */
    @Value("${security.super-admin.username:admin}")
    private String superAdminUsername;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return UserPermissions.of(isSuperAdmin(loginId));
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return UserPermissions.rolesOf(isSuperAdmin(loginId));
    }

    /**
     * 是否系统管理员。查不到用户时按普通用户处理，未登录的情况由 Sa-Token 登录校验先行拦截。
     */
    private boolean isSuperAdmin(Object loginId) {
        SysUser user = userMapper.selectOneById(String.valueOf(loginId));
        return user != null && user.getUsername() != null
                && user.getUsername().equals(superAdminUsername);
    }
}
