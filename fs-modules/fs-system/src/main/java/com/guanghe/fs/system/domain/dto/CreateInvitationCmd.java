package com.guanghe.fs.system.domain.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 创建邀请命令
 *
 * @Author: guangheUlti
 * @Date: 2026/3/31
 */
@Data
public class CreateInvitationCmd implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 邀请邮箱
     */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    /**
     * 角色ID
     */
    @NotNull(message = "角色ID不能为空")
    private Long roleId;

    /**
     * 邀请有效期（小时）
     */
    private Integer expiresHours = 72;
}
