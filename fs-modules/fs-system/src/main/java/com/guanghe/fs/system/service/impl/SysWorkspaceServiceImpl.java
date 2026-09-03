package com.guanghe.fs.system.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.system.constant.WorkspaceRoleConstants;
import com.guanghe.fs.system.domain.*;
import com.guanghe.fs.system.domain.dto.BatchCreateRolePermissionsCmd;
import com.guanghe.fs.system.domain.dto.CreateRoleCmd;
import com.guanghe.fs.system.domain.dto.CreateWorkspaceCmd;
import com.guanghe.fs.system.domain.dto.CreateWorkspaceMemberCmd;
import com.guanghe.fs.system.domain.dto.UpdateWorkspaceCmd;
import com.guanghe.fs.system.domain.vo.WorkspaceDetailVO;
import com.guanghe.fs.system.domain.vo.WorkspaceVO;
import com.guanghe.fs.system.mapper.SysWorkspaceInvitationMapper;
import com.guanghe.fs.system.mapper.SysWorkspaceMapper;
import com.guanghe.fs.system.service.*;
import io.github.linpeilie.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

import static com.guanghe.fs.system.domain.table.SysRolePermissionTableDef.SYS_ROLE_PERMISSION;
import static com.guanghe.fs.system.domain.table.SysRoleTableDef.SYS_ROLE;
import static com.guanghe.fs.system.domain.table.SysWorkspaceInvitationTableDef.SYS_WORKSPACE_INVITATION;
import static com.guanghe.fs.system.domain.table.SysWorkspaceMemberTableDef.SYS_WORKSPACE_MEMBER;
import static com.guanghe.fs.system.domain.table.SysWorkspaceTableDef.SYS_WORKSPACE;

/**
 * 工作空间表 服务层实现
 *
 * @author guangheUlti
 * @date 2026/3/30 10:11
 */
@Service
@RequiredArgsConstructor
public class SysWorkspaceServiceImpl extends ServiceImpl<SysWorkspaceMapper, SysWorkspace> implements SysWorkspaceService {

    private final Converter converter;
    private final SysPermissionService permissionService;
    private final SysRoleService roleService;
    private final SysRolePermissionService rolePermissionService;
    private final SysWorkspaceMemberService memberService;
    private final SysWorkspaceInvitationMapper invitationMapper;

    @Override
    public List<WorkspaceVO> getWorkspacesByUser(String userId) {
        // 查询用户加入的所有工作空间
        List<SysWorkspace> workspaces = mapper.selectListByQuery(
                QueryWrapper.create()
                        .select(SYS_WORKSPACE.ALL_COLUMNS)
                        .from(SYS_WORKSPACE)
                        .innerJoin(SYS_WORKSPACE_MEMBER).on(SYS_WORKSPACE.ID.eq(SYS_WORKSPACE_MEMBER.WORKSPACE_ID))
                        .where(SYS_WORKSPACE_MEMBER.USER_ID.eq(userId))
        );
        return converter.convert(workspaces, WorkspaceVO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceVO createWorkspace(CreateWorkspaceCmd cmd) {
        String userId = StpUtil.getLoginIdAsString();

        // 1. 创建 workspace
        SysWorkspace workspace = new SysWorkspace();
        workspace.setName(cmd.getName());
        workspace.setSlug(cmd.getSlug());
        workspace.setDescription(cmd.getDescription());
        workspace.setOwnerId(userId);
        workspace.setMemberCount(1);
        this.save(workspace);

        // 2. 创建系统预设角色：管理员、普通成员、受限成员
        List<SysPermission> allPerms = permissionService.getAll();
        List<String> allCodes = allPerms.stream()
                .map(SysPermission::getPermissionCode)
                .collect(Collectors.toList());

        CreateRoleCmd adminCmd = new CreateRoleCmd();
        adminCmd.setWorkspaceId(workspace.getId());
        adminCmd.setRoleCode(WorkspaceRoleConstants.CODE_ADMIN);
        adminCmd.setRoleName(I18nUtils.getMessage("role.admin.name"));
        adminCmd.setDescription(I18nUtils.getMessage("role.admin.description"));
        adminCmd.setRoleType(WorkspaceRoleConstants.TYPE_SYSTEM);
        SysRole adminRole = roleService.createRole(adminCmd);
        bindRolePermissions(adminRole, allCodes);

        CreateRoleCmd memberCmd = new CreateRoleCmd();
        memberCmd.setWorkspaceId(workspace.getId());
        memberCmd.setRoleCode(WorkspaceRoleConstants.CODE_MEMBER);
        memberCmd.setRoleName(I18nUtils.getMessage("role.member.name"));
        memberCmd.setDescription(I18nUtils.getMessage("role.member.description"));
        memberCmd.setRoleType(WorkspaceRoleConstants.TYPE_SYSTEM);
        SysRole memberPreset = roleService.createRole(memberCmd);
        bindRolePermissions(memberPreset, List.of("file:read", "file:write", "file:share"));

        CreateRoleCmd viewerCmd = new CreateRoleCmd();
        viewerCmd.setWorkspaceId(workspace.getId());
        viewerCmd.setRoleCode(WorkspaceRoleConstants.CODE_VIEWER);
        viewerCmd.setRoleName(I18nUtils.getMessage("role.viewer.name"));
        viewerCmd.setDescription(I18nUtils.getMessage("role.viewer.description"));
        viewerCmd.setRoleType(WorkspaceRoleConstants.TYPE_SYSTEM);
        SysRole viewerPreset = roleService.createRole(viewerCmd);
        bindRolePermissions(viewerPreset, List.of("file:read"));

        // 3. 创建者加入工作空间并绑定管理员角色
        CreateWorkspaceMemberCmd workspaceMemberCmd = new CreateWorkspaceMemberCmd();
        workspaceMemberCmd.setWorkspaceId(workspace.getId());
        workspaceMemberCmd.setUserId(userId);
        workspaceMemberCmd.setRoleId(adminRole.getId());
        memberService.createMember(workspaceMemberCmd);

        return converter.convert(workspace, WorkspaceVO.class);
    }

    private void bindRolePermissions(SysRole role, List<String> permissionCodes) {
        BatchCreateRolePermissionsCmd permCmd = new BatchCreateRolePermissionsCmd();
        permCmd.setRoleId(role.getId());
        permCmd.setRoleCode(role.getRoleCode());
        permCmd.setPermissionCodes(permissionCodes);
        rolePermissionService.batchCreateRolePermissions(permCmd);
    }

    @Override
    public WorkspaceDetailVO getCurrentDetail(String workspaceId, String userId) {
        SysWorkspace workspace = this.getById(workspaceId);
        if (workspace == null) {
            throw new BusinessException(I18nUtils.getMessage("workspace.not.exist"));
        }

        SysWorkspaceMember member = memberService.findByWorkspaceAndUser(workspaceId, userId);
        if (member == null) {
            throw new BusinessException(I18nUtils.getMessage("workspace.not.member"));
        }

        WorkspaceDetailVO vo = new WorkspaceDetailVO();
        vo.setId(workspace.getId());
        vo.setName(workspace.getName());
        vo.setSlug(workspace.getSlug());
        vo.setDescription(workspace.getDescription());
        vo.setOwnerId(workspace.getOwnerId());
        vo.setMemberCount(workspace.getMemberCount());
        vo.setCreatedAt(workspace.getCreatedAt());
        vo.setUpdatedAt(workspace.getUpdatedAt());

        SysRole role = roleService.getRoleById(member.getRoleId());
        if (role != null) {
            vo.setRoleCode(role.getRoleCode());
            vo.setRoleName(role.getRoleName());
            vo.setPermissions(rolePermissionService.getPermissionCodesByRoleId(role.getId()));
        }

        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceVO updateWorkspace(String workspaceId, UpdateWorkspaceCmd cmd) {
        SysWorkspace workspace = this.getById(workspaceId);
        if (workspace == null) {
            throw new BusinessException(I18nUtils.getMessage("workspace.not.exist"));
        }

        workspace.setName(cmd.getName());
        workspace.setDescription(cmd.getDescription());
        this.updateById(workspace);

        return converter.convert(workspace, WorkspaceVO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteWorkspace(String workspaceId, String userId) {
        SysWorkspace workspace = this.getById(workspaceId);
        if (workspace == null) {
            throw new BusinessException(I18nUtils.getMessage("workspace.not.exist"));
        }

        if (!workspace.getOwnerId().equals(userId)) {
            throw new BusinessException(I18nUtils.getMessage("workspace.only.owner.can.delete"));
        }

        // 级联删除：邀请 → 成员 → 角色权限 → 角色 → 工作空间
        invitationMapper.deleteByQuery(QueryWrapper.create()
                .where(SYS_WORKSPACE_INVITATION.WORKSPACE_ID.eq(workspaceId)));
        memberService.remove(QueryWrapper.create()
                .where(SYS_WORKSPACE_MEMBER.WORKSPACE_ID.eq(workspaceId)));

        List<SysRole> roles = roleService.list(
                QueryWrapper.create().where(SYS_ROLE.WORKSPACE_ID.eq(workspaceId)));
        if (!roles.isEmpty()) {
            List<Long> roleIds = roles.stream().map(SysRole::getId).toList();
            rolePermissionService.remove(QueryWrapper.create()
                    .where(SYS_ROLE_PERMISSION.ROLE_ID.in(roleIds)));
            roleService.removeByIds(roleIds);
        }

        this.removeById(workspaceId);
    }

    @Override
    public boolean checkSlug(String slug) {
        long count = this.count(QueryWrapper.create().where(SYS_WORKSPACE.SLUG.eq(slug)));
        return count == 0;
    }
}
