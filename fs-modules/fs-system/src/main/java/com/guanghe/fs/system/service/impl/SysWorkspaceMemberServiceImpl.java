package com.guanghe.fs.system.service.impl;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.system.domain.SysWorkspace;
import com.guanghe.fs.system.domain.SysWorkspaceMember;
import com.guanghe.fs.system.domain.dto.CreateWorkspaceMemberCmd;
import com.guanghe.fs.system.domain.vo.WorkspaceMemberVO;
import com.guanghe.fs.system.mapper.SysWorkspaceMapper;
import com.guanghe.fs.system.mapper.SysWorkspaceMemberMapper;
import com.guanghe.fs.system.service.SysWorkspaceMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.guanghe.fs.system.domain.table.SysRoleTableDef.SYS_ROLE;
import static com.guanghe.fs.system.domain.table.SysUserTableDef.SYS_USER;
import static com.guanghe.fs.system.domain.table.SysWorkspaceMemberTableDef.SYS_WORKSPACE_MEMBER;
import static com.guanghe.fs.system.domain.table.SysWorkspaceTableDef.SYS_WORKSPACE;

/**
 * 工作空间成员服务实现
 *
 * @Author: guangheUlti
 * @Date: 2026/3/31
 */
@Service
@RequiredArgsConstructor
public class SysWorkspaceMemberServiceImpl extends ServiceImpl<SysWorkspaceMemberMapper, SysWorkspaceMember> implements SysWorkspaceMemberService {

    private final SysWorkspaceMapper workspaceMapper;

    @Override
    public Page<WorkspaceMemberVO> getMembers(String workspaceId, int pageNumber, int pageSize) {
        Page<WorkspaceMemberVO> page = mapper.paginateAs(
                Page.of(pageNumber, pageSize),
                QueryWrapper.create()
                        .select(
                                SYS_WORKSPACE_MEMBER.ID,
                                SYS_WORKSPACE_MEMBER.WORKSPACE_ID,
                                SYS_USER.ID.as("userId"),
                                SYS_USER.USERNAME,
                                SYS_USER.NICKNAME,
                                SYS_USER.EMAIL,
                                SYS_USER.AVATAR,
                                SYS_USER.STATUS,
                                SYS_USER.LAST_LOGIN_AT,
                                SYS_ROLE.ID.as("roleId"),
                                SYS_ROLE.ROLE_CODE.as("roleCode"),
                                SYS_ROLE.ROLE_NAME.as("roleName"),
                                SYS_WORKSPACE_MEMBER.JOINED_AT,
                                SYS_WORKSPACE.OWNER_ID.as("ownerId")
                        )
                        .from(SYS_WORKSPACE_MEMBER)
                        .leftJoin(SYS_USER).on(SYS_WORKSPACE_MEMBER.USER_ID.eq(SYS_USER.ID))
                        .leftJoin(SYS_ROLE).on(SYS_WORKSPACE_MEMBER.ROLE_ID.eq(SYS_ROLE.ID))
                        .leftJoin(SYS_WORKSPACE).on(SYS_WORKSPACE_MEMBER.WORKSPACE_ID.eq(SYS_WORKSPACE.ID))
                        .where(SYS_WORKSPACE_MEMBER.WORKSPACE_ID.eq(workspaceId)),
                WorkspaceMemberVO.class
        );
        
        // 设置 isOwner 标识
        page.getRecords().forEach(WorkspaceMemberVO::setIsOwner);
        
        return page;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateMemberRole(String workspaceId, String userId, Long roleId, String currentUserId) {
        // 不能修改自己的角色
        if (userId.equals(currentUserId)) {
            throw new BusinessException(I18nUtils.getMessage("member.cannot.modify.self"));
        }
        //不能修改所有者角色
        SysWorkspace workspace = workspaceMapper.selectOneById(workspaceId);
        if (workspace != null && workspace.getOwnerId().equals(userId)) {
            throw new BusinessException(I18nUtils.getMessage("member.cannot.modify.owner"));
        }

        // 查询成员
        SysWorkspaceMember member = mapper.findByWorkspaceAndUser(workspaceId, userId);
        if (member == null) {
            throw new BusinessException(I18nUtils.getMessage("member.not.exist"));
        }

        // 更新角色
        member.setRoleId(roleId);
        mapper.update(member);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeMember(String workspaceId, String userId, String currentUserId) {
        // 查询工作空间
        SysWorkspace workspace = workspaceMapper.selectOneById(workspaceId);
        if (workspace == null) {
            throw new BusinessException(I18nUtils.getMessage("workspace.not.exist"));
        }

        // 不能移除所有者
        if (workspace.getOwnerId().equals(userId)) {
            throw new BusinessException(I18nUtils.getMessage("member.cannot.remove.owner"));
        }

        // 不能移除自己
        if (userId.equals(currentUserId)) {
            throw new BusinessException(I18nUtils.getMessage("member.cannot.remove.self"));
        }

        // 查询成员
        SysWorkspaceMember member = mapper.findByWorkspaceAndUser(workspaceId, userId);
        if (member == null) {
            throw new BusinessException(I18nUtils.getMessage("member.not.exist"));
        }

        // 删除成员
        mapper.deleteById(member.getId());

        // 更新成员数量
        workspace.setMemberCount(workspace.getMemberCount() - 1);
        workspaceMapper.update(workspace);
    }

    @Override
    public SysWorkspaceMember createMember(CreateWorkspaceMemberCmd cmd) {
        SysWorkspaceMember member = new SysWorkspaceMember();
        member.setWorkspaceId(cmd.getWorkspaceId());
        member.setUserId(cmd.getUserId());
        member.setRoleId(cmd.getRoleId());
        member.setJoinedAt(LocalDateTime.now());
        member.setUpdatedAt(LocalDateTime.now());
        mapper.insert(member);
        return member;
    }

    @Override
    public SysWorkspaceMember findByWorkspaceAndUser(String workspaceId, String userId) {
        return mapper.findByWorkspaceAndUser(workspaceId, userId);
    }

    @Override
    public long countByWorkspaceAndRoleId(String workspaceId, Long roleId) {
        return mapper.selectCountByQuery(
                QueryWrapper.create()
                        .where(SYS_WORKSPACE_MEMBER.WORKSPACE_ID.eq(workspaceId))
                        .and(SYS_WORKSPACE_MEMBER.ROLE_ID.eq(roleId))
        );
    }
}
