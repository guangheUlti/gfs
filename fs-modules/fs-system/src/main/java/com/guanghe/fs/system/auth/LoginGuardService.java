package com.guanghe.fs.system.auth;

import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.framework.common.utils.IpUtils;
import com.guanghe.fs.framework.redis.repository.RedisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
        if (Boolean.TRUE.equals(redisRepository.hasKey(KEY_LOGIN_LOCK + guardKey(account)))) {
            throw new BusinessException(I18nUtils.getMessage("user.locked"));
        }
    }

    /**
     * 记录一次登录失败；达到上限后锁定
     */
    public void recordLoginFailure(String account) {
        String key = KEY_LOGIN_FAIL + guardKey(account);
        Long count = redisRepository.incr(key, 1);
        if (count != null && count == 1) {
            redisRepository.expire(key, lockMinutes * 60L);
        }
        if (count != null && count >= maxAttempts) {
            redisRepository.setExpire(KEY_LOGIN_LOCK + guardKey(account), maxAttempts, lockMinutes * 60L);
            redisRepository.del(key);
        }
    }

    /**
     * 登录成功后清除失败计数与锁定
     */
    public void clearLoginFailures(String account) {
        redisRepository.del(KEY_LOGIN_FAIL + guardKey(account), KEY_LOGIN_LOCK + guardKey(account));
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
     * 锁定维度：账号 + 客户端IP（账号 trim 后原样使用，区分邮箱与用户名登录）
     */
    private String guardKey(String account) {
        return (account == null ? "" : account.trim()) + ":" + IpUtils.getIpAddr();
    }
}
