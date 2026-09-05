package com.guanghe.fs.system.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 在线登录终端VO（一个 token 对应一个终端）
 *
 * @Author: guangheUlti
 * @Date: 2026/9/5
 */
@Data
@Schema(description = "在线登录终端")
public class OnlineTerminalVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "终端序号，同一账号内从 0 开始，用于定位要踢下线的会话")
    private Integer index;

    @Schema(description = "token 末 6 位，仅用于校验踢的是否还是同一个会话（列表被刷新后序号可能错位），不可用于登录")
    private String tokenTail;

    @Schema(description = "设备类型，未指定时为 Sa-Token 默认值 DEF")
    private String deviceType;

    @Schema(description = "登录 IP（改动前登录的会话可能为空）")
    private String ip;

    @Schema(description = "登录浏览器（改动前登录的会话可能为空）")
    private String browser;

    @Schema(description = "登录操作系统（改动前登录的会话可能为空）")
    private String os;

    @Schema(description = "登录时间（毫秒时间戳）")
    private Long loginTime;

    @Schema(description = "最后活跃时间（毫秒时间戳），未记录时为空")
    private Long lastActiveTime;

    @Schema(description = "是否为当前管理员本次请求所用的会话")
    private Boolean current;
}
