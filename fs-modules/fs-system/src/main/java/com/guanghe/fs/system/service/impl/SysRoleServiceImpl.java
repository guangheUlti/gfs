package com.guanghe.fs.system.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.guanghe.fs.framework.common.context.WorkspaceContext;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.system.constant.WorkspaceRoleConstants;
import com.guanghe.fs.system.domain.SysPermission;
import com.guanghe.fs.system.domain.SysRole;
import com.guanghe.fs.system.domain.dto.CreateCustomRoleCmd;
import com.guanghe.fs.system.domain.dto.CreateRoleCmd;
import com.guanghe.fs.system.domain.dto.UpdateCustomRoleCmd;
import com.guanghe.fs.system.domain.vo.SysRoleVO;
import com.guanghe.fs.system.mapper.SysRoleMapper;
import com.guanghe.fs.system.service.SysPermissionService;
import com.guanghe.fs.system.service.SysRolePermissionService;
import com.guanghe.fs.system.service.SysRoleService;
import com.guanghe.fs.system.service.SysWorkspaceMemberService;
import io.github.linpeilie.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.guanghe.fs.system.domain.table.SysRoleTableDef.SYS_ROLE;

/**
 * 角色服务实现类
 *
 * @Author: guangheUlti
 * @Date: 2026/3/30 10:11
 */
@Service
@RequiredArgsConstructor
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements SysRoleService {

    private final Converter converter;
    private final SysPermissionService permissionService;
    private final SysRolePermissionService rolePermissionService;
    private final SysWorkspaceMemberService workspaceMemberService;

    @Override
    public List<SysRoleVO> getListByWorkspace(String workspaceId) {
        List<SysRole> list = this.list(new QueryWrapper().where(SYS_ROLE.WORKSPACE_ID.eq(workspaceId)));
        list.sort(Comparator
                .comparing((SysRole r) -> isSystemRole(r) ? 0 : 1)
                .thenComparing(SysRoleServiceImpl::presetSortKey)
                .thenComparing(SysRole::getId, Comparator.nullsLast(Long::compareTo)));
        return converter.convert(list, SysRoleVO.class);
    }

    private static boolean isSystemRole(SysRole r) {
        return r.getRoleType() != null && r.getRoleType() == WorkspaceRoleConstants.TYPE_SYSTEM;
    }

    private static int presetSortKey(SysRole r) {
        String code = r.getRoleCode();
        if (code == null) {
            return 99;
        }
        return switch (code.toLowerCase()) {
            case WorkspaceRoleConstants.CODE_ADMIN -> 0;
            case WorkspaceRoleConstants.CODE_MEMBER -> 1;
            case WorkspaceRoleConstants.CODE_VIEWER -> 2;
            default -> 50;
        };
    }

    @Override
    public SysRole createRole(CreateRoleCmd cmd) {
        SysRole role = new SysRole();
        role.setWorkspaceId(cmd.getWorkspaceId());
        role.setRoleCode(cmd.getRoleCode());
        role.setRoleName(cmd.getRoleName());
        role.setDescription(cmd.getDescription());
        role.setRoleType(cmd.getRoleType() != null ? cmd.getRoleType() : WorkspaceRoleConstants.TYPE_CUSTOM);
        this.save(role);
        return role;
    }

    @Override
    public SysRole getRoleById(Long roleId) {
        String workspaceId = WorkspaceContext.getWorkspaceId();
        if (workspaceId != null) {
            return this.getOne(new QueryWrapper()
                    .where(SYS_ROLE.ID.eq(roleId))
                    .and(SYS_ROLE.WORKSPACE_ID.eq(workspaceId)));
        }
        return this.getById(roleId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysRoleVO createCustomRole(String workspaceId, CreateCustomRoleCmd cmd) {
        String code = cmd.getRoleCode().trim();
        if (WorkspaceRoleConstants.isReservedRoleCode(code)) {
            throw new BusinessException(I18nUtils.getMessage("role.code.reserved"));
        }
        long dup = this.count(new QueryWrapper()
                .where(SYS_ROLE.WORKSPACE_ID.eq(workspaceId))
                .and(SYS_ROLE.ROLE_CODE.eq(code)));
        if (dup > 0) {
            throw new BusinessException(I18nUtils.getMessage("role.code.exists"));
        }
        validatePermissionCodes(cmd.getPermissions());

        CreateRoleCmd inner = new CreateRoleCmd();
        inner.setWorkspaceId(workspaceId);
        inner.setRoleCode(code);
        inner.setRoleName(cmd.getRoleName().trim());
        inner.setDescription(cmd.getDescription());
        inner.setRoleType(WorkspaceRoleConstants.TYPE_CUSTOM);
        SysRole role = createRole(inner);
        rolePermissionService.replacePermissions(role.getId(), role.getRoleCode(), cmd.getPermissions());
        return toDetailVo(role);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysRoleVO updateCustomRole(String workspaceId, Long roleId, UpdateCustomRoleCmd cmd) {
        SysRole role = requireCustomRole(workspaceId, roleId);
        validatePermissionCodes(cmd.getPermissions());

        role.setRoleName(cmd.getRoleName().trim());
        role.setDescription(cmd.getDescription());
        this.updateById(role);

        rolePermissionService.replacePermissions(role.getId(), role.getRoleCode(), cmd.getPermissions());
        return toDetailVo(getById(role.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCustomRole(String workspaceId, Long roleId) {
        SysRole role = requireCustomRole(workspaceId, roleId);
        long members = workspaceMemberService.countByWorkspaceAndRoleId(workspaceId, roleId);
        if (members > 0) {
            throw new BusinessException(I18nUtils.getMessage("role.in.use"));
        }
        rolePermissionService.removeByRoleId(roleId);
        this.removeById(roleId);
    }

    @Override
    public SysRoleVO getRoleDetail(String workspaceId, Long roleId) {
        SysRole role = this.getOne(new QueryWrapper()
                .where(SYS_ROLE.ID.eq(roleId))
                .and(SYS_ROLE.WORKSPACE_ID.eq(workspaceId)));
        if (role == null) {
            throw new BusinessException(I18nUtils.getMessage("role.not.exist"));
        }
        return toDetailVo(role);
    }

    private SysRole requireCustomRole(String workspaceId, Long roleId) {
        SysRole role = this.getOne(new QueryWrapper()
                .where(SYS_ROLE.ID.eq(roleId))
                .and(SYS_ROLE.WORKSPACE_ID.eq(workspaceId)));
        if (role == null) {
            throw new BusinessException(I18nUtils.getMessage("role.not.exist"));
        }
        if (!Objects.equals(role.getRoleType(), WorkspaceRoleConstants.TYPE_CUSTOM)) {
            throw new BusinessException(I18nUtils.getMessage("role.system.cannot.edit"));
        }
        return role;
    }

    private void validatePermissionCodes(List<String> codes) {
        Set<String> allowed = permissionService.getAll().stream()
                .map(SysPermission::getPermissionCode)
                .collect(Collectors.toSet());
        for (String c : codes) {
            if (!allowed.contains(c)) {
                throw new BusinessException(I18nUtils.getMessage("role.permission.invalid", new Object[]{c}));
            }
        }
    }

    private SysRoleVO toDetailVo(SysRole role) {
        SysRoleVO vo = converter.convert(role, SysRoleVO.class);
        vo.setPermissions(rolePermissionService.getPermissionCodesByRoleId(role.getId()));
        return vo;
    }
}
