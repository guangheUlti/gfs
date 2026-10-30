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

    @Schema(description = "只读模式（全部协议）：true 时只允许读访问，写操作被拒绝")
    private Boolean readOnly;

    @Schema(description = "游客访问（仅 alist）：true 时未登录也可浏览目录与播放，写操作仍需登录")
    private Boolean allowGuest;

    @Schema(description = "游客视角账号 ID（仅 alist）：匿名请求以该账号身份只读，传空表示清除")
    private String guestUserId;

    @Schema(description = "游客目录密码（仅 alist）：true 时游客请求必须携带公开账号的登录密码")
    private Boolean guestPassword;

    @Schema(description = "服务级存储配置 ID（全部协议）：外部访问固定落到该存储，Local 或空表示内置系统存储")
    private String storageSettingId;
}
