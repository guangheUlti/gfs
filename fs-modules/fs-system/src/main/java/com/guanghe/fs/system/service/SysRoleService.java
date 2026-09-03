package com.guanghe.fs.system.service;

import com.mybatisflex.core.service.IService;
import com.guanghe.fs.system.domain.SysRole;
import com.guanghe.fs.system.domain.dto.CreateCustomRoleCmd;
import com.guanghe.fs.system.domain.dto.CreateRoleCmd;
import com.guanghe.fs.system.domain.dto.UpdateCustomRoleCmd;
import com.guanghe.fs.system.domain.vo.SysRoleVO;

import java.util.List;

/**
 * 角色服务接口。
 * <p>
 * <b>关联约定（id 与 code）</b>
 * <ul>
 *   <li><b>role_id（主键）</b>：成员表、邀请表、角色-权限关联表均通过 {@code role_id} 引用 {@code sys_role}，是空间内角色的唯一标识。</li>
 *   <li><b>role_code（编码）</b>：同一 {@code workspace_id} 下唯一，用于展示名称、创建时校验、以及 Sa-Token {@code getRoleList} 返回给 {@code @SaCheckRole("admin")} 等注解使用；<b>不作为外键</b>。</li>
 * </ul>
 *
 * @Author: guangheUlti
 * @Date: 2026/3/30 10:11
 */
public interface SysRoleService extends IService<SysRole> {

    /**
     * 根据工作空间列出全部角色（含系统预设与自定义）。
     *
     * @param workspaceId 工作空间 ID
     * @return 角色列表 VO
     */
    List<SysRoleVO> getListByWorkspace(String workspaceId);

    /**
     * 内部创建角色行（工作区初始化、自定义角色创建等）；权限绑定请配合 {@link SysRolePermissionService}。
     *
     * @param cmd 创建命令（含 workspaceId、roleCode、roleType 等）
     * @return 持久化后的角色实体
     */
    SysRole createRole(CreateRoleCmd cmd);

    /**
     * 按主键查询角色；若当前请求存在工作空间上下文，则限定为该空间下的 {@code role_id}，避免跨空间误用。
     *
     * @param roleId 角色主键 ID
     * @return 角色实体，不存在或未落在当前空间时为 null
     */
    SysRole getRoleById(Long roleId);

    /**
     * 在当前工作空间创建自定义角色（{@code role_type = 自定义}），并写入权限关联（以 {@code role_id} 为准）。
     */
    SysRoleVO createCustomRole(String workspaceId, CreateCustomRoleCmd cmd);

    /**
     * 更新自定义角色的名称、描述与权限；系统预设角色不可调用。
     *
     * @param roleId 角色主键 ID（非 role_code）
     */
    SysRoleVO updateCustomRole(String workspaceId, Long roleId, UpdateCustomRoleCmd cmd);

    /**
     * 删除自定义角色；若仍有成员占用该 {@code role_id} 则拒绝。
     */
    void deleteCustomRole(String workspaceId, Long roleId);

    /**
     * 查询角色详情（含权限码列表）；按 {@code role_id} + 工作空间校验。
     */
    SysRoleVO getRoleDetail(String workspaceId, Long roleId);
}
