package com.guanghe.fs.system.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 管理员账号列表查询参数
 *
 * @Author: guangheUlti
 * @Date: 2026/10/29
 */
@Data
public class AdminUserQry {

    @Schema(description = "关键词，匹配用户名或昵称")
    private String keyword;

    @Schema(description = "账号状态：0正常 1禁用 2待审核 3已拒绝；为空查询全部")
    private Integer status;

    @Schema(description = "页码，从 1 开始")
    private Integer page = 1;

    @Schema(description = "每页条数")
    private Integer pageSize = 10;
}
