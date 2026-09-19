package com.guanghe.fs.storage.plugin.sftp.config;

import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import lombok.Data;

/**
 * SFTP 存储配置
 *
 * @Author: guangheUlti
 * @Date: 2026/09/09
 */
@Data
public class SftpConfig {

    /** 服务器地址 */
    private String sftpHost;

    /** 端口（默认22） */
    private String sftpPort;

    /** 用户名 */
    private String sftpUsername;

    /** 密码（与私钥二选一） */
    private String sftpPassword;

    /** 私钥文件路径（可选，与密码二选一） */
    private String sftpPrivateKeyPath;

    /** known_hosts 文件路径（可选，缺省宽松校验） */
    private String sftpKnownHostsPath;

    /** 从 StorageConfig 转换为配置对象 */
    public static SftpConfig toObject(StorageConfig config) {
        return config.toObject(SftpConfig.class);
    }
}
