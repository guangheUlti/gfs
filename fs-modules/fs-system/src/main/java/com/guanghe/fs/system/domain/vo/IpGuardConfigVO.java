package com.guanghe.fs.system.domain.vo;

import com.guanghe.fs.system.domain.dto.IpGuardRule;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * IP 安全防护配置
 *
 * @Author: guangheUlti
 * @Date: 2026/11/4
 */
@Data
@Schema(description = "IP 安全防护配置")
public class IpGuardConfigVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "防护模式：OFF 关闭 / WHITE 白名单 / BLACK 黑名单")
    private String mode;

    @Schema(description = "白名单条目")
    private List<IpGuardRule> whiteList;

    @Schema(description = "黑名单条目")
    private List<IpGuardRule> blackList;

    @Schema(description = "当前操作者（本次请求）的来源 IP，用于确认要放行的地址")
    private String currentIp;
}
