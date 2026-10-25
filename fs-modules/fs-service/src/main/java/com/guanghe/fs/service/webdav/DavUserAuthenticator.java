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
 * Basic 认证的用户校验 + Sa-Token 桥接（WebDAV 与 SFTP/FTP 共用）。
 * <p>
 * Basic 认证每个请求都发生；sa-token is-concurrent=true 下每请求 login 会堆积会话，
 * 所以按用户缓存 token，TTL 30 分钟（active-timeout 3600s 且每次请求会刷新活跃时间，复用安全）。
 * <p>
 * 防暴力：与 Web 登录共用 {@link LoginGuardService} 的「账号+IP」失败计数与锁定键——
 * 连续失败达到上限后，该账号在该 IP 上直接认证失败（WebDAV 回 401，SFTP/FTP 回认证失败），
 * 锁定期间不再校验密码、也不再延长锁定期，成功后清零。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DavUserAuthenticator {

    /** token 缓存 TTL（毫秒）：30 分钟 */
    private static final long TOKEN_TTL_MS = 30 * 60 * 1000L;

    /**
     * 凭证缓存 TTL（毫秒）：10 分钟。
     * <p>
     * Basic 认证让每个请求都重放一次密码，而 BCrypt(cost=12) 单次约 200ms：挂载后每次
     * PROPFIND / GET 都要付这笔账（实测 /dav 请求 210ms，无认证的普通接口只要 5ms）。
     * 命中条件除了「同用户名+同密码摘要」，还要求库里密码哈希未变，改密码立即失效。
     */
    private static final long CREDENTIAL_TTL_MS = 10 * 60 * 1000L;

    /** 凭证缓存上限（线程数×挂载点量级，超过后先清过期再满则清空） */
    private static final int CREDENTIAL_CACHE_MAX = 512;

    private final com.guanghe.fs.system.mapper.SysUserMapper userMapper;
    private final com.guanghe.fs.system.auth.PasswordHashService passwordHashService;
    private final com.guanghe.fs.system.auth.LoginGuardService loginGuardService;

    /** userId -> 缓存的 token */
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    /** 凭证摘要(sha256(username\0password)) -> 已验证记录 */
    private final Map<String, CachedCredential> credentialCache = new ConcurrentHashMap<>();

    /**
     * 校验用户名密码；成功返回用户，失败返回 null（不区分「用户不存在」与「密码错误」，防枚举）。
     * 失败会累加防暴力计数，锁定期间直接返回 null（不泄漏是锁定还是密码错）。
     *
     * @param clientIp 客户端 IP（WebDAV 取请求 remoteAddr，SFTP 取会话对端地址，FTP 取不到传 null）
     */
    public SysUser authenticate(String username, String rawPassword, String clientIp) {
        if (username == null || rawPassword == null) {
            return null;
        }
        // 防暴力：锁定期内直接失败，不查库、不做 BCrypt（避免锁定期间被当成哈希计算器刷 CPU）
        if (loginGuardService.isLoginLocked(username, clientIp)) {
            log.warn("协议认证被拒绝：账号处于锁定状态, username={}, ip={}", username, clientIp);
            return null;
        }
        SysUser user = userMapper.selectOneByQuery(
                com.mybatisflex.core.query.QueryWrapper.create()
                        .where(com.guanghe.fs.system.domain.table.SysUserTableDef.SYS_USER.USERNAME.eq(username)));
        if (user == null) {
            loginGuardService.recordLoginFailure(username, clientIp);
            return null;
        }
        // 账号状态异常是业务拒绝，不计入密码试错次数（与 Web 登录策略一致）
        if (user.getStatus() == null || user.getStatus() != UserStatus.NORMAL) {
            return null;
        }
        if (!verifyPassword(username, rawPassword, user)) {
            loginGuardService.recordLoginFailure(username, clientIp);
            return null;
        }
        loginGuardService.clearLoginFailures(username, clientIp);
        return user;
    }

    /**
     * 密码校验（带凭证缓存）。
     * <p>
     * 缓存命中仍要求库里密码哈希与缓存时的哈希一致，因此管理员/用户改密码后旧缓存条目
     * 自然失效，不需要额外广播；代价只是每请求一次按主键等值查询（约 1ms）。
     */
    private boolean verifyPassword(String username, String rawPassword, SysUser user) {
        String passwordHash = user.getPassword();
        String digest = cn.dev33.satoken.secure.SaSecureUtil.sha256(username + "\u0000" + rawPassword);
        long now = System.currentTimeMillis();
        CachedCredential cached = credentialCache.get(digest);
        if (cached != null && cached.expireAt() > now
                && cached.userId().equals(user.getId())
                && cached.passwordHash().equals(passwordHash)) {
            return true;
        }
        if (cached != null) {
            credentialCache.remove(digest, cached);
        }
        if (!passwordHashService.matches(rawPassword, passwordHash)) {
            return false;
        }
        if (credentialCache.size() >= CREDENTIAL_CACHE_MAX) {
            credentialCache.values().removeIf(item -> item.expireAt() <= now);
            if (credentialCache.size() >= CREDENTIAL_CACHE_MAX) {
                credentialCache.clear();
            }
        }
        credentialCache.put(digest, new CachedCredential(user.getId(), passwordHash, now + CREDENTIAL_TTL_MS));
        return true;
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

    private record CachedCredential(String userId, String passwordHash, long expireAt) {
    }
}
