package com.guanghe.fs.system.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.io.FileUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.guanghe.fs.framework.common.constant.CommonConstant;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.storage.plugin.boot.StoragePluginManager;
import com.guanghe.fs.storage.plugin.core.IStorageOperationService;
import com.guanghe.fs.system.domain.SysUser;
import com.guanghe.fs.system.domain.dto.*;
import com.guanghe.fs.system.domain.vo.PendingUserVO;
import com.guanghe.fs.system.domain.vo.SysUserVO;
import com.guanghe.fs.system.mapper.SysUserMapper;
import com.guanghe.fs.system.constant.UserPermissions;
import com.guanghe.fs.system.constant.UserStatus;
import com.guanghe.fs.system.auth.PasswordHashService;
import com.guanghe.fs.system.auth.LoginGuardService;
import com.guanghe.fs.system.service.SysUserService;
import com.guanghe.fs.system.service.SysUserTransferSettingService;
import io.github.linpeilie.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Objects;
import java.util.List;
import java.util.Locale;

import static com.guanghe.fs.system.domain.table.SysUserTableDef.SYS_USER;

/**
 * 用户表 服务实现类
 *
 * @Author: guangheUlti
 * @Date: 2024/6/7 11:14
 */
@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    private final Converter converter;

    private final CacheManager cacheManager;

    private final SysUserTransferSettingService userTransferSettingService;

    private final StoragePluginManager pluginManager;

    private final PasswordHashService passwordHashService;

    private final LoginGuardService loginGuardService;

    /** 系统管理员用户名（用于注册审核等全局管理权限判定） */
    @Value("${security.super-admin.username:admin}")
    private String superAdminUsername;

    @Override
    public SysUser getByUsername(String username) {

        return this.getOne(new QueryWrapper().where(SYS_USER.USERNAME.eq(username)));
    }

    @Override
    @Cacheable(value = "user", keyGenerator = "userKeyGenerator")
    public SysUserVO getDetail() {
        String userId = StpUtil.getLoginIdAsString();
        SysUser user = this.getById(userId);
        SysUserVO userVO = converter.convert(user, SysUserVO.class);
        if (user != null) {
            // 设置用户是否已设置密码
            userVO.setIsSetPassword(user.getPassword() != null);
            // 是否系统管理员（用户名与配置一致）
            boolean superAdmin = user.getUsername() != null && user.getUsername().equals(superAdminUsername);
            userVO.setIsSuperAdmin(superAdmin);
            // 用户级权限，与 StpInterfaceImpl 的鉴权口径保持一致
            userVO.setPermissions(UserPermissions.of(superAdmin));
        }
        return userVO;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void register(UserRegisterCmd cmd) {
        // 防暴力：同一 IP 注册失败过多后禁止注册
        loginGuardService.checkRegisterAllowed();
        try {
            SysUser user = this.getByUsername(cmd.getUsername());
            if (user != null) {
                throw new BusinessException(I18nUtils.getMessage("user.username.exists"));
            }
            if (!cmd.getPassword().equals(cmd.getConfirmPassword())) {
                throw new BusinessException(I18nUtils.getMessage("user.password.not.match"));
            }
            user = new SysUser();
            user.setUsername(cmd.getUsername());
            user.setPassword(passwordHashService.encode(cmd.getPassword()));
            user.setEmail(cmd.getEmail().trim().toLowerCase(Locale.ROOT));
            user.setNickname(cmd.getNickname());
            user.setAvatar(cmd.getAvatar());
            // 新注册账号需管理员审核通过后才允许登录
            user.setStatus(UserStatus.PENDING_REVIEW);
            this.save(user);

            // 初始化用户传输配置
            userTransferSettingService.initUserTransferSetting(user.getId());
        } catch (BusinessException e) {
            loginGuardService.recordRegisterFailure();
            throw e;
        }
        loginGuardService.clearRegisterFailures();
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    @CacheEvict(value = "user", keyGenerator = "userKeyGenerator")
    public void editUserInfo(UserEditInfoCmd cmd) {
        String userId = StpUtil.getLoginIdAsString();
        SysUser existUser = this.getById(userId);
        if (existUser == null) {
            throw new BusinessException(I18nUtils.getMessage("user.not.exist"));
        }
        existUser.setNickname(cmd.getNickname());
        this.updateById(existUser);
    }

    @Override
    public void uploadAvatar(MultipartFile file) {
        String userId = StpUtil.getLoginIdAsString();
        SysUser existUser = this.getById(userId);
        if (existUser == null) {
            throw new BusinessException(I18nUtils.getMessage("user.not.exist"));
        }

        String avatarUrl;
        try {
            IStorageOperationService storageOperationService = pluginManager.getLocalInstance();
            // 优化路径拼接与命名，防止路径穿越
            String suffix = FileUtil.getSuffix(file.getOriginalFilename());
            String fileName = userId + "_" + System.currentTimeMillis() + "." + suffix;
            String avatarPath = CommonConstant.AVATAR_SAVE_PATH + "/" + userId;

            // 目录创建逻辑可以封装在 storageOperationService 内部
            String objectKey = avatarPath + "/" + fileName;

            storageOperationService.uploadFile(file.getInputStream(), objectKey);
            avatarUrl = storageOperationService.getFileUrl(objectKey, null);
        } catch (Exception e) {
            throw new BusinessException(I18nUtils.getMessage("file.upload.failed"));
        }

        updateUserAvatarInTransaction(existUser, avatarUrl);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateUserAvatarInTransaction(SysUser user, String url) {
        user.setAvatar(url);
        this.updateById(user);
        Objects.requireNonNull(cacheManager.getCache("user")).evict(user.getId());
    }


    @Override
    @CacheEvict(value = "user", keyGenerator = "userKeyGenerator")
    public void updatePassword(PasswordEditCmd cmd) {
        String userId = StpUtil.getLoginIdAsString();
        SysUser user = this.getById(userId);
        if (user == null) {
            throw new BusinessException(I18nUtils.getMessage("user.not.exist"));
        }
        if (!passwordHashService.matches(cmd.getOldPassword(), user.getPassword())) {
            throw new BusinessException(I18nUtils.getMessage("user.password.incorrect"));
        }
        if (!cmd.getNewPassword().equals(cmd.getConfirmPassword())) {
            throw new BusinessException(I18nUtils.getMessage("user.password.not.match"));
        }
        user.setPassword(passwordHashService.encode(cmd.getNewPassword()));
        this.updateById(user);
    }

    @Override
    public void setPassword(PasswordAddCmd cmd) {
        String userId = StpUtil.getLoginIdAsString();
        SysUser user = this.getById(userId);
        if (user == null) {
            throw new BusinessException(I18nUtils.getMessage("user.not.exist"));
        }
        if (user.getPassword() != null && !user.getPassword().isBlank()) {
            throw new BusinessException(I18nUtils.getMessage("user.password.already.set"));
        }
        if (!cmd.getNewPassword().equals(cmd.getConfirmPassword())) {
            throw new BusinessException(I18nUtils.getMessage("user.password.not.match"));
        }
        user.setPassword(passwordHashService.encode(cmd.getNewPassword()));
        this.updateById(user);
    }

    @Override
    public List<PendingUserVO> listPendingUsers() {
        assertSuperAdmin();
        List<SysUser> users = this.list(new QueryWrapper()
                .where(SYS_USER.STATUS.eq(UserStatus.PENDING_REVIEW))
                .orderBy(SYS_USER.CREATED_AT, false));
        return users.stream()
                .map(u -> converter.convert(u, PendingUserVO.class))
                .toList();
    }

    @Override
    public void approveUser(String userId) {
        reviewUser(userId, UserStatus.NORMAL);
    }

    @Override
    public void rejectUser(String userId) {
        reviewUser(userId, UserStatus.REJECTED);
    }

    /**
     * 审核用户：将待审核状态变更为目标状态，并清除目标用户的缓存
     */
    private void reviewUser(String userId, int targetStatus) {
        assertSuperAdmin();
        SysUser user = this.getById(userId);
        if (user == null) {
            throw new BusinessException(I18nUtils.getMessage("admin.user.not.exist"));
        }
        if (user.getStatus() != UserStatus.PENDING_REVIEW) {
            throw new BusinessException(I18nUtils.getMessage("admin.user.not.pending"));
        }
        user.setStatus(targetStatus);
        this.updateById(user);
        Objects.requireNonNull(cacheManager.getCache("user")).evict(userId);
    }

    @Override
    public void assertSuperAdmin() {
        String userId = StpUtil.getLoginIdAsString();
        SysUser user = this.getById(userId);
        if (user == null || user.getUsername() == null
                || !user.getUsername().equals(superAdminUsername)) {
            throw new BusinessException(403, I18nUtils.getMessage("admin.forbidden"));
        }
    }
}
