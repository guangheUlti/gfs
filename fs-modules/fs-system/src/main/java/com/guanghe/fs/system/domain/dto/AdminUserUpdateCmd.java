package com.guanghe.fs.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员修改账号资料（用户名是登录标识，不允许修改）
 *
 * @Author: guangheUlti
 * @Date: 2026/10/29
 */
@Data
public class AdminUserUpdateCmd {

    @NotBlank(message = "nickname不能为空")
    @Size(max = 64, message = "nickname长度不能超过64个字符")
    private String nickname;
}
