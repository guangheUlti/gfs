package com.guanghe.fs.system.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 被锁定的账号（连续登录失败触发防暴力锁定，供管理端解锁）
 * <p>
 * 锁定状态存在 Redis，账号可能根本不存在（比如扫描器乱试用户名），
 * 因此 nickname / avatar 允许为空。
 *
 * @Author: guangheUlti
 * @Date: 2026/10/25
 */
@Data
@Schema(description = "被锁定账号信息")
public class LockedUserVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "登录账号")
    private String username;

    @Schema(description = "昵称（账号存在时才有）")
    private String nickname;

    @Schema(description = "头像（账号存在时才有）")
    private String avatar;

    @Schema(description = "剩余锁定秒数（多 IP 锁定取最大值）")
    private long remainingSeconds;

    @Schema(description = "被锁定的来源 IP 数量")
    private int ipCount;
}
