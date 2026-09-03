package com.guanghe.fs.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 批量创建角色权限命令
 *
 * @Author: guangheUlti
 * @Date: 2026/3/31
 */
@Data
public class BatchCreateRolePermissionsCmd implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 角色ID
     */
    @NotNull(message = "角色ID不能为空")
    private Long roleId;

    /**
     * 角色编码
     */
    @NotBlank(message = "角色编码不能为空")
    private String roleCode;

    /**
     * 权限编码列表
     */
    @NotEmpty(message = "权限编码列表不能为空")
    private List<String> permissionCodes;
}
