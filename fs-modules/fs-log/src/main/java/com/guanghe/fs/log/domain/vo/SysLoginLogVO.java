package com.guanghe.fs.log.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.guanghe.fs.framework.common.enums.LoginType;
import com.guanghe.fs.framework.common.utils.DateUtils;
import com.guanghe.fs.log.domain.SysLoginLog;
import io.github.linpeilie.annotations.AutoMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 登录日志VO对象
 *
 * @Author: guanghe
 * @Date: 2025/9/25 16:20
 */
@Data
@AutoMapper(target = SysLoginLog.class)
@Schema(description = "登录日志")
public class SysLoginLogVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "id")
    private Long id;

    @Schema(description = "用户ID")
    private String userId;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "登录IP地址")
    private String loginIp;

    @Schema(description = "登录地址")
    private String loginAddress;

    @Schema(description = "登录方式")
    private LoginType loginType;

    @Schema(description = "浏览器")
    private String browser;

    @Schema(description = "操作系统")
    private String os;

    @Schema(description = "状态")
    private Integer status;

    @Schema(description = "错误信息")
    private String msg;

    @Schema(description = "最后登录时间")
    @JsonFormat(pattern = DateUtils.DATE_TIME_PATTERN)
    private LocalDateTime loginTime;
}
