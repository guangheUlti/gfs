package com.guanghe.fs.system.aspect;

import com.guanghe.fs.framework.common.enums.LoginType;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.Ip2RegionUtils;
import com.guanghe.fs.framework.common.utils.IpUtils;
import com.guanghe.fs.log.domain.event.CreateLoginLogEvent;
import com.guanghe.fs.system.domain.dto.LoginCmd;
import com.guanghe.fs.system.domain.vo.LoginResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 登录日志切面
 *
 * @Author: guanghe
 * @Date: 2025/9/25 14:35
 */
@Slf4j
@Aspect
@Component
@Order(1)
@RequiredArgsConstructor
public class LoginLogAspect {
    private final ApplicationEventPublisher eventPublisher;

    @Pointcut("@annotation(com.guanghe.fs.log.annotation.LoginLog)")
    public void loginLogPointcut() {
    }

    @Around("loginLogPointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Object[] args = joinPoint.getArgs();
        LoginCmd cmd = null;
        if (args != null && args.length > 0 && args[0] instanceof LoginCmd) {
            cmd = (LoginCmd) args[0];
        }

        // 获取登录相关信息
        String ip = IpUtils.getIpAddr();
        String address = Ip2RegionUtils.search(ip);
        String browser = IpUtils.getBrowser();
        String os = IpUtils.getOs();
        String userAgent = IpUtils.getUserAgent();

        // 获取用户名和登陆方式
        String username = (cmd != null) ? cmd.getAccount() : "unknown";
        LoginType loginType = (cmd != null) ? cmd.getLoginType() : LoginType.password;

        long startTime = System.currentTimeMillis();

        try {
            // 执行目标方法
            Object result = joinPoint.proceed();

            // 登录成功，从返回值中提取用户信息
            if (result instanceof LoginResult loginResult) {
                String userId = loginResult.getId() != null ? loginResult.getId() : null;
                String actualUsername = loginResult.getUsername() != null ? loginResult.getUsername() : username;

                // 发布登录成功事件
                eventPublisher.publishEvent(
                        CreateLoginLogEvent.success(this, userId, actualUsername, ip, address, loginType, browser, os, userAgent)
                );

                long endTime = System.currentTimeMillis();
                log.info("登录成功: 用户[{}], IP[{}], 耗时[{}ms]", actualUsername, ip, endTime - startTime);
            }

            return result;

        } catch (Exception e) {
            // 系统异常
            publishFailureEvent(username, ip, address, loginType, browser, os, userAgent, e.getMessage());

            long endTime = System.currentTimeMillis();
            log.error("登录异常: 用户[{}], IP[{}], 耗时[{}ms]",
                    username, ip, endTime - startTime, e);
            //重新抛出去让统一异常处理器处理
            throw new BusinessException(e.getMessage());
        }
    }

    /**
     * 发布登录失败事件
     */
    private void publishFailureEvent(String username, String ip, String address, LoginType loginType,
                                     String browser, String os, String userAgent, String message) {
        eventPublisher.publishEvent(
                CreateLoginLogEvent.failure(this, username, ip, address, loginType, browser, os, message, userAgent)
        );
    }
}