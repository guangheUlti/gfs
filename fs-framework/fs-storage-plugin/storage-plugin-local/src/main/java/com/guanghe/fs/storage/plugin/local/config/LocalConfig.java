package com.guanghe.fs.storage.plugin.local.config;

import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import lombok.Data;

/**
 * 本地存储配置对象（schema 属性映射）
 *
 * @Author: guangheUlti
 * @Date: 2026/09/13
 */
@Data
public class LocalConfig {

    /** 存储根路径 */
    private String basePath;

    /** 是否开启落盘加密（"true" 开启） */
    private String encryptionEnabled;

    /** 落盘加密口令（开启加密时必填） */
    private String encryptionSecret;

    /** 从 StorageConfig 转换为配置对象 */
    public static LocalConfig toObject(StorageConfig config) {
        return config.toObject(LocalConfig.class);
    }
}
