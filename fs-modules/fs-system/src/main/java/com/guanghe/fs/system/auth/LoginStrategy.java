package com.guanghe.fs.system.auth;

import com.guanghe.fs.framework.common.enums.LoginType;
import com.guanghe.fs.system.domain.dto.LoginCmd;
import com.guanghe.fs.system.domain.vo.LoginResult;

/**
 * 登录策略接口
 *
 * @Author: guangheUlti
 * @Date: 2026/4/2 09:59
 */
public interface LoginStrategy {

    /**
     * 获取登录类型
     *
     * @return 登录类型
     */
    LoginType getLoginType();


    /**
     * 登录认证
     *
     * @param cmd 登录参数
     * @return 登录结果
     */
    LoginResult authenticate(LoginCmd cmd);
}
