package com.guanghe.fs.system.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.guanghe.fs.framework.common.utils.DateUtils;
import com.guanghe.fs.system.domain.SysWorkspace;
import io.github.linpeilie.annotations.AutoMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;


/**
 * 工作空间VO信息，用于展示工作空间信息
 *
 * @Author: guangheUlti
 * @Date: 2024/10/16 15:28
 */
@Data
@AutoMapper(target = SysWorkspace.class)
@Schema(description = "工作空间信息")
public class WorkspaceVO implements Serializable {

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

    @Schema(description = "所有者id")
    private String ownerId;

    @Schema(description = "成员数量")
    private Integer memberCount;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = DateUtils.DATE_TIME_PATTERN)
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    @JsonFormat(pattern = DateUtils.DATE_TIME_PATTERN)
    private LocalDateTime updatedAt;
}
