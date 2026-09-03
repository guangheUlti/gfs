package com.guanghe.fs.system.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 更新成员角色命令
 *
 * @Author: guangheUlti
 * @Date: 2026/3/31
 */
@Data
public class UpdateMemberRoleCmd implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 角色ID
     */
    @NotNull(message = "角色ID不能为空")
    private Long roleId;
}
