package com.guanghe.fs.system.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.guanghe.fs.framework.common.utils.DateUtils;
import com.guanghe.fs.system.domain.SysRole;
import io.github.linpeilie.annotations.AutoMapper;
import io.github.linpeilie.annotations.AutoMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 角色VO，用于展示角色信息
 *
 * @Author: guangheUlti
 * @Date: 2024/6/7 11:08
 */
@Data
@AutoMapper(target = SysRole.class)
@Schema(description = "角色信息")
public class SysRoleVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "角色ID")
    private Long id;

    @Schema(description = "角色编码")
    private String roleCode;

    @Schema(description = "角色名称")
    private String roleName;

    @AutoMapping(source = "description")
    @Schema(description = "角色描述")
    private String description;

    @Schema(description = "角色类型：0 系统预设，1 自定义")
    private Integer roleType;

    @Schema(description = "权限编码列表（详情接口返回）")
    private List<String> permissions;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = DateUtils.DATE_TIME_PATTERN)
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    @JsonFormat(pattern = DateUtils.DATE_TIME_PATTERN)
    private LocalDateTime updatedAt;
}
