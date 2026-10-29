package com.guanghe.fs.system.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 管理员启用/禁用账号
 *
 * @Author: guangheUlti
 * @Date: 2026/10/29
 */
@Data
public class AdminUserStatusCmd {

    @NotNull(message = "enabled不能为空")
    private Boolean enabled;
}
