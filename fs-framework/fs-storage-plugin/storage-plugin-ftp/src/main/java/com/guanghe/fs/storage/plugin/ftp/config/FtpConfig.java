package com.guanghe.fs.storage.plugin.ftp.config;

import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import lombok.Data;

/**
 * FTP/FTPS 存储配置
 *
 * @Author: guangheUlti
 * @Date: 2026/09/09
 */
@Data
public class FtpConfig {

    /** 服务器地址 */
    private String ftpHost;

    /** 端口（默认21） */
    private String ftpPort;

    /** 用户名 */
    private String ftpUsername;

    /** 密码 */
    private String ftpPassword;

    /** 是否启用 FTPS（"true"/"false"） */
    private String ftpsEnabled;

    /** 被动模式（默认true，"true"/"false"） */
    private String passiveMode;

    /** 控制连接编码（默认UTF-8） */
    private String controlEncoding;

    /** 从 StorageConfig 转换为配置对象 */
    public static FtpConfig toObject(StorageConfig config) {
        return config.toObject(FtpConfig.class);
    }
}
