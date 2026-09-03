package com.guanghe.fs.system.service;

import com.mybatisflex.core.service.IService;
import com.guanghe.fs.system.domain.SysRolePermission;
import com.guanghe.fs.system.domain.dto.BatchCreateRolePermissionsCmd;

import java.util.List;

/**
 * 角色与权限码的关联服务（{@code sys_role_permission}）。
 * <p>
 * <b>关联约定</b>：表内以 {@code role_id} 关联 {@code sys_role} 为准；{@code role_code} 为与角色行同步的冗余字段，便于排查，查询权限列表时请使用 {@link #getPermissionCodesByRoleId(Long)}。
 *
 * @Author: guangheUlti
 * @Date: 2026/3/30 10:11
 */
public interface SysRolePermissionService extends IService<SysRolePermission> {

    /**
     * 批量写入角色权限行（每条含 {@code role_id} 与 {@code permission_code}）。
     */
    void batchCreateRolePermissions(BatchCreateRolePermissionsCmd cmd);

    /**
     * 按角色主键查询权限码列表（查询条件使用 {@code role_id}）。
     *
     * @param roleId {@code sys_role.role_id}
     */
    List<String> getPermissionCodesByRoleId(Long roleId);

    /**
     * 删除指定角色下的全部权限关联（按 {@code role_id} 删除）。
     */
    void removeByRoleId(Long roleId);

    /**
     * 替换某角色的权限集合：先按 {@code role_id} 清空，再批量插入；{@code roleCode} 写入冗余列。
     */
    void replacePermissions(Long roleId, String roleCode, List<String> permissionCodes);
}
