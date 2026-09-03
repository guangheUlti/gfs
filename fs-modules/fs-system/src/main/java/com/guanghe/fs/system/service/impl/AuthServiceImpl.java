package com.guanghe.fs.system.service.impl;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import com.guanghe.fs.log.annotation.LoginLog;
import com.guanghe.fs.system.auth.LoginStrategy;
import com.guanghe.fs.system.auth.LoginStrategyFactory;
import com.guanghe.fs.system.domain.SysUser;
import com.guanghe.fs.system.domain.dto.LoginCmd;
import com.guanghe.fs.system.domain.vo.LoginResult;
import com.guanghe.fs.system.service.AuthService;
import com.guanghe.fs.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 认证服务实现
 *
 * @Author: guangheUlti
 * @Date: 2024/10/16 14:26
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final LoginStrategyFactory loginStrategyFactory;
    private final SysUserService sysUserService;

    @Override
    @LoginLog()
    public LoginResult doLogin(LoginCmd cmd) {
        LoginStrategy strategy = loginStrategyFactory.getStrategy(cmd.getLoginType());
        LoginResult result = strategy.authenticate(cmd);

        StpUtil.login(result.getId(), cmd.getIsRemember());
        StpUtil.getSession().set("username", result.getUsername());
        SaTokenInfo tokenInfo = StpUtil.getTokenInfo();
        result.setAccessToken(tokenInfo.getTokenValue());
        //修改最后登录时间
        SysUser user = new SysUser();
        user.setId(result.getId());
        user.setLastLoginAt(LocalDateTime.now());
        sysUserService.updateById(user);
        return result;
    }

    @Override
    public void logout() {
        StpUtil.logout();
    }
}
