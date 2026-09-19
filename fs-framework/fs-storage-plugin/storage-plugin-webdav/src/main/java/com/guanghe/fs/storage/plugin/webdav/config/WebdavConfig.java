package com.guanghe.fs.storage.plugin.webdav.config;

import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import lombok.Data;

/**
 * WebDAV 存储配置
 *
 * @Author: guangheUlti
 * @Date: 2026/09/09
 */
@Data
public class WebdavConfig {

    /** 服务端点（完整 URL 前缀，如 https://dav.example.com/） */
    private String webdavEndpoint;

    /** 用户名 */
    private String webdavUsername;

    /** 密码 */
    private String webdavPassword;

    /** 基础路径（可选，拼在 endpoint 后） */
    private String webdavBasePath;

    /** 从 StorageConfig 转换为配置对象 */
    public static WebdavConfig toObject(StorageConfig config) {
        return config.toObject(WebdavConfig.class);
    }
}
