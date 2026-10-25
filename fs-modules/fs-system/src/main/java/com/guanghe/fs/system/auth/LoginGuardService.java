package com.guanghe.fs.system.auth;

import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.framework.common.utils.IpUtils;
import com.guanghe.fs.framework.redis.repository.RedisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 登录/注册防暴力攻击守卫
 * <p>
 * 基于 Redis 计数器实现：同一账号+IP 连续登录失败达到上限后锁定一段时间；
 * 同一 IP 注册失败达到上限后禁止注册一段时间。锁定期间直接拒绝请求，
 * 登录成功后自动清除计数。
 *
 * @Author: guangheUlti
 * @Date: 2026/9/1
 */
@Service
@RequiredArgsConstructor
public class LoginGuardService {

    private final RedisRepository redisRepository;

    /** 连续失败多少次后锁定 */
    @Value("${security.brute-force.max-attempts:3}")
    private int maxAttempts;

    /** 锁定时长（分钟） */
    @Value("${security.brute-force.lock-minutes:30}")
    private int lockMinutes;

    private static final String KEY_LOGIN_FAIL = "login:guard:fail:";
    private static final String KEY_LOGIN_LOCK = "login:guard:lock:";
    private static final String KEY_REGISTER_FAIL = "register:guard:fail:";
    private static final String KEY_REGISTER_LOCK = "register:guard:lock:";

    /**
     * 登录前检查：账号+IP 是否已被锁定
     */
    public void checkLoginAllowed(String account) {
        if (isLoginLocked(account, IpUtils.getIpAddr())) {
            throw new BusinessException(I18nUtils.getMessage("user.locked"));
        }
    }

    /**
     * 账号+指定 IP 是否处于锁定期。
     * <p>
     * 供 WebDAV / SFTP / FTP 等「非 HTTP 登录表单」的协议端复用：这些协议每次请求都要重新校验
     * Basic 密码，拿不到 HTTP 登录的异常语义，因此只做布尔判定，由调用方转成 401 / 认证失败。
     *
     * @param account  账号（与 Web 登录共用同一计数与锁定键，攻击者无法靠换协议拿额外尝试次数）
     * @param clientIp 客户端 IP；拿不到时传 null（归入 unknown 桶，不与真实 IP 的锁互相污染）
     */
    public boolean isLoginLocked(String account, String clientIp) {
        return Boolean.TRUE.equals(redisRepository.hasKey(KEY_LOGIN_LOCK + guardKey(account, clientIp)));
    }

    /**
     * 记录一次登录失败；达到上限后锁定
     */
    public void recordLoginFailure(String account) {
        recordLoginFailure(account, IpUtils.getIpAddr());
    }

    /**
     * 记录一次登录失败（指定 IP，协议端使用）；达到上限后锁定
     */
    public void recordLoginFailure(String account, String clientIp) {
        String key = KEY_LOGIN_FAIL + guardKey(account, clientIp);
        Long count = redisRepository.incr(key, 1);
        if (count != null && count == 1) {
            redisRepository.expire(key, lockMinutes * 60L);
        }
        if (count != null && count >= maxAttempts) {
            redisRepository.setExpire(KEY_LOGIN_LOCK + guardKey(account, clientIp), maxAttempts, lockMinutes * 60L);
            redisRepository.del(key);
        }
    }

    /**
     * 列当前处于锁定期的账号（供管理端解锁）。
     * <p>
     * 同一账号可能在多个 IP 上被锁，这里按账号聚合：剩余时长取最大，IP 数量累计，
     * 便于管理员一眼看出“谁被锁、锁了多久、来自几个来源”。
     *
     * @return 锁定账号列表（按剩余时长从长到短）
     */
    public List<LockedAccount> listLockedAccounts() {
        Set<String> keys = redisRepository.keys(KEY_LOGIN_LOCK + "*");
        if (keys.isEmpty()) {
            return List.of();
        }
        Map<String, LockedAccount> merged = new LinkedHashMap<>();
        for (String key : keys) {
            Long remain = redisRepository.getExpire(key);
            if (remain == null || remain <= 0) {
                // TTL -1（无过期）或 -2（已消失）：前者异常但不删，后者本就不会出现在 keys 里
                if (remain != null && remain == -1L) {
                    remain = (long) lockMinutes * 60L;
                } else {
                    continue;
                }
            }
            String guardKey = key.substring(KEY_LOGIN_LOCK.length());
            int split = guardKey.lastIndexOf(':');
            if (split <= 0) {
                continue;
            }
            String account = guardKey.substring(0, split);
            String ip = guardKey.substring(split + 1);
            LockedAccount existing = merged.get(account);
            if (existing == null) {
                merged.put(account, new LockedAccount(account, remain, 1, List.of(ip)));
            } else {
                List<String> ips = new ArrayList<>(existing.ips());
                ips.add(ip);
                merged.put(account, new LockedAccount(account,
                        Math.max(existing.remainingSeconds(), remain), ips.size(), ips));
            }
        }
        return merged.values().stream()
                .sorted((a, b) -> Long.compare(b.remainingSeconds(), a.remainingSeconds()))
                .toList();
    }

    /**
     * 解除账号在所有 IP 上的锁定（同时清掉失败计数，避免解锁后一次失败又重新锁）。
     *
     * @return 实际删除的锁定键数量；为 0 表示该账号本来就没被锁
     */
    public int unlockAccount(String account) {
        String normalized = escapeGlob(account == null ? "" : account.trim());
        Set<String> lockKeys = redisRepository.keys(KEY_LOGIN_LOCK + normalized + ":*");
        Set<String> failKeys = redisRepository.keys(KEY_LOGIN_FAIL + normalized + ":*");
        if (!lockKeys.isEmpty()) {
            redisRepository.del(lockKeys.toArray(String[]::new));
        }
        if (!failKeys.isEmpty()) {
            redisRepository.del(failKeys.toArray(String[]::new));
        }
        return lockKeys.size();
    }

    /**
     * 转义 Redis 模式通配符：账号里若含 * ? [ ] \\ 不能当成 glob 匹配，
     * 否则管理员“解锁某个账号”可能扫到别人的锁定键。
     */
    private static String escapeGlob(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == ']' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 锁定账号快照（管理端展示用） */
    public record LockedAccount(String account, long remainingSeconds, int ipCount, List<String> ips) {
    }

    /**
     * 登录成功后清除失败计数与锁定
     */
    public void clearLoginFailures(String account) {
        clearLoginFailures(account, IpUtils.getIpAddr());
    }

    /**
     * 登录成功后清除失败计数与锁定（指定 IP，协议端使用）
     */
    public void clearLoginFailures(String account, String clientIp) {
        redisRepository.del(KEY_LOGIN_FAIL + guardKey(account, clientIp),
                KEY_LOGIN_LOCK + guardKey(account, clientIp));
    }

    /**
     * 注册前检查：IP 是否已被禁止注册
     */
    public void checkRegisterAllowed() {
        if (Boolean.TRUE.equals(redisRepository.hasKey(KEY_REGISTER_LOCK + IpUtils.getIpAddr()))) {
            throw new BusinessException(I18nUtils.getMessage("user.register.locked"));
        }
    }

    /**
     * 记录一次注册失败；达到上限后禁止该 IP 注册
     */
    public void recordRegisterFailure() {
        String ip = IpUtils.getIpAddr();
        String key = KEY_REGISTER_FAIL + ip;
        Long count = redisRepository.incr(key, 1);
        if (count != null && count == 1) {
            redisRepository.expire(key, lockMinutes * 60L);
        }
        if (count != null && count >= maxAttempts) {
            redisRepository.setExpire(KEY_REGISTER_LOCK + ip, maxAttempts, lockMinutes * 60L);
            redisRepository.del(key);
        }
    }

    /**
     * 注册成功后清除失败计数
     */
    public void clearRegisterFailures() {
        redisRepository.del(KEY_REGISTER_FAIL + IpUtils.getIpAddr());
    }

    /**
     * 锁定维度：账号 + 客户端IP（账号 trim 后原样使用）
     */
    private String guardKey(String account) {
        return guardKey(account, IpUtils.getIpAddr());
    }

    /**
     * 锁定维度：账号 + 指定IP；IP 缺失归入 unknown，避免把「取不到 IP」的协议端全部并进某个真实 IP
     */
    private String guardKey(String account, String clientIp) {
        String ip = (clientIp == null || clientIp.isBlank()) ? "unknown" : clientIp.trim();
        return (account == null ? "" : account.trim()) + ":" + ip;
    }
}
