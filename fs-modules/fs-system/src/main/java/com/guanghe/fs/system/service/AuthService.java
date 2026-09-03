package com.guanghe.fs.system.service;

import com.guanghe.fs.system.domain.dto.LoginCmd;
import com.guanghe.fs.system.domain.vo.LoginResult;

/**
 * 认证服务接口
 *
 * @Author: guangheUlti
 * @Date: 2024/10/16 14:25
 */
public interface AuthService {

    /**
     * 登录
     *
     * @param cmd
     * @return
     */
    LoginResult doLogin(LoginCmd cmd);

    /**
     * 退出登录
     */
    void logout();
}
