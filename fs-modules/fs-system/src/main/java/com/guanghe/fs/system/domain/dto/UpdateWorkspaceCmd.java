package com.guanghe.fs.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 更新工作空间命令
 *
 * @Author: guangheUlti
 * @Date: 2026/3/31
 */
@Data
public class UpdateWorkspaceCmd implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工作空间名称
     */
    @NotBlank(message = "工作空间名称不能为空")
    private String name;

    /**
     * 描述
     */
    private String description;
}
