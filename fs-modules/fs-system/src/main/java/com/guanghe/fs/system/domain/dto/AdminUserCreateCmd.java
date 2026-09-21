package com.guanghe.fs.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员手动创建用户 cmd。
 * <p>
 * 与开放注册的区别：昵称可选、创建即可登录（不进入待审核），
 * 密码策略与注册一致（最短 6 位）。
 *
 * @Author: guangheUlti
 * @Date: 2026/9/21
 */
@Data
public class AdminUserCreateCmd {

    @NotBlank(message = "username不能为空")
    @Size(max = 64, message = "username长度不能超过64个字符")
    private String username;

    @NotBlank(message = "password不能为空")
    @Size(min = 6, max = 128, message = "password长度必须在6到128个字符之间")
    private String password;

    /** 可选，缺省回退用户名 */
    @Size(max = 64, message = "nickname长度不能超过64个字符")
    private String nickname;
}
