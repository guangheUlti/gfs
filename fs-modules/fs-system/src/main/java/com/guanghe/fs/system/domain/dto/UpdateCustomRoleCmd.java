package com.guanghe.fs.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class UpdateCustomRoleCmd implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "角色名称不能为空")
    private String roleName;

    private String description;

    @NotEmpty(message = "请至少选择一项权限")
    private List<String> permissions;
}
