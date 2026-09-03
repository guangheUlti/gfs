package com.guanghe.fs.system.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 用户状态常量
 *
 * @Author: guangheUlti
 * @Date: 2026/9/1
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class UserStatus {

    /** 正常 */
    public static final int NORMAL = 0;
    /** 禁用 */
    public static final int DISABLED = 1;
    /** 待审核（新注册用户需管理员审核通过后才可登录） */
    public static final int PENDING_REVIEW = 2;
    /** 注册申请已拒绝 */
    public static final int REJECTED = 3;
}
