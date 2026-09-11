package com.guanghe.fs.service.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对外文件服务配置 VO（含实时运行状态）
 */
@Data
@Schema(description = "对外文件服务配置")
public class ServiceSettingVO {

    @Schema(description = "服务类型：webdav / sftp")
    private String serviceType;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "监听端口（webdav 为空 = 复用 HTTP 80 端口）")
    private Integer port;

    @Schema(description = "监听地址")
    private String bindAddress;

    @Schema(description = "运行状态：running / stopped / error")
    private String status;

    @Schema(description = "运行错误信息（status=error 时有值）")
    private String error;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
