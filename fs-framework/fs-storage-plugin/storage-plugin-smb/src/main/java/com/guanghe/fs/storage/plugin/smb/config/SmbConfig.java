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

    /** 是否启用域账号（AD/域）；true 时才使用 smbDomain，否则按本地/工作组账号处理 */
    private boolean smbDomainEnabled;

    /** 域（可选，仅在 smbDomainEnabled=true 时参与认证） */
    private String smbDomain;

    /** 共享名（空 = 动态共享模式：在文件页根目录列出主机全部共享） */
    private String smbShare;

    /** 用户名 */
    private String smbUsername;

    /** 密码 */
    private String smbPassword;

    /** 是否匿名访问（Guest）；true 时用户名/密码可不填，用匿名会话建连 */
    private boolean smbAnonymous;

    /** 从 StorageConfig 转换为配置对象 */
    public static SmbConfig toObject(StorageConfig config) {
        return config.toObject(SmbConfig.class);
    }
}
