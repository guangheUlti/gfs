package com.guanghe.fs.system.domain.dto;

import com.guanghe.fs.framework.common.enums.LoginType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 登录DTO对象
 *
 * @Author: guangheUlti
 * @Date: 2024/6/7 11:24
 */
@Data
public class LoginCmd {

    @NotNull(message = "登录类型不能为空")
    private LoginType loginType;

    @NotBlank(message = "账号不能为空")
    private String account;

    @NotBlank(message = "密码不能为空，验证码模式下此为code")
    private String password;

    @Schema(description = "是否记住我：仅影响前端把 token 存在 localStorage 还是 sessionStorage，服务端不再据此下发持久 Cookie")
    private Boolean isRemember = false;
}
