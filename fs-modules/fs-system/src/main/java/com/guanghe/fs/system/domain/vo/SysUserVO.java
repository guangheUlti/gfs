package com.guanghe.fs.system.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.guanghe.fs.framework.common.utils.DateUtils;
import com.guanghe.fs.system.domain.SysUser;
import io.github.linpeilie.annotations.AutoMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户VO信息，用于展示用户信息（不含权限，权限从工作空间接口获取）
 *
 * @Author: guangheUlti
 * @Date: 2024/10/16 15:28
 */
@Data
@AutoMapper(target = SysUser.class)
@Schema(description = "用户信息")
public class SysUserVO implements Serializable {

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

    @Schema(description = "邮箱地址")
    private String email;

    @Schema(description = "状态")
    private Integer status;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = DateUtils.DATE_TIME_PATTERN)
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    @JsonFormat(pattern = DateUtils.DATE_TIME_PATTERN)
    private LocalDateTime updatedAt;

    @Schema(description = "最后登录时间")
    @JsonFormat(pattern = DateUtils.DATE_TIME_PATTERN)
    private LocalDateTime lastLoginAt;

    @Schema(description = "是否设置密码")
    private Boolean isSetPassword;

    @Schema(description = "是否系统管理员")
    private Boolean isSuperAdmin;
}
