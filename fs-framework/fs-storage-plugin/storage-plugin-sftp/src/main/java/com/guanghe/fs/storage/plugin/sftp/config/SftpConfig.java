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

    /** 挂载根路径（相对登录用户主目录或绝对路径，作为网盘根目录；必填） */
    private String sftpRootPath;

    /** 认证方式：password（密码）/ key（私钥）；留空时兼容旧配置按私钥优先 */
    private String sftpAuthType;

    /** 密码（认证方式为 password 时使用） */
    private String sftpPassword;

    /** 私钥文件路径（认证方式为 key 时使用） */
    private String sftpPrivateKeyPath;

    /** known_hosts 文件路径（兼容旧配置；留空时使用运行目录 storage/known_hosts，界面已不再暴露） */
    private String sftpKnownHostsPath;

    /** 从 StorageConfig 转换为配置对象 */
    public static SftpConfig toObject(StorageConfig config) {
        return config.toObject(SftpConfig.class);
    }
}
