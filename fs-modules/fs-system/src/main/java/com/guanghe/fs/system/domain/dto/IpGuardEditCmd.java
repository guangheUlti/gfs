package com.guanghe.fs.system.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * IP 安全防护配置修改命令（整份覆盖：模式 + 两份名单一起保存）
 *
 * @Author: guangheUlti
 * @Date: 2026/11/4
 */
@Data
@Schema(description = "IP 安全防护配置修改命令")
public class IpGuardEditCmd {

    @NotBlank
    @Schema(description = "防护模式：OFF 关闭 / WHITE 白名单 / BLACK 黑名单")
    private String mode;

    @Schema(description = "白名单条目")
    private List<IpGuardRule> whiteList;

    @Schema(description = "黑名单条目")
    private List<IpGuardRule> blackList;
}
