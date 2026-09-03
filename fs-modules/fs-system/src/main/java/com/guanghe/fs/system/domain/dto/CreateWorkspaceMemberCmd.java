package com.guanghe.fs.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 创建工作空间成员命令
 *
 * @Author: guangheUlti
 * @Date: 2026/3/31
 */
@Data
public class CreateWorkspaceMemberCmd implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工作空间ID
     */
    @NotBlank(message = "工作空间ID不能为空")
    private String workspaceId;

    /**
     * 用户ID
     */
    @NotBlank(message = "用户ID不能为空")
    private String userId;

    /**
     * 角色ID
     */
    @NotNull(message = "角色ID不能为空")
    private Long roleId;
}
