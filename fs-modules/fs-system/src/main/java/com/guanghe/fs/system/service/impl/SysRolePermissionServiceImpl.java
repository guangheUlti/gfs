package com.guanghe.fs.system.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.guanghe.fs.system.domain.SysRolePermission;
import com.guanghe.fs.system.domain.dto.BatchCreateRolePermissionsCmd;
import com.guanghe.fs.system.mapper.SysRolePermissionMapper;
import com.guanghe.fs.system.service.SysRolePermissionService;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

import static com.guanghe.fs.system.domain.table.SysRolePermissionTableDef.SYS_ROLE_PERMISSION;

/**
 * 角色权限关联服务实现类
 *
 * @Author: guangheUlti
 * @Date: 2026/3/30 10:11
 */
@Service
public class SysRolePermissionServiceImpl extends ServiceImpl<SysRolePermissionMapper, SysRolePermission> implements SysRolePermissionService {

    @Override
    @CacheEvict(value = "rolePermissions", key = "#cmd.roleId")
    public void batchCreateRolePermissions(BatchCreateRolePermissionsCmd cmd) {
        for (String permissionCode : cmd.getPermissionCodes()) {
            SysRolePermission rp = new SysRolePermission();
            rp.setRoleId(cmd.getRoleId());
            rp.setRoleCode(cmd.getRoleCode());
            rp.setPermissionCode(permissionCode);
            this.save(rp);
        }
    }

    @Override
    @Cacheable(value = "rolePermissions", key = "#roleId", unless = "#result == null || #result.isEmpty()")
    public List<String> getPermissionCodesByRoleId(Long roleId) {
        List<SysRolePermission> rolePermissions = this.list(
                new QueryWrapper().where(SYS_ROLE_PERMISSION.ROLE_ID.eq(roleId))
        );
        return rolePermissions.stream()
                .map(SysRolePermission::getPermissionCode)
                .collect(Collectors.toList());
    }

    @Override
    @CacheEvict(value = "rolePermissions", key = "#roleId")
    public void removeByRoleId(Long roleId) {
        this.remove(new QueryWrapper().where(SYS_ROLE_PERMISSION.ROLE_ID.eq(roleId)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replacePermissions(Long roleId, String roleCode, List<String> permissionCodes) {
        removeByRoleId(roleId);
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return;
        }
        BatchCreateRolePermissionsCmd cmd = new BatchCreateRolePermissionsCmd();
        cmd.setRoleId(roleId);
        cmd.setRoleCode(roleCode);
        cmd.setPermissionCodes(permissionCodes);
        batchCreateRolePermissions(cmd);
    }
}
