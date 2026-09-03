package com.guanghe.fs.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建工作空间命令对象
 *
 * @Author: guangheUlti
 * @Date: 2026/31 11:24
 */
@Data
public class CreateWorkspaceCmd {

    @NotBlank(message = "工作空间名称不能为空")
    private String name;

    @NotBlank(message = "工作空间标识不能为空")
    private String slug;

    private String description;

}
