package com.guanghe.fs.service.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 对外文件服务配置更新参数
 */
@Data
@Schema(description = "对外文件服务配置更新参数")
public class UpdateServiceSettingCmd {

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "监听端口（仅 sftp 需要，1024-65535）")
    private Integer port;

    @Schema(description = "监听地址（仅 sftp）")
    private String bindAddress;
}
