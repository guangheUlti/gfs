package com.guanghe.fs.log.domain.event;

import com.guanghe.fs.framework.common.enums.LoginType;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.io.Serial;

/**
 *
 *
 * @Author: guanghe
 * @Date: 2025/9/25 14:45
 */
@Getter
public class CreateLoginLogEvent extends ApplicationEvent {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户ID
     */
    private final String userId;
    /**
     * 用户名
     */
    private final String username;
    /**
     * 登录IP地址
     */
    private final String loginIp;
    /**
     * 登录地址
     */
    private final String loginAddress;
    /**
     * 登录方式
     */
    private LoginType loginType;
    /**
     * 浏览器类型
     */
    private final String browser;
    /**
     * 操作系统
     */
    private final String os;
    /**
     * 登录状态 0成功 1失败
     */
    private final Integer status;
    /**
     * 提示消息
     */
    private final String msg;
    /**
     * User-Agent
     */
    private final String userAgent;

    public CreateLoginLogEvent(Object source, String userId, String username, String loginIp,
                               String loginAddress, LoginType loginType, String browser, String os, Integer status,
                               String msg, String userAgent) {
        super(source);
        this.userId = userId;
        this.username = username;
        this.loginIp = loginIp;
        this.loginAddress = loginAddress;
        this.loginType = loginType;
        this.browser = browser;
        this.os = os;
        this.status = status;
        this.msg = msg;
        this.userAgent = userAgent;
    }

    /**
     * 构建登录成功事件
     */
    public static CreateLoginLogEvent success(Object source, String userId, String username,
                                              String loginIp, String loginAddress, LoginType loginType, String browser,
                                              String os, String userAgent) {
        return new CreateLoginLogEvent(source, userId, username, loginIp, loginAddress, loginType,
                browser, os, 0, "登录成功", userAgent);
    }

    /**
     * 构建登录失败事件
     */
    public static CreateLoginLogEvent failure(Object source, String username, String loginIp,
                                              String loginAddress, LoginType loginType, String browser, String os,
                                              String msg, String userAgent) {
        return new CreateLoginLogEvent(source, null, username, loginIp, loginAddress, loginType,
                browser, os, 1, msg, userAgent);
    }
}
