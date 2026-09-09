package com.guanghe.fs.storage.plugin.smb.config;

import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import lombok.Data;

/**
 * SMB 存储配置
 *
 * @Author: guangheUlti
 * @Date: 2026/09/09
 */
@Data
public class SmbConfig {

    /** 服务器地址 */
    private String smbHost;

    /** 端口（默认445），字符串入参（前端按 string 提交） */
    private String smbPort;

    /** 域（可选） */
    private String smbDomain;

    /** 共享名 */
    private String smbShare;

    /** 用户名 */
    private String smbUsername;

    /** 密码 */
    private String smbPassword;

    /** 分片临时目录（可选，默认 ${java.io.tmpdir}/gfs-storage-temp/smb） */
    private String tempPath;

    /** 从 StorageConfig 转换为配置对象 */
    public static SmbConfig toObject(StorageConfig config) {
        return config.toObject(SmbConfig.class);
    }
}
