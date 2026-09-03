package com.guanghe.fs.framework.common.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 基础分页查询对象
 *
 * @Author: guanghe
 * @Date: 2025/9/24 13:40
 */
@Data
public class PageQuery {


    @Schema(description = "页码", example = "1")
    @Min(value = 1, message = "页码最小为1")
    private Integer page;

    @Schema(description = "每页大小", example = "20")
    @Min(value = 1, message = "每页大小最小为1")
    private Integer pageSize;

    @Schema(description = "排序字段", example = "updateTime")
    private String orderBy;

    @Schema(description = "排序方向", example = "DESC", allowableValues = {"ASC", "DESC"})
    private String orderDirection = "DESC";
}
