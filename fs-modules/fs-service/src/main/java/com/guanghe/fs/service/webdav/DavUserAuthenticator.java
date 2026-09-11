package com.guanghe.fs.service.webdav;

import cn.dev33.satoken.stp.StpUtil;
import com.guanghe.fs.system.constant.UserStatus;
import com.guanghe.fs.system.domain.SysUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Basic 认证的用户校验 + Sa-Token 桥接（WebDAV 与 SFTP 共用）。
 * <p>
 * Basic 认证每个请求都发生；sa-token is-concurrent=true 下每请求 login 会堆积会话，
 * 所以按用户缓存 token，TTL 30 分钟（active-timeout 3600s 且每次请求会刷新活跃时间，复用安全）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DavUserAuthenticator {

    /** token 缓存 TTL（毫秒）：30 分钟 */
    private static final long TOKEN_TTL_MS = 30 * 60 * 1000L;

    private final com.guanghe.fs.system.mapper.SysUserMapper userMapper;
    private final com.guanghe.fs.system.auth.PasswordHashService passwordHashService;

    /** userId -> 缓存的 token */
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    /**
     * 校验用户名密码；成功返回用户，失败返回 null（不区分「用户不存在」与「密码错误」，防枚举）
     */
    public SysUser authenticate(String username, String rawPassword) {
        if (username == null || rawPassword == null) {
            return null;
        }
        SysUser user = userMapper.selectOneByQuery(
                com.mybatisflex.core.query.QueryWrapper.create()
                        .where(com.guanghe.fs.system.domain.table.SysUserTableDef.SYS_USER.USERNAME.eq(username)));
        if (user == null) {
            return null;
        }
        // 账号状态必须正常
        if (user.getStatus() == null || user.getStatus() != UserStatus.NORMAL) {
            return null;
        }
        return passwordHashService.matches(rawPassword, user.getPassword()) ? user : null;
    }

    /**
     * 取（或创建）指定用户的可用 token，并设置到当前线程的 Sa-Token 上下文。
     * WebDAV 请求跑在 Tomcat 线程上，已有 Spring 请求上下文，直接 setTokenValue 即可。
     */
    public String bridgeSaToken(String userId) {
        CachedToken cached = tokenCache.get(userId);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expireAt > now) {
            StpUtil.setTokenValue(cached.token);
            return cached.token;
        }
        if (cached != null) {
            tokenCache.remove(userId);
        }
        // is-concurrent=true：新登录不挤掉用户已有会话
        StpUtil.login(userId);
        String token = StpUtil.getTokenValue();
        tokenCache.put(userId, new CachedToken(token, now + TOKEN_TTL_MS));
        return token;
    }

    private record CachedToken(String token, long expireAt) {
    }
}
