package com.guanghe.fs.system.auth.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.guanghe.fs.framework.common.enums.LoginType;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.system.auth.LoginStrategy;
import com.guanghe.fs.system.auth.LoginGuardService;
import com.guanghe.fs.system.auth.PasswordHashService;
import com.guanghe.fs.system.constant.UserStatus;
import com.guanghe.fs.system.domain.SysUser;
import com.guanghe.fs.system.domain.dto.LoginCmd;
import com.guanghe.fs.system.domain.vo.LoginResult;
import com.guanghe.fs.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

import static com.guanghe.fs.system.domain.table.SysUserTableDef.SYS_USER;

/**
 * 账号密码登录策略
 *
 * @Author: guangheUlti
 * @Date: 2026/4/2 09:59
 */
@Component
@RequiredArgsConstructor
public class PasswordLoginStrategy implements LoginStrategy {

    private final SysUserMapper userMapper;

    private final PasswordHashService passwordHashService;

    private final LoginGuardService loginGuardService;

    private final static String emailRegex = "^[A-Za-z0-9+_.-]+@(.+)$";

    @Override
    public LoginType getLoginType() {
        return LoginType.password;
    }

    @Override
    public LoginResult authenticate(LoginCmd cmd) {
        String account = cmd.getAccount();
        String password = cmd.getPassword();
        // 防暴力：账号+IP 锁定期内直接拒绝
        loginGuardService.checkLoginAllowed(account);
        QueryWrapper queryWrapper = new QueryWrapper();
        if (account.matches(emailRegex)) {
            queryWrapper.where(SYS_USER.EMAIL.eq(account));
        } else {
            queryWrapper.where(SYS_USER.USERNAME.eq(account));
        }
        SysUser user = userMapper.selectOneByQuery(queryWrapper);
        if (user == null) {
            loginGuardService.recordLoginFailure(account);
            throw new BusinessException(I18nUtils.getMessage("user.account.or.password.incorrect"));
        }
        // 禁用/待审核/已拒绝均为业务拒绝，不计入密码试错次数
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new BusinessException(I18nUtils.getMessage("user.disabled"));
        }
        if (user.getStatus() == UserStatus.PENDING_REVIEW) {
            throw new BusinessException(I18nUtils.getMessage("user.pending.review"));
        }
        if (user.getStatus() == UserStatus.REJECTED) {
            throw new BusinessException(I18nUtils.getMessage("user.register.rejected"));
        }
        if (!passwordHashService.matches(password, user.getPassword())) {
            loginGuardService.recordLoginFailure(account);
            throw new BusinessException(I18nUtils.getMessage("user.account.or.password.incorrect"));
        }

        loginGuardService.clearLoginFailures(account);

        if (passwordHashService.isLegacyHash(user.getPassword())) {
            user.setPassword(passwordHashService.encode(password));
        }

        LoginResult loginResult = new LoginResult();
        loginResult.setId(user.getId());
        loginResult.setUsername(user.getUsername());
        //修改最后登录时间
        user.setLastLoginAt(LocalDateTime.now());
        userMapper.update(user);
        return loginResult;
    }
}
