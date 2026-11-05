package com.guanghe.fs.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.system.auth.LoginGuardService;
import com.guanghe.fs.system.auth.PasswordHashService;
import com.guanghe.fs.system.constant.UserStatus;
import com.guanghe.fs.system.domain.SysUser;
import com.guanghe.fs.system.domain.dto.AdminUserCreateCmd;
import com.guanghe.fs.system.mapper.SysUserMapper;
import com.guanghe.fs.system.service.impl.SysUserServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 手动创建用户接口的越权回归测试。
 * <p>
 * 2026-11-03 事件：普通账号 guanghe 口令泄露后直接调用 {@code POST /apis/admin/users}
 * 批量造出免审核账号。修复要求：仅系统管理员可调用，且非管理员不得触碰密码哈希与写库。
 */
class SysUserCreateByAdminAuthTest {

    private static final String LOGIN_ID = "u-login";
    private static final String SUPER_ADMIN = "admin";

    private SysUserMapper userMapper;
    private PasswordHashService passwordHashService;
    private SysUserServiceImpl userService;
    private MockedStatic<StpUtil> stpUtil;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        passwordHashService = mock(PasswordHashService.class);
        LoginGuardService loginGuardService = mock(LoginGuardService.class);
        SysUserTransferSettingService transferSettingService = mock(SysUserTransferSettingService.class);

        when(passwordHashService.encode(anyString())).thenReturn("encoded");
        when(userMapper.insert(any(SysUser.class), anyBoolean())).thenReturn(1);

        userService = new SysUserServiceImpl(
                null, null, transferSettingService, null, passwordHashService, loginGuardService);
        ReflectionTestUtils.setField(userService, "mapper", userMapper);
        ReflectionTestUtils.setField(userService, "superAdminUsername", SUPER_ADMIN);

        stpUtil = mockStatic(StpUtil.class);
        stpUtil.when(StpUtil::getLoginIdAsString).thenReturn(LOGIN_ID);
    }

    @AfterEach
    void tearDown() {
        stpUtil.close();
    }

    @Test
    void normalUserCannotCreateUser() {
        when(userMapper.selectOneById(LOGIN_ID)).thenReturn(user("alice"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.createUserByAdmin(cmd("attacker")));

        assertEquals(403, ex.getCode());
        // 越权请求不应走到建号逻辑：不查重、不算哈希、不写库
        verify(userMapper, never()).selectOneByQuery(any());
        verify(passwordHashService, never()).encode(anyString());
        verify(userMapper, never()).insert(any(SysUser.class), anyBoolean());
    }

    @Test
    void superAdminCreatesNormalAccountWithoutReview() {
        when(userMapper.selectOneById(LOGIN_ID)).thenReturn(user(SUPER_ADMIN));
        when(userMapper.selectOneByQuery(any())).thenReturn(null);

        userService.createUserByAdmin(cmd("newuser"));

        verify(userMapper).insert(org.mockito.ArgumentMatchers.argThat(user ->
                "newuser".equals(user.getUsername())
                        && "encoded".equals(user.getPassword())
                        && user.getStatus() == UserStatus.NORMAL), anyBoolean());
    }

    private static SysUser user(String username) {
        SysUser user = new SysUser();
        user.setId(LOGIN_ID);
        user.setUsername(username);
        return user;
    }

    private static AdminUserCreateCmd cmd(String username) {
        AdminUserCreateCmd cmd = new AdminUserCreateCmd();
        cmd.setUsername(username);
        cmd.setPassword("password123");
        return cmd;
    }
}
