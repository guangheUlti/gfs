package com.guanghe.fs.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员重置账号密码
 *
 * @Author: guangheUlti
 * @Date: 2026/10/29
 */
@Data
public class AdminResetPasswordCmd {

    @NotBlank(message = "password不能为空")
    @Size(min = 6, max = 128, message = "password长度必须在6到128个字符之间")
    private String password;
}
