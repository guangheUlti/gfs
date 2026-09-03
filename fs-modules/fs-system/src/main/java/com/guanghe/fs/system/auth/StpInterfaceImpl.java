package com.guanghe.fs.system.auth;

import cn.dev33.satoken.stp.StpInterface;
import com.guanghe.fs.framework.common.context.WorkspaceContext;
import com.guanghe.fs.system.domain.SysRole;
import com.guanghe.fs.system.domain.SysWorkspaceMember;
import com.guanghe.fs.system.service.SysRolePermissionService;
import com.guanghe.fs.system.service.SysRoleService;
import com.guanghe.fs.system.service.SysWorkspaceMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义权限加载接口实现类
 * 基于工作空间的权限验证
 *
 * @Author: guangheUlti
 * @Date: 2024/11/20 14:46
 */
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final SysWorkspaceMemberService memberService;
    private final SysRoleService roleService;
    private final SysRolePermissionService rolePermissionService;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        // 获取当前工作空间ID
        String workspaceId = WorkspaceContext.getWorkspaceId();
        if (workspaceId == null || workspaceId.isBlank()) {
            // 如果没有工作空间上下文，返回空权限列表
            return new ArrayList<>();
        }

        // 查询用户在当前工作空间的成员信息
        String userId = String.valueOf(loginId);
        SysWorkspaceMember member = memberService.findByWorkspaceAndUser(workspaceId, userId);
        if (member == null) {
            return new ArrayList<>();
        }

        // 根据角色ID获取权限列表
        List<String> permissions = rolePermissionService.getPermissionCodesByRoleId(member.getRoleId());
        return permissions != null ? permissions : new ArrayList<>();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        // 获取当前工作空间ID
        String workspaceId = WorkspaceContext.getWorkspaceId();
        if (workspaceId == null || workspaceId.isBlank()) {
            // 如果没有工作空间上下文，返回空角色列表
            return new ArrayList<>();
        }

        // 查询用户在当前工作空间的成员信息
        String userId = String.valueOf(loginId);
        SysWorkspaceMember member = memberService.findByWorkspaceAndUser(workspaceId, userId);
        if (member == null) {
            return new ArrayList<>();
        }

        // 根据角色ID获取角色信息
        SysRole role = roleService.getRoleById(member.getRoleId());
        if (role != null) {
            return List.of(role.getRoleCode());
        }

        return new ArrayList<>();
    }
}
