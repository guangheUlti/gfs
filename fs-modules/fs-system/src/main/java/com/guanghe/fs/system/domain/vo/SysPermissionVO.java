package com.guanghe.fs.system.domain.vo;

import com.guanghe.fs.system.domain.SysPermission;
import io.github.linpeilie.annotations.AutoMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
@AutoMapper(target = SysPermission.class)
@Schema(description = "权限信息")
public class SysPermissionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "权限ID")
    private Long id;

    @Schema(description = "权限代码")
    private String permissionCode;

    @Schema(description = "权限名称")
    private String permissionName;

    @Schema(description = "权限模块")
    private String module;

    @Schema(description = "权限描述")
    private String description;

    @Schema(description = "权限排序")
    private Integer sort;
}

