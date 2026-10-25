package com.guanghe.fs.service.webdav;

import com.guanghe.fs.framework.redis.repository.RedisRepository;
import com.guanghe.fs.system.auth.LoginGuardService;
import com.guanghe.fs.system.auth.PasswordHashService;
import com.guanghe.fs.system.constant.UserStatus;
import com.guanghe.fs.system.domain.SysUser;
import com.guanghe.fs.system.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 协议端（WebDAV / SFTP / FTP 共用的 {@link DavUserAuthenticator}）防暴力锁定行为。
 * <p>
 * 覆盖：连续失败达上限后锁定（正确密码也拒绝、且锁定期间不再查库/算哈希）、
 * 计数的 IP 维度隔离、成功登录清零、账号状态异常不计入密码试错。
 */
class ProtocolAuthLockoutTest {

    private static final String USERNAME = "alice";
    private static final String GOOD_PASSWORD = "good-password";
    private static final String BAD_PASSWORD = "bad-password";
    private static final String IP = "10.0.0.7";

    private MutableRedis redis;
    private LoginGuardService guard;
    private PasswordHashService passwordHashService;
    private SysUserMapper userMapper;
    private DavUserAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        redis = new MutableRedis();
        guard = new LoginGuardService(redis);
        ReflectionTestUtils.setField(guard, "maxAttempts", 3);
        ReflectionTestUtils.setField(guard, "lockMinutes", 30);

        passwordHashService = mock(PasswordHashService.class);
        userMapper = mock(SysUserMapper.class);

        SysUser user = new SysUser();
        user.setId("u-1");
        user.setUsername(USERNAME);
        user.setPassword("encoded");
        user.setStatus(UserStatus.NORMAL);
        when(userMapper.selectOneByQuery(any())).thenReturn(user);

        when(passwordHashService.matches(anyString(), anyString()))
                .thenAnswer(inv -> GOOD_PASSWORD.equals(inv.getArgument(0)));

        authenticator = new DavUserAuthenticator(userMapper, passwordHashService, guard);
    }

    @Test
    void threeFailuresLockAccountOnThatIpOnly() {
        for (int i = 0; i < 3; i++) {
            assertNull(authenticator.authenticate(USERNAME, BAD_PASSWORD, IP));
        }
        assertTrue(guard.isLoginLocked(USERNAME, IP), "连续失败达上限应锁定");

        // 锁定期间密码正确也拒绝
        assertNull(authenticator.authenticate(USERNAME, GOOD_PASSWORD, IP));
        // 且锁定请求不再查库、不再做哈希运算（避免锁定期间被当哈希计算器）
        verify(userMapper, times(3)).selectOneByQuery(any());
        verify(passwordHashService, times(3)).matches(anyString(), anyString());

        // 其他 IP 不受影响
        assertNotNull(authenticator.authenticate(USERNAME, GOOD_PASSWORD, "10.0.0.8"));
    }

    @Test
    void successfulLoginResetsFailureCount() {
        assertNull(authenticator.authenticate(USERNAME, BAD_PASSWORD, IP));
        assertNull(authenticator.authenticate(USERNAME, BAD_PASSWORD, IP));
        assertNotNull(authenticator.authenticate(USERNAME, GOOD_PASSWORD, IP));

        // 计数已清零：再来 2 次失败仍不到上限
        assertNull(authenticator.authenticate(USERNAME, BAD_PASSWORD, IP));
        assertNull(authenticator.authenticate(USERNAME, BAD_PASSWORD, IP));
        assertFalse(guard.isLoginLocked(USERNAME, IP));
        assertNotNull(authenticator.authenticate(USERNAME, GOOD_PASSWORD, IP));
    }

    @Test
    void missingIpUsesSeparateBucket() {
        // FTP 拿不到客户端 IP 时传 null，归入 unknown 桶，不与真实 IP 的锁互相污染
        for (int i = 0; i < 3; i++) {
            assertNull(authenticator.authenticate(USERNAME, BAD_PASSWORD, null));
        }
        assertTrue(guard.isLoginLocked(USERNAME, null));
        assertFalse(guard.isLoginLocked(USERNAME, IP));
        assertNotNull(authenticator.authenticate(USERNAME, GOOD_PASSWORD, IP));
    }

    @Test
    void disabledAccountDoesNotCountAsPasswordFailure() {
        SysUser disabled = new SysUser();
        disabled.setId("u-2");
        disabled.setUsername(USERNAME);
        disabled.setPassword("encoded");
        disabled.setStatus(UserStatus.DISABLED);
        when(userMapper.selectOneByQuery(any())).thenReturn(disabled);

        for (int i = 0; i < 5; i++) {
            assertNull(authenticator.authenticate(USERNAME, GOOD_PASSWORD, IP));
        }
        assertFalse(guard.isLoginLocked(USERNAME, IP), "账号状态拒绝不应计入密码试错次数");
    }

    /**
     * 内存版 Redis：只实现防暴力守卫用到的几个操作（哈希表 + 计数），TTL 不参与断言。
     */
    private static final class MutableRedis extends RedisRepository {

        private final Map<String, Object> store = new ConcurrentHashMap<>();

        private MutableRedis() {
            super(null);
        }

        @Override
        public boolean expire(String key, long time) {
            return true;
        }

        @Override
        public Boolean hasKey(String key) {
            return store.containsKey(key);
        }

        @Override
        public void del(String... key) {
            Arrays.stream(key).forEach(store::remove);
        }

        @Override
        public boolean setExpire(String key, Object value, long time) {
            store.put(key, value);
            return true;
        }

        @Override
        public Long incr(String key, long delta) {
            Object current = store.get(key);
            long next = (current == null ? 0L : ((Number) current).longValue()) + delta;
            store.put(key, next);
            return next;
        }
    }
}
