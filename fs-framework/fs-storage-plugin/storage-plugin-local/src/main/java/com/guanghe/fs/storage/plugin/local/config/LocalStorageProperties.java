package com.guanghe.fs.storage.plugin.local.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 本地存储配置（从application.yml读取）
 *
 * @Author: guangheUlti
 * @Date: 2026/01/12 22:06
 */
@Data
@Component
@ConfigurationProperties(prefix = "fs.storage.local")
public class LocalStorageProperties {

    /**
     * 存储基础路径
     */
    private String basePath = "/data/files";

    /**
     * 访问基础URL
     */
    private String baseUrl = "http://localhost:80/files";

    /**
     * 转换为 StorageConfig 的 properties Map
     */
    public Map<String, Object> toPropertiesMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("basePath", basePath);
        map.put("baseUrl", baseUrl);
        return map;
    }
}
