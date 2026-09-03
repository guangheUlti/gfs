package com.guanghe.fs.log.listener;

import com.guanghe.fs.log.domain.SysLoginLog;
import com.guanghe.fs.log.domain.event.CreateLoginLogEvent;
import com.guanghe.fs.log.service.SysLoginLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 登录事件监听器
 *
 * @Author: guanghe
 * @Date: 2025/9/25 14:35
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginEventListener {

    private final SysLoginLogService sysLoginLogService;

    /**
     * 监听登录事件并记录日志
     */
    @Async
    @EventListener
    public void handleLoginEvent(CreateLoginLogEvent event) {
        try {
            SysLoginLog loginLog = buildLoginLog(event);
            sysLoginLogService.save(loginLog);
            log.info("登录日志记录成功: 用户[{}], IP[{}], 状态[{}], 消息[{}]",
                    event.getUsername(),
                    event.getLoginIp(),
                    event.getStatus() == 0 ? "成功" : "失败",
                    event.getMsg());
        } catch (Exception e) {
            log.error("记录登录日志失败: 用户[{}], IP[{}]",
                    event.getUsername(), event.getLoginIp(), e);
        }
    }

    /**
     * 构建登录日志对象
     */
    private SysLoginLog buildLoginLog(CreateLoginLogEvent event) {
        SysLoginLog loginLog = new SysLoginLog();
        loginLog.setUserId(event.getUserId());
        loginLog.setUsername(event.getUsername());
        loginLog.setLoginIp(event.getLoginIp());
        loginLog.setLoginAddress(event.getLoginAddress());
        loginLog.setLoginType(event.getLoginType());
        loginLog.setBrowser(event.getBrowser());
        loginLog.setOs(event.getOs());
        loginLog.setStatus(event.getStatus());
        loginLog.setMsg(event.getMsg());
        loginLog.setLoginTime(LocalDateTime.now());
        return loginLog;
    }
}

