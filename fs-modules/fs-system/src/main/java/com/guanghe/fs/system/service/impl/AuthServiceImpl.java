package com.guanghe.fs.system.service.impl;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import com.guanghe.fs.framework.common.utils.IpUtils;
import com.guanghe.fs.log.annotation.LoginLog;
import com.guanghe.fs.system.auth.LoginStrategy;
import com.guanghe.fs.system.auth.LoginStrategyFactory;
import com.guanghe.fs.system.constant.TerminalExtraKey;
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

        // 登录时把本次的登录环境写到终端记录上：is-share=false 时同一账号每个终端都是独立 token，
        // 不记则全是一样的默认值，「登录管理」里管理员无法判断该踢哪一个（见 SessionAdminServiceImpl）
        StpUtil.login(result.getId(), StpUtil.createSaLoginParameter()
                .setTerminalExtra(TerminalExtraKey.IP, IpUtils.getIpAddr())
                .setTerminalExtra(TerminalExtraKey.BROWSER, IpUtils.getBrowser())
                .setTerminalExtra(TerminalExtraKey.OS, IpUtils.getOs()));
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
