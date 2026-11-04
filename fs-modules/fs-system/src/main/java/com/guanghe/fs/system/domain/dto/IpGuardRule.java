package com.guanghe.fs.system.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * IP 防护名单条目
 *
 * @Author: guangheUlti
 * @Date: 2026/11/4
 */
@Data
@Schema(description = "IP 防护名单条目")
public class IpGuardRule implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "IP 或 CIDR 网段，如 203.0.113.9、192.168.1.0/24、2001:db8::/32")
    private String ip;

    @Schema(description = "备注（仅用于说明这条是谁，不参与匹配）")
    private String remark;
}
