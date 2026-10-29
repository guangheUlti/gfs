package com.guanghe.fs.system.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.framework.common.constant.CommonConstant;
import com.guanghe.fs.framework.common.domain.PageResult;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.storage.plugin.boot.StoragePluginManager;
import com.guanghe.fs.system.auth.LoginGuardService;
import com.guanghe.fs.system.auth.PasswordHashService;
import com.guanghe.fs.system.constant.UserStatus;
import com.guanghe.fs.system.domain.SysUser;
import com.guanghe.fs.system.domain.dto.AdminResetPasswordCmd;
import com.guanghe.fs.system.domain.dto.AdminUserQry;
import com.guanghe.fs.system.domain.dto.AdminUserUpdateCmd;
import com.guanghe.fs.system.domain.vo.AdminUserVO;
import com.guanghe.fs.system.domain.vo.UserDataSummaryVO;
import com.guanghe.fs.system.service.AdminUserManageService;
import com.guanghe.fs.system.service.SessionAdminService;
import com.guanghe.fs.system.service.SysUserService;
import com.guanghe.fs.system.service.UserDataPurgeService;
import com.guanghe.fs.system.util.AvatarUtils;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.guanghe.fs.system.domain.table.SysUserTableDef.SYS_USER;

/**
 * 系统管理员账号管理实现
 *
 * @Author: guangheUlti
 * @Date: 2026/10/29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserManageServiceImpl implements AdminUserManageService {

    private final SysUserService sysUserService;

    private final SessionAdminService sessionAdminService;

    private final LoginGuardService loginGuardService;

    private final PasswordHashService passwordHashService;

    private final StoragePluginManager pluginManager;

    private final CacheManager cacheManager;

    private final ObjectProvider<UserDataPurgeService> userDataPurgeServiceProvider;

    /** 系统管理员用户名（唯一超级管理员，由配置指定） */
    @Value("${security.super-admin.username:admin}")
    private String superAdminUsername;

    @Override
    public PageResult<AdminUserVO> listUsers(AdminUserQry qry) {
        sysUserService.assertSuperAdmin();

        int page = qry.getPage() == null || qry.getPage() < 1 ? 1 : qry.getPage();
        int pageSize = qry.getPageSize() == null ? 10 : Math.min(Math.max(qry.getPageSize(), 1), 100);

        QueryWrapper wrapper = new QueryWrapper();
        String keyword = qry.getKeyword() == null ? null : qry.getKeyword().trim();
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.and(SYS_USER.USERNAME.like(keyword).or(SYS_USER.NICKNAME.like(keyword)));
        }
        if (qry.getStatus() != null) {
            wrapper.and(SYS_USER.STATUS.eq(qry.getStatus()));
        }
        // 创建时间相同的账号（批量建号）用 ID 兜底，保证翻页顺序稳定
        wrapper.orderBy(SYS_USER.CREATED_AT, false).orderBy(SYS_USER.ID, false);

        Page<SysUser> result = sysUserService.page(new Page<>(page, pageSize), wrapper);

        Map<String, Integer> onlineCounts = onlineTerminalCounts();
        Map<String, LoginGuardService.LockedAccount> lockedAccounts = loginGuardService.listLockedAccounts()
                .stream()
                .collect(Collectors.toMap(
                        LoginGuardService.LockedAccount::account,
                        Function.identity(),
                        (left, right) -> left));

        List<AdminUserVO> records = result.getRecords().stream()
                .map(user -> toVO(user, onlineCounts, lockedAccounts))
                .toList();
        return PageResult.success(records, result.getTotalRow());
    }

    @Override
    public void updateProfile(String userId, AdminUserUpdateCmd cmd) {
        sysUserService.assertSuperAdmin();
        SysUser user = requireUser(userId);
        user.setNickname(cmd.getNickname().trim());
        sysUserService.updateById(user);
        evictUserCache(userId);
    }

    @Override
    public void setEnabled(String userId, boolean enabled) {
        sysUserService.assertSuperAdmin();
        SysUser user = requireUser(userId);
        assertNotSelf(user);
        assertNotSuperAdmin(user);

        int current = user.getStatus() == null ? UserStatus.NORMAL : user.getStatus();
        if (current != UserStatus.NORMAL && current != UserStatus.DISABLED) {
            throw new BusinessException(I18nUtils.getMessage("admin.user.status.invalid"));
        }
        user.setStatus(enabled ? UserStatus.NORMAL : UserStatus.DISABLED);
        sysUserService.updateById(user);
        evictUserCache(userId);
        if (!enabled) {
            // 禁用后立即踢掉所有在线会话，避免已有 token 继续访问
            StpUtil.kickout(userId);
        }
    }

    @Override
    public void resetPassword(String userId, AdminResetPasswordCmd cmd) {
        sysUserService.assertSuperAdmin();
        SysUser user = requireUser(userId);
        // 自己的密码在「个人资料」里改，避免管理员误操作把自己踢下线
        assertNotSelf(user);

        user.setPassword(passwordHashService.encode(cmd.getPassword()));
        sysUserService.updateById(user);
        evictUserCache(userId);
        StpUtil.kickout(userId);
    }

    @Override
    public UserDataSummaryVO getUserDataSummary(String userId) {
        sysUserService.assertSuperAdmin();
        requireUser(userId);
        return requirePurger().summarize(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(String userId) {
        sysUserService.assertSuperAdmin();
        SysUser user = requireUser(userId);
        assertNotSelf(user);
        assertNotSuperAdmin(user);

        requirePurger().purge(userId);
        deleteAvatar(userId);
        loginGuardService.unlockAccount(user.getUsername());
        sysUserService.removeById(userId);
        evictUserCache(userId);
        StpUtil.kickout(userId);
    }

    private AdminUserVO toVO(SysUser user,
                             Map<String, Integer> onlineCounts,
                             Map<String, LoginGuardService.LockedAccount> lockedAccounts) {
        AdminUserVO vo = new AdminUserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatar(AvatarUtils.resolve(user.getAvatar(), pluginManager::getLocalInstance));
        vo.setStatus(user.getStatus());
        vo.setCreatedAt(user.getCreatedAt());
        vo.setLastLoginAt(user.getLastLoginAt());
        vo.setSuperAdmin(isSuperAdmin(user));
        vo.setOnlineTerminals(onlineCounts.getOrDefault(user.getId(), 0));
        LoginGuardService.LockedAccount locked = lockedAccounts.get(user.getUsername());
        if (locked != null) {
            vo.setLocked(true);
            vo.setLockRemainingSeconds(locked.remainingSeconds());
        }
        return vo;
    }

    /** 在线终端数按账号汇总，避免逐行扫 Redis */
    private Map<String, Integer> onlineTerminalCounts() {
        return sessionAdminService.listOnlineSessions(null).stream()
                .collect(Collectors.toMap(
                        vo -> vo.getLoginId(),
                        vo -> vo.getTerminals() == null ? 0 : vo.getTerminals().size(),
                        (left, right) -> left));
    }

    private SysUser requireUser(String userId) {
        SysUser user = sysUserService.getById(userId);
        if (user == null) {
            throw new BusinessException(I18nUtils.getMessage("admin.user.not.exist"));
        }
        return user;
    }

    private void assertNotSelf(SysUser user) {
        if (Objects.equals(user.getId(), StpUtil.getLoginIdAsString())) {
            throw new BusinessException(I18nUtils.getMessage("admin.user.self.protected"));
        }
    }

    private void assertNotSuperAdmin(SysUser user) {
        if (isSuperAdmin(user)) {
            throw new BusinessException(I18nUtils.getMessage("admin.user.super-admin.protected"));
        }
    }

    private boolean isSuperAdmin(SysUser user) {
        return user.getUsername() != null && user.getUsername().equals(superAdminUsername);
    }

    private UserDataPurgeService requirePurger() {
        UserDataPurgeService purger = userDataPurgeServiceProvider.getIfAvailable();
        if (purger == null) {
            throw new BusinessException(I18nUtils.getMessage("admin.user.purge.unavailable"));
        }
        return purger;
    }

    /** 头像存放在本地存储 avatar/{userId} 目录下，随账号一起删除 */
    private void deleteAvatar(String userId) {
        try {
            pluginManager.getLocalInstance()
                    .deleteDirectory(CommonConstant.AVATAR_SAVE_PATH + "/" + userId);
        } catch (Exception e) {
            log.warn("删除用户头像目录失败: userId={}, error={}", userId, e.getMessage());
        }
    }

    private void evictUserCache(String userId) {
        Objects.requireNonNull(cacheManager.getCache("user")).evict(userId);
    }
}
