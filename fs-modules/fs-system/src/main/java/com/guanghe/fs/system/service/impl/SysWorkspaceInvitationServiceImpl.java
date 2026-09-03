package com.guanghe.fs.system.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.framework.notify.mail.domain.Mail;
import com.guanghe.fs.framework.notify.mail.event.MailEvent;
import com.guanghe.fs.system.domain.*;
import com.guanghe.fs.system.domain.dto.CreateInvitationCmd;
import com.guanghe.fs.system.domain.dto.CreateWorkspaceMemberCmd;
import com.guanghe.fs.system.domain.vo.InvitationDetailVO;
import com.guanghe.fs.system.domain.vo.WorkspaceInvitationVO;
import com.guanghe.fs.system.mapper.SysUserMapper;
import com.guanghe.fs.system.mapper.SysWorkspaceInvitationMapper;
import com.guanghe.fs.system.service.*;
import io.github.linpeilie.Converter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.guanghe.fs.system.domain.table.SysRoleTableDef.SYS_ROLE;
import static com.guanghe.fs.system.domain.table.SysUserTableDef.SYS_USER;
import static com.guanghe.fs.system.domain.table.SysWorkspaceInvitationTableDef.SYS_WORKSPACE_INVITATION;
import static com.guanghe.fs.system.domain.table.SysWorkspaceTableDef.SYS_WORKSPACE;

/**
 * 工作空间邀请服务实现
 *
 * @Author: guangheUlti
 * @Date: 2026/3/31
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysWorkspaceInvitationServiceImpl extends ServiceImpl<SysWorkspaceInvitationMapper, SysWorkspaceInvitation> implements SysWorkspaceInvitationService {

    private final Converter converter;
    private final SysRoleService roleService;
    private final SysWorkspaceMemberService memberService;
    private final SysWorkspaceService workspaceService;
    private final SysUserMapper userMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${fs.frontend.domain}")
    private String frontendUrl;

    @Override
    public List<WorkspaceInvitationVO> getInvitations(String workspaceId) {
        return mapper.selectListByQueryAs(
                QueryWrapper.create()
                        .select(
                                SYS_WORKSPACE_INVITATION.ALL_COLUMNS,
                                SYS_ROLE.ROLE_NAME,
                                SYS_USER.NICKNAME.as("invitedByName")
                        )
                        .from(SYS_WORKSPACE_INVITATION)
                        .leftJoin(SYS_ROLE).on(SYS_WORKSPACE_INVITATION.ROLE_ID.eq(SYS_ROLE.ID))
                        .leftJoin(SYS_USER).on(SYS_WORKSPACE_INVITATION.INVITED_BY.eq(SYS_USER.ID))
                        .where(SYS_WORKSPACE_INVITATION.WORKSPACE_ID.eq(workspaceId))
                        .and(SYS_WORKSPACE_INVITATION.STATUS.in(0, 1)) // 只显示待接受和已接受的
                        .orderBy(SYS_WORKSPACE_INVITATION.CREATED_AT.desc()),
                WorkspaceInvitationVO.class
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceInvitationVO createInvitation(String workspaceId, CreateInvitationCmd cmd, String inviterId) {
        // 验证工作空间是否存在
        SysWorkspace workspace = workspaceService.getById(workspaceId);
        if (workspace == null) {
            throw new BusinessException(I18nUtils.getMessage("workspace.not.exist"));
        }

        // 验证角色是否存在
        SysRole role = roleService.getRoleById(cmd.getRoleId());
        if (role == null) {
            throw new BusinessException(I18nUtils.getMessage("role.not.exist"));
        }

        // 获取邀请者信息
        SysUser inviter = userMapper.selectOneById(inviterId);
        if (inviter == null) {
            throw new BusinessException(I18nUtils.getMessage("user.not.exist"));
        }

        // 检查被邀请的邮箱是否已注册，如果已注册则检查是否已经是成员
        SysUser invitedUser = userMapper.selectOneByQuery(
                QueryWrapper.create().where(SYS_USER.EMAIL.eq(cmd.getEmail()))
        );
        if (invitedUser != null) {
            SysWorkspaceMember existingMember = memberService.findByWorkspaceAndUser(workspaceId, invitedUser.getId());
            if (existingMember != null) {
                throw new BusinessException(I18nUtils.getMessage("invitation.already.member"));
            }
        }

        // 检查是否存在待处理的邀请
        SysWorkspaceInvitation existingInvitation = this.getOne(
                QueryWrapper.create()
                        .where(SYS_WORKSPACE_INVITATION.WORKSPACE_ID.eq(workspaceId))
                        .and(SYS_WORKSPACE_INVITATION.EMAIL.eq(cmd.getEmail()))
                        .and(SYS_WORKSPACE_INVITATION.STATUS.eq(0))
                        .and(SYS_WORKSPACE_INVITATION.EXPIRES_AT.gt(LocalDateTime.now()))
        );
        if (existingInvitation != null) {
            throw new BusinessException(I18nUtils.getMessage("invitation.already.pending"));
        }

        // 生成邀请令牌
        String token = UUID.randomUUID().toString().replace("-", "");

        // 创建邀请
        SysWorkspaceInvitation invitation = new SysWorkspaceInvitation();
        invitation.setWorkspaceId(workspaceId);
        invitation.setEmail(cmd.getEmail());
        invitation.setRoleId(cmd.getRoleId());
        invitation.setInvitedBy(inviterId);
        invitation.setToken(token);
        invitation.setStatus(0); // 待接受
        invitation.setExpiresAt(LocalDateTime.now().plusHours(cmd.getExpiresHours()));
        this.save(invitation);

        // 生成邀请链接（从配置中获取前端地址）
        String inviteLink = frontendUrl + "/invite?token=" + token;

        // 发送邀请邮件
        try {
            Mail mail = Mail.buildWorkspaceMemberInviteMail(
                    cmd.getEmail(),
                    inviter.getNickname(),
                    workspace.getName(),
                    role.getRoleName(),
                    inviteLink
            );
            eventPublisher.publishEvent(new MailEvent(this, mail));
        } catch (Exception e) {
            log.error("Failed to send invitation email: {}", e.getMessage());
        }

        return converter.convert(invitation, WorkspaceInvitationVO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelInvitation(String invitationId, String workspaceId) {
        SysWorkspaceInvitation invitation = this.getById(invitationId);
        if (invitation == null) {
            throw new BusinessException(I18nUtils.getMessage("invitation.not.exist"));
        }

        if (!invitation.getWorkspaceId().equals(workspaceId)) {
            throw new BusinessException(I18nUtils.getMessage("invitation.no.permission"));
        }

        // 更新状态为已取消
        invitation.setStatus(3);
        this.updateById(invitation);
    }

    @Override
    public SysWorkspaceInvitation findByToken(String token) {
        return this.getOne(QueryWrapper.create()
                .where(SYS_WORKSPACE_INVITATION.TOKEN.eq(token)));
    }

    @Override
    public InvitationDetailVO verifyInvitation(String token) {
        // 查询邀请信息及关联数据
        SysWorkspaceInvitation invitation = mapper.selectOneByQueryAs(
                QueryWrapper.create()
                        .select(
                                SYS_WORKSPACE_INVITATION.ALL_COLUMNS,
                                SYS_WORKSPACE.NAME.as("workspaceName"),
                                SYS_ROLE.ROLE_NAME,
                                SYS_USER.NICKNAME.as("inviterName")
                        )
                        .from(SYS_WORKSPACE_INVITATION)
                        .leftJoin(SYS_WORKSPACE).on(SYS_WORKSPACE_INVITATION.WORKSPACE_ID.eq(SYS_WORKSPACE.ID))
                        .leftJoin(SYS_ROLE).on(SYS_WORKSPACE_INVITATION.ROLE_ID.eq(SYS_ROLE.ID))
                        .leftJoin(SYS_USER).on(SYS_WORKSPACE_INVITATION.INVITED_BY.eq(SYS_USER.ID))
                        .where(SYS_WORKSPACE_INVITATION.TOKEN.eq(token)),
                SysWorkspaceInvitation.class
        );

        if (invitation == null) {
            throw new BusinessException(I18nUtils.getMessage("invitation.not.exist"));
        }

        // 检查邀请状态
        if (invitation.getStatus() != 0) {
            throw new BusinessException(I18nUtils.getMessage("invitation.invalid"));
        }

        // 检查是否过期
        boolean expired = invitation.getExpiresAt().isBefore(LocalDateTime.now());
        if (expired) {
            // 自动更新为已过期状态
            invitation.setStatus(2);
            this.updateById(invitation);
            throw new BusinessException(I18nUtils.getMessage("invitation.expired"));
        }

        // 检查用户是否已存在
        SysUser existingUser = userMapper.selectOneByQuery(
                QueryWrapper.create().where(SYS_USER.EMAIL.eq(invitation.getEmail()))
        );
        boolean userExists = existingUser != null;

        // 构建返回对象
        return InvitationDetailVO.builder()
                .id(invitation.getId())
                .workspaceId(invitation.getWorkspaceId())
                .workspaceName(invitation.getWorkspaceName())
                .email(invitation.getEmail())
                .roleName(invitation.getRoleName())
                .inviterName(invitation.getInviterName())
                .status(invitation.getStatus())
                .expiresAt(invitation.getExpiresAt())
                .expired(expired)
                .createdAt(invitation.getCreatedAt())
                .userExists(userExists)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void acceptInvitation(String token, String userId) {
        SysWorkspaceInvitation invitation = findByToken(token);
        if (invitation == null) {
            throw new BusinessException(I18nUtils.getMessage("invitation.not.exist"));
        }

        // 检查邀请状态
        if (invitation.getStatus() != 0) {
            throw new BusinessException(I18nUtils.getMessage("invitation.invalid"));
        }

        // 检查是否过期
        if (invitation.getExpiresAt().isBefore(LocalDateTime.now())) {
            invitation.setStatus(2); // 已过期
            this.updateById(invitation);
            throw new BusinessException(I18nUtils.getMessage("invitation.expired"));
        }

        // 获取用户信息
        SysUser user = userMapper.selectOneById(userId);
        if (user == null) {
            throw new BusinessException(I18nUtils.getMessage("user.not.exist"));
        }

        // 验证用户邮箱是否匹配（用户注册时应该使用邀请邮箱）
        if (!invitation.getEmail().equals(user.getEmail())) {
            throw new BusinessException(I18nUtils.getMessage("invitation.email.mismatch"));
        }

        // 检查是否已经是成员
        SysWorkspaceMember existingMember = memberService.findByWorkspaceAndUser(invitation.getWorkspaceId(), userId);
        if (existingMember != null) {
            throw new BusinessException(I18nUtils.getMessage("invitation.already.member"));
        }

        // 创建成员记录
        CreateWorkspaceMemberCmd memberCmd = new CreateWorkspaceMemberCmd();
        memberCmd.setWorkspaceId(invitation.getWorkspaceId());
        memberCmd.setUserId(userId);
        memberCmd.setRoleId(invitation.getRoleId());
        memberService.createMember(memberCmd);

        // 更新邀请状态
        invitation.setStatus(1); // 已接受
        invitation.setAcceptedAt(LocalDateTime.now());
        this.updateById(invitation);

        // 更新工作空间成员数量
        SysWorkspace workspace = workspaceService.getById(invitation.getWorkspaceId());
        if (workspace != null) {
            workspace.setMemberCount(workspace.getMemberCount() + 1);
            workspaceService.updateById(workspace);
        }
    }
}
