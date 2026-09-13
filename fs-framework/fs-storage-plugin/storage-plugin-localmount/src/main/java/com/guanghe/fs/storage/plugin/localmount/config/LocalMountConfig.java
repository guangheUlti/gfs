package com.guanghe.fs.storage.plugin.localmount.config;

import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import lombok.Data;

/**
 * 本地目录挂载配置
 *
 * @Author: guangheUlti
 * @Date: 2026/09/09
 */
@Data
public class LocalMountConfig {

    /** 挂载点显示名（用户根目录下的挂载目录名，默认"本地挂载"） */
    private String mountName;

    /** 挂载根路径（真实绝对路径，必填） */
    private String rootPath;

    /** 是否跟随符号链接（默认 false） */
    private String followSymlinks;

    /** 扫描间隔秒数（可选，覆盖全局扫描间隔） */
    private String rescanIntervalSeconds;

    /** 是否开启落盘加密（"true" 开启） */
    private String encryptionEnabled;

    /** 落盘加密口令（开启加密时必填） */
    private String encryptionSecret;

    /** 从 StorageConfig 转换为配置对象 */
    public static LocalMountConfig toObject(StorageConfig config) {
        return config.toObject(LocalMountConfig.class);
    }

    /** 是否开启落盘加密（容错解析，非 "true" 一律视为关闭） */
    public boolean isEncryptionEnabled() {
        return "true".equalsIgnoreCase(encryptionEnabled == null ? "" : encryptionEnabled.trim());
    }
}
