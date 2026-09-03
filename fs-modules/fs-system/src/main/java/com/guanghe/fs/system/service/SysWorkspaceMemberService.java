package com.guanghe.fs.system.service;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.service.IService;
import com.guanghe.fs.system.domain.SysWorkspaceMember;
import com.guanghe.fs.system.domain.dto.CreateWorkspaceMemberCmd;
import com.guanghe.fs.system.domain.vo.WorkspaceMemberVO;

/**
 * 工作空间成员服务接口
 *
 * @Author: guangheUlti
 * @Date: 2026/3/31
 */
public interface SysWorkspaceMemberService extends IService<SysWorkspaceMember> {

    /**
     * 分页查询工作空间成员
     *
     * @param workspaceId 工作空间ID
     * @param pageNumber 页码
     * @param pageSize 每页大小
     * @return 成员列表
     */
    Page<WorkspaceMemberVO> getMembers(String workspaceId, int pageNumber, int pageSize);

    /**
     * 更新成员角色
     *
     * @param workspaceId 工作空间ID
     * @param userId 用户ID
     * @param roleId 角色ID
     * @param currentUserId 当前操作用户ID
     */
    void updateMemberRole(String workspaceId, String userId, Long roleId, String currentUserId);

    /**
     * 移除成员
     *
     * @param workspaceId 工作空间ID
     * @param userId 用户ID
     * @param currentUserId 当前操作用户ID
     */
    void removeMember(String workspaceId, String userId, String currentUserId);

    /**
     * 创建工作空间成员
     *
     * @param cmd 创建成员命令
     * @return 创建后的成员
     */
    SysWorkspaceMember createMember(CreateWorkspaceMemberCmd cmd);

    /**
     * 根据工作空间ID和用户ID查询成员
     *
     * @param workspaceId 工作空间ID
     * @param userId 用户ID
     * @return 成员信息
     */
    SysWorkspaceMember findByWorkspaceAndUser(String workspaceId, String userId);

    /**
     * 统计某工作空间内使用指定角色的成员数量
     */
    long countByWorkspaceAndRoleId(String workspaceId, Long roleId);
}
