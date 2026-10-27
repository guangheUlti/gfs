package com.guanghe.fs.service.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 可被选为「游客视角」的账号（仅正常状态用户）
 */
@Data
@Schema(description = "游客视角候选账号")
public class ServiceGuestUserVO {

    @Schema(description = "账号 ID")
    private String id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "昵称")
    private String nickname;
}
