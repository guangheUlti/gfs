package com.guanghe.fs.system.service;

import com.mybatisflex.core.service.IService;
import com.guanghe.fs.system.domain.SysWorkspaceInvitation;
import com.guanghe.fs.system.domain.dto.CreateInvitationCmd;
import com.guanghe.fs.system.domain.vo.InvitationDetailVO;
import com.guanghe.fs.system.domain.vo.WorkspaceInvitationVO;

import java.util.List;

/**
 * 工作空间邀请服务接口
 *
 * @Author: guangheUlti
 * @Date: 2026/3/31
 */
public interface SysWorkspaceInvitationService extends IService<SysWorkspaceInvitation> {

    /**
     * 获取工作空间的邀请列表
     *
     * @param workspaceId 工作空间ID
     * @return 邀请列表
     */
    List<WorkspaceInvitationVO> getInvitations(String workspaceId);

    /**
     * 创建邀请
     *
     * @param workspaceId 工作空间ID
     * @param cmd 创建邀请命令
     * @param inviterId 邀请人ID
     * @return 邀请VO
     */
    WorkspaceInvitationVO createInvitation(String workspaceId, CreateInvitationCmd cmd, String inviterId);

    /**
     * 取消邀请
     *
     * @param invitationId 邀请ID
     * @param workspaceId 工作空间ID
     */
    void cancelInvitation(String invitationId, String workspaceId);

    /**
     * 根据 token 查询邀请
     *
     * @param token 邀请令牌
     * @return 邀请信息
     */
    SysWorkspaceInvitation findByToken(String token);

    /**
     * 验证邀请令牌并获取邀请详情
     *
     * @param token 邀请令牌
     * @return 邀请详情
     */
    InvitationDetailVO verifyInvitation(String token);

    /**
     * 接受邀请
     *
     * @param token 邀请令牌
     * @param userId 用户ID
     */
    void acceptInvitation(String token, String userId);
}
