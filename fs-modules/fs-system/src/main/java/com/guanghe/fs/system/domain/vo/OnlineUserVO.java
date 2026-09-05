package com.guanghe.fs.system.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 在线登录用户VO（一个账号 + 其全部在线终端）
 *
 * @Author: guangheUlti
 * @Date: 2026/9/5
 */
@Data
@Schema(description = "在线登录用户")
public class OnlineUserVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "登录标识（即用户id）")
    private String loginId;

    @Schema(description = "用户名，账号已被删除时为空")
    private String username;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "头像")
    private String avatar;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "账号状态，取值见 UserStatus；账号已被删除时为空")
    private Integer status;

    @Schema(description = "该账号当前在线的终端列表")
    private List<OnlineTerminalVO> terminals;
}
