package com.guanghe.fs.system.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.guanghe.fs.framework.common.utils.DateUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 工作空间详情 VO（含当前用户角色权限，平铺结构）
 *
 * @Author: guangheUlti
 * @Date: 2026/3/31
 */
@Data
@Schema(description = "工作空间详情")
public class WorkspaceDetailVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "工作空间ID")
    private String id;

    @Schema(description = "工作空间名称")
    private String name;

    @Schema(description = "URL友好的唯一标识")
    private String slug;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "所有者ID")
    private String ownerId;

    @Schema(description = "成员数量")
    private Integer memberCount;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = DateUtils.DATE_TIME_PATTERN)
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    @JsonFormat(pattern = DateUtils.DATE_TIME_PATTERN)
    private LocalDateTime updatedAt;

    @Schema(description = "当前用户角色编码")
    private String roleCode;

    @Schema(description = "当前用户角色名称")
    private String roleName;

    @Schema(description = "当前用户权限列表")
    private List<String> permissions;
}
