package com.guanghe.fs.system.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.guanghe.fs.framework.common.utils.DateUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理员账号列表项
 *
 * @Author: guangheUlti
 * @Date: 2026/10/29
 */
@Data
@Schema(description = "账号信息")
public class AdminUserVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "id")
    private String id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "头像")
    private String avatar;

    @Schema(description = "状态：0正常 1禁用 2待审核 3已拒绝")
    private Integer status;

    @Schema(description = "是否系统管理员")
    private boolean superAdmin;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = DateUtils.DATE_TIME_PATTERN)
    private LocalDateTime createdAt;

    @Schema(description = "最后登录时间")
    @JsonFormat(pattern = DateUtils.DATE_TIME_PATTERN)
    private LocalDateTime lastLoginAt;

    @Schema(description = "当前在线终端数")
    private int onlineTerminals;

    @Schema(description = "是否被防暴力锁定")
    private boolean locked;

    @Schema(description = "锁定剩余秒数")
    private Long lockRemainingSeconds;
}
